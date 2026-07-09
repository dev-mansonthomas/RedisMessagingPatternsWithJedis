// Call the read_claim_or_dlq Redis Function (DLQ pattern) once and print the result.
//
// Blog post: https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams
// Prereq: Redis 8.8+ with samples/setup.sh applied. Target override: REDIS_URL env var.
// Run: go run .
package main

import (
	"context"
	"fmt"
	"os"
	"strings"

	"github.com/redis/go-redis/v9"
)

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
	opt, err := redis.ParseURL(url)
	if err != nil {
		panic(err)
	}
	ctx := context.Background()
	rdb := redis.NewClient(opt)
	defer rdb.Close()

	// KEYS: stream, dlq — ARGS: group, consumer, minIdleMs, count, maxDeliveries
	result, err := rdb.FCall(ctx, "read_claim_or_dlq",
		[]string{"test-stream", "test-stream:dlq"},
		"test-group", "consumer-1", "100", "100", "2").Result()
	if err != nil {
		panic(err)
	}

	outer, ok := result.([]any)
	if !ok || len(outer) < 2 {
		fmt.Println("no messages to process")
		return
	}
	toProcess, _ := outer[0].([]any) // [[id, [f1, v1, ...]], ...]
	dlqIds, _ := outer[1].([]any)    // [[original_id, dlq_id], ...]

	for _, m := range toProcess {
		entry := m.([]any)
		flat := entry[1].([]any)
		var fields strings.Builder
		for i := 0; i+1 < len(flat); i += 2 {
			fmt.Fprintf(&fields, "%s=%s ", str(flat[i]), str(flat[i+1]))
		}
		fmt.Printf("TO_PROCESS %s %s\n", str(entry[0]), strings.TrimSpace(fields.String()))
	}
	for _, p := range dlqIds {
		pair := p.([]any)
		fmt.Printf("DLQ %s -> %s\n", str(pair[0]), str(pair[1]))
	}
	if len(toProcess) == 0 && len(dlqIds) == 0 {
		fmt.Println("no messages to process")
	}
}
