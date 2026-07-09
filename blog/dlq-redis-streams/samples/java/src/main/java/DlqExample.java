/**
 * Call the read_claim_or_dlq Redis Function (DLQ pattern) once and print the result.
 *
 * Blog post: https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams
 * Prereq: Redis 8.8+ with samples/setup.sh applied. Target override: REDIS_URL env var.
 * Run: mvn -q compile exec:java
 */
import java.util.List;
import redis.clients.jedis.JedisPooled;

public class DlqExample {

    private static String str(Object o) {
        return (o instanceof byte[] b) ? new String(b) : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        String url = System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379");
        try (JedisPooled jedis = new JedisPooled(url)) {
            // KEYS: stream, dlq — ARGS: group, consumer, minIdleMs, count, maxDeliveries
            Object result = jedis.fcall("read_claim_or_dlq",
                List.of("test-stream", "test-stream:dlq"),
                List.of("test-group", "consumer-1", "100", "100", "2"));

            if (!(result instanceof List<?> outer) || outer.size() < 2) {
                System.out.println("no messages to process");
                return;
            }
            List<Object> toProcess = (List<Object>) outer.get(0); // [[id, [f1, v1, ...]], ...]
            List<Object> dlqIds = (List<Object>) outer.get(1);    // [[original_id, dlq_id], ...]

            for (Object m : toProcess) {
                List<Object> entry = (List<Object>) m;
                List<Object> flat = (List<Object>) entry.get(1);
                StringBuilder fields = new StringBuilder();
                for (int i = 0; i + 1 < flat.size(); i += 2) {
                    fields.append(str(flat.get(i))).append('=').append(str(flat.get(i + 1))).append(' ');
                }
                System.out.println("TO_PROCESS " + str(entry.get(0)) + " " + fields.toString().trim());
            }
            for (Object p : dlqIds) {
                List<Object> pair = (List<Object>) p;
                System.out.println("DLQ " + str(pair.get(0)) + " -> " + str(pair.get(1)));
            }
            if (toProcess.isEmpty() && dlqIds.isEmpty()) {
                System.out.println("no messages to process");
            }
        }
    }
}
