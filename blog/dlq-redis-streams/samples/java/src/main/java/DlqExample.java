/*
Dead Letter Queue on Redis Streams — call read_claim_or_dlq and print its reply.
This sample assumes no prior Redis knowledge.

QUICKSTART — paste the indented lines into a terminal (needs Docker + JDK 21 + Maven):

    git clone https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis.git
    cd RedisMessagingPatternsWithJedis
    export REDIS_URL="redis://localhost:$(./blog/dlq-redis-streams/samples/setup.sh)"
    cd blog/dlq-redis-streams/samples/java && mvn -q compile exec:java

setup.sh loads the Lua function and, if nothing is already listening on
localhost:6379, starts a throwaway Redis 8.8 in Docker on a free port and prints
that port — captured above into REDIS_URL. Blog post & the other five languages:
https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams

A 60-second primer
  - A Redis *stream* is an append-only log. Each entry has an ID ("1699-0") and a
    flat list of field/value pairs, e.g. [type, order.created, order_id, 1001].
  - A *consumer group* tracks, per entry, which consumer holds it and how many
    times it was delivered — that delivery count is the DLQ retry budget.
  - We don't send the raw stream commands; we FCALL one server-side Lua function,
    read_claim_or_dlq (lua/stream_utils.lua), which Redis runs atomically.

What read_claim_or_dlq returns (maps 1:1 to the Lua "return { a, b }")
  The reply is a 2-element list:
    result.get(0) = messages to process -> each entry is [ id, [f1, v1, f2, v2, ...] ]
                                           (the flat field list XREADGROUP returns)
    result.get(1) = dlq_ids             -> each is [ original_id, new_dlq_id ]: the entry
                                           left test-stream and now lives in test-stream:dlq
  Either list may be empty. Jedis returns the nested Lua tables as nested Lists, and
  Redis bulk strings arrive as byte[] — hence the str() helper.
*/
import java.util.List;
import redis.clients.jedis.JedisPooled;

public class DlqExample {

    // A Redis bulk-string reply arrives as byte[]; render it (or anything else) as text.
    private static String str(Object o) {
        return (o instanceof byte[] b) ? new String(b) : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        String url = System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379");

        // 1) Connect (JedisPooled manages a small connection pool for us).
        try (JedisPooled jedis = new JedisPooled(url)) {

            // 2) Call the function. fcall(name, keys, args) maps to the wire form
            //    FCALL <name> <numkeys> <keys...> <args...>; Jedis fills numkeys
            //    from keys.size().
            //      KEYS = stream, dlq_stream
            //      ARGS = group, consumer, minIdle(ms), count, maxDeliver (demo defaults)
            Object result = jedis.fcall("read_claim_or_dlq",
                List.of("test-stream", "test-stream:dlq"),
                List.of("test-group", "consumer-1", "100", "100", "2"));

            // 3) The reply is the 2-element list [ messages_to_process, dlq_ids ].
            //    Anything else (e.g. a nil reply) means "nothing happened".
            if (!(result instanceof List<?> outer) || outer.size() < 2) {
                System.out.println("no messages to process");
                return;
            }
            List<Object> toProcess = (List<Object>) outer.get(0); // [[id, [f1, v1, ...]], ...]
            List<Object> dlqIds = (List<Object>) outer.get(1);    // [[original_id, dlq_id], ...]

            // 4) Entries to handle now. entry.get(0) is the ID; entry.get(1) is the flat
            //    [f1, v1, f2, v2, ...] list, which we walk two-by-two into "field=value".
            for (Object m : toProcess) {
                List<Object> entry = (List<Object>) m;
                List<Object> flat = (List<Object>) entry.get(1);
                StringBuilder fields = new StringBuilder();
                for (int i = 0; i + 1 < flat.size(); i += 2) {
                    fields.append(str(flat.get(i))).append('=').append(str(flat.get(i + 1))).append(' ');
                }
                System.out.println("TO_PROCESS " + str(entry.get(0)) + " " + fields.toString().trim());
            }

            // 5) Entries the function just moved to the dead-letter stream:
            //    pair.get(0) = original ID on test-stream, pair.get(1) = new ID in test-stream:dlq.
            for (Object p : dlqIds) {
                List<Object> pair = (List<Object>) p;
                System.out.println("DLQ " + str(pair.get(0)) + " -> " + str(pair.get(1)));
            }

            // 6) Nothing to process and nothing dead-lettered this poll.
            if (toProcess.isEmpty() && dlqIds.isEmpty()) {
                System.out.println("no messages to process");
            }
        }
    }
}
