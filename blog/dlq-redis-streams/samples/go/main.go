/*
Dead Letter Queue on Redis Streams — call read_claim_or_dlq and print its reply.
This sample assumes no prior Redis knowledge.

QUICKSTART — paste the indented lines into a terminal (needs Docker + the Go toolchain):

    git clone https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis.git
    cd RedisMessagingPatternsWithJedis
    export REDIS_URL="redis://localhost:$(./blog/dlq-redis-streams/samples/setup.sh)"
    cd blog/dlq-redis-streams/samples/go && go run .

setup.sh loads the Lua function and, if nothing is already listening on
localhost:6379, starts a throwaway Redis 8.8 in Docker on a free port and prints
that port — captured above into REDIS_URL. Blog post & the other five languages:
https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams

A 60-second primer
  - A Redis *stream* is an append-only log; each entry = an ID ("1699-0") plus a
    flat list of field/value pairs (["type","order.created","order_id","1001"]).
  - A *consumer group* tracks, per entry, which consumer holds it and how many
    times it was delivered — that delivery count is the DLQ pattern's retry budget.
  - We FCALL one server-side Lua function, read_claim_or_dlq (lua/stream_utils.lua),
    which Redis runs atomically, instead of sending the raw stream commands ourselves.

What read_claim_or_dlq returns (maps 1:1 to the Lua "return { a, b }")
  The reply is a 2-element array:
    outer[0] = messages to process -> each is [ id, [f1, v1, f2, v2, ...] ]
                                      (that flat list is what XREADGROUP returns)
    outer[1] = dlq_ids             -> each is [ original_id, new_dlq_id ]: the entry
                                      left test-stream and now lives in test-stream:dlq
  Either list may be empty. go-redis decodes the nested arrays as []interface{}
  (Go 1.18+ spells it "any") and leaf strings as string, so we type-assert down the tree.
*/
package main

import (
	"context"
	"fmt"
	"os"
	"strings"

	"github.com/redis/go-redis/v9"
)

// A Redis leaf may arrive as string or []byte depending on the value; render either as text.
func str(v any) string {
	if b, ok := v.([]byte); ok {
		return string(b)
	}
	return fmt.Sprintf("%v", v)
}

func main() {
	url := os.Getenv("REDIS_URL")
	if url == "" {
		url = "redis://localhost:6379"
	}

	// 1) Parse the URL and open a client.
	opt, err := redis.ParseURL(url)
	if err != nil {
		panic(err)
	}
	ctx := context.Background()
	rdb := redis.NewClient(opt)
	defer rdb.Close()

	// 2) Call the function. FCall(ctx, name, keys, args...) maps to the wire form
	//    FCALL <name> <numkeys> <keys...> <args...>; go-redis derives numkeys from
	//    len(keys).
	//      keys = stream, dlq_stream
	//      args = group, consumer, minIdle(ms), count, maxDeliver  (demo defaults)
	result, err := rdb.FCall(ctx, "read_claim_or_dlq",
		[]string{"test-stream", "test-stream:dlq"},
		"test-group", "consumer-1", "100", "100", "2").Result()
	if err != nil {
		panic(err)
	}

	// 3) The reply is the 2-element array [ messages_to_process, dlq_ids ].
	//    Anything else means there was nothing to do.
	outer, ok := result.([]any)
	if !ok || len(outer) < 2 {
		fmt.Println("no messages to process")
		return
	}
	toProcess, _ := outer[0].([]any) // [[id, [f1, v1, ...]], ...]
	dlqIds, _ := outer[1].([]any)    // [[original_id, dlq_id], ...]

	// 4) Entries to handle now: entry[0] = ID, entry[1] = flat [f1, v1, f2, v2, ...],
	//    which we walk two-by-two into "field=value" pairs.
	for _, m := range toProcess {
		entry := m.([]any)
		flat := entry[1].([]any)
		var fields strings.Builder
		for i := 0; i+1 < len(flat); i += 2 {
			fmt.Fprintf(&fields, "%s=%s ", str(flat[i]), str(flat[i+1]))
		}
		fmt.Printf("TO_PROCESS %s %s\n", str(entry[0]), strings.TrimSpace(fields.String()))
	}

	// 5) Entries the function just moved to the dead-letter stream: [original_id, new_dlq_id].
	for _, p := range dlqIds {
		pair := p.([]any)
		fmt.Printf("DLQ %s -> %s\n", str(pair[0]), str(pair[1]))
	}

	// 6) Nothing to process and nothing dead-lettered this poll.
	if len(toProcess) == 0 && len(dlqIds) == 0 {
		fmt.Println("no messages to process")
	}
}
