package com.redis.patterns.service;

import com.redis.patterns.dto.DLQEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service implementing the Work Queue / Competing Consumers pattern.
 * 
 * Features:
 * - 4 worker Virtual Threads processing jobs in parallel
 * - Uses read_claim_or_dlq Lua function for atomic claim + DLQ routing
 * - Jobs with processingType=Error are not acknowledged (go to DLQ after 2 attempts)
 * - Jobs with processingType=OK are copied to worker-specific "done" streams
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkQueueService implements CommandLineRunner {

    private final JedisPool jedisPool;
    private final WebSocketEventService webSocketEventService;
    private final RedisStreamListenerService streamListenerService;

    // Stream names
    public static final String JOB_STREAM = "jobs.imageProcessing.v1";
    public static final String JOB_GROUP = "jobs-group";
    public static final String JOB_DLQ = "jobs.imageProcessing.v1:dlq";
    public static final String JOB_DONE_PREFIX = "jobs.done.worker-";

    // Configuration
    private static final int NUM_WORKERS = 4;
    private static final int MAX_DELIVERIES = 2;
    private static final long MIN_IDLE_MS = 100; // 100ms idle time
    private static final long POLL_INTERVAL_MS = 100; // Poll every 100ms
    private static final long PROCESSING_SLEEP_MS = 100; // 100ms processing time

    // Lua function
    private static final String FUNCTION_NAME = "read_claim_or_dlq";

    // Worker management
    private final Map<Integer, AtomicBoolean> workerRunning = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    @Override
    public void run(String... args) throws Exception {
        log.info("Starting Work Queue Service with {} workers", NUM_WORKERS);
        
        // Initialize consumer group
        initializeConsumerGroup();
        
        // Start monitoring job streams for WebSocket broadcasts
        streamListenerService.startMonitoring(JOB_STREAM);
        streamListenerService.startMonitoring(JOB_DLQ);
        for (int i = 1; i <= NUM_WORKERS; i++) {
            streamListenerService.startMonitoring(JOB_DONE_PREFIX + i);
        }
        
        // Start workers
        for (int i = 1; i <= NUM_WORKERS; i++) {
            startWorker(i);
        }
        
        log.info("Work Queue Service started successfully");
    }

    /**
     * Initialize the consumer group for the job stream.
     */
    private void initializeConsumerGroup() {
        try (var jedis = jedisPool.getResource()) {
            try {
                jedis.xgroupCreate(JOB_STREAM, JOB_GROUP, new StreamEntryID(), true);
                log.info("Consumer group '{}' created for stream '{}'", JOB_GROUP, JOB_STREAM);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    log.info("Consumer group '{}' already exists", JOB_GROUP);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Start a worker Virtual Thread.
     */
    private void startWorker(int workerId) {
        AtomicBoolean running = new AtomicBoolean(true);
        workerRunning.put(workerId, running);
        
        Thread.ofVirtual()
            .name("work-queue-worker-" + workerId)
            .start(() -> workerLoop(workerId, running));
        
        log.info("Started worker-{}", workerId);
    }

    /**
     * Worker loop - polls for jobs and processes them.
     */
    private void workerLoop(int workerId, AtomicBoolean running) {
        String consumerName = "worker-" + workerId;
        String doneStream = JOB_DONE_PREFIX + workerId;
        
        while (running.get() && !shutdown.get()) {
            try {
                processNextJob(workerId, consumerName, doneStream);
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Worker-{} error: {}", workerId, e.getMessage());
                try {
                    Thread.sleep(1000); // Back off on error
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("Worker-{} stopped", workerId);
    }

    /**
     * Process the next available job.
     */
    private void processNextJob(int workerId, String consumerName, String doneStream) {
        try (var jedis = jedisPool.getResource()) {
            // Call read_claim_or_dlq Lua function
            Object result = jedis.fcall(
                FUNCTION_NAME,
                Arrays.asList(JOB_STREAM, JOB_DLQ),
                Arrays.asList(JOB_GROUP, consumerName, String.valueOf(MIN_IDLE_MS), "1", String.valueOf(MAX_DELIVERIES))
            );
            
            if (!(result instanceof List)) return;
            
            @SuppressWarnings("unchecked")
            List<Object> resultList = (List<Object>) result;
            if (resultList.size() < 2) return;
            
            // Process DLQ messages (broadcast deletion events)
            processDLQMessages(resultList.get(1));
            
            // Process messages to work on
            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) resultList.get(0);

            for (Object msgItem : messages) {
                processMessage(jedis, workerId, consumerName, doneStream, msgItem);
            }
        }
    }

    /**
     * Process DLQ messages - broadcast deletion events.
     */
    private void processDLQMessages(Object dlqResult) {
        if (!(dlqResult instanceof List)) return;

        @SuppressWarnings("unchecked")
        List<Object> dlqMessages = (List<Object>) dlqResult;

        for (Object dlqItem : dlqMessages) {
            if (dlqItem instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> dlqEntry = (List<Object>) dlqItem;
                if (dlqEntry.size() >= 2) {
                    String originalId = convertToString(dlqEntry.get(0));
                    String dlqId = convertToString(dlqEntry.get(1));
                    log.info("Job {} routed to DLQ with ID {}", originalId, dlqId);

                    webSocketEventService.broadcastEvent(DLQEvent.builder()
                        .eventType(DLQEvent.EventType.MESSAGE_DELETED)
                        .messageId(originalId)
                        .streamName(JOB_STREAM)
                        .details("Job routed to DLQ (max deliveries reached)")
                        .build());
                }
            }
        }
    }

    /**
     * Process a single message.
     */
    @SuppressWarnings("unchecked")
    private void processMessage(redis.clients.jedis.Jedis jedis, int workerId, String consumerName,
                                String doneStream, Object msgItem) {
        if (!(msgItem instanceof List)) return;

        List<Object> msgEntry = (List<Object>) msgItem;
        if (msgEntry.size() < 2) return;

        String messageId = convertToString(msgEntry.get(0));
        List<Object> fieldsList = (List<Object>) msgEntry.get(1);

        // Parse fields
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i < fieldsList.size(); i += 2) {
            String key = convertToString(fieldsList.get(i));
            String value = convertToString(fieldsList.get(i + 1));
            fields.put(key, value);
        }

        String processingType = fields.getOrDefault("processingType", "OK");
        String jobId = fields.getOrDefault("jobId", "unknown");

        log.debug("Worker-{} processing job {} (type={})", workerId, jobId, processingType);

        try {
            // Simulate processing time
            Thread.sleep(PROCESSING_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if ("OK".equals(processingType)) {
            // Success: copy to done stream and ACK
            jedis.xadd(doneStream, XAddParams.xAddParams(), fields);
            jedis.xack(JOB_STREAM, JOB_GROUP, new StreamEntryID(messageId));

            log.info("Worker-{} completed job {} successfully", workerId, jobId);

            // Broadcast deletion from job stream
            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.MESSAGE_DELETED)
                .messageId(messageId)
                .streamName(JOB_STREAM)
                .details("Job completed by worker-" + workerId)
                .build());
        } else {
            // Error: do NOT acknowledge - will be retried or go to DLQ
            log.warn("Worker-{} failed to process job {} (will retry)", workerId, jobId);
        }
    }

    /**
     * Produce a job to the job stream.
     */
    public String produceJob(String jobId, String processingType, Map<String, String> additionalFields) {
        try (var jedis = jedisPool.getResource()) {
            Map<String, String> payload = new HashMap<>();
            payload.put("jobId", jobId);
            payload.put("processingType", processingType);
            payload.put("createdAt", java.time.Instant.now().toString());
            if (additionalFields != null) {
                payload.putAll(additionalFields);
            }

            StreamEntryID messageId = jedis.xadd(JOB_STREAM, XAddParams.xAddParams(), payload);
            log.debug("Produced job {} with messageId {}", jobId, messageId);

            return messageId.toString();
        }
    }

    /**
     * Stop all workers.
     */
    public void stopWorkers() {
        log.info("Stopping all workers");
        shutdown.set(true);
        workerRunning.values().forEach(running -> running.set(false));
    }

    /**
     * Helper to convert Redis response to String.
     */
    private String convertToString(Object obj) {
        if (obj instanceof byte[]) {
            return new String((byte[]) obj);
        } else if (obj instanceof String) {
            return (String) obj;
        } else {
            return obj.toString();
        }
    }

    /**
     * Get stream names for this pattern.
     */
    public Map<String, String> getStreamNames() {
        Map<String, String> names = new HashMap<>();
        names.put("jobStream", JOB_STREAM);
        names.put("dlqStream", JOB_DLQ);
        for (int i = 1; i <= NUM_WORKERS; i++) {
            names.put("doneStream" + i, JOB_DONE_PREFIX + i);
        }
        return names;
    }

    /**
     * Clear all work queue streams and recreate the consumer group.
     * This allows a clean restart without restarting the application.
     */
    public void clearAllStreams() {
        log.info("Clearing all work queue streams");

        try (var jedis = jedisPool.getResource()) {
            // Delete all streams
            List<String> streamsToDelete = new ArrayList<>();
            streamsToDelete.add(JOB_STREAM);
            streamsToDelete.add(JOB_DLQ);
            for (int i = 1; i <= NUM_WORKERS; i++) {
                streamsToDelete.add(JOB_DONE_PREFIX + i);
            }

            for (String stream : streamsToDelete) {
                try {
                    jedis.del(stream);
                    log.debug("Deleted stream: {}", stream);
                } catch (Exception e) {
                    log.warn("Could not delete stream {}: {}", stream, e.getMessage());
                }
            }

            // Recreate consumer group
            initializeConsumerGroup();

            log.info("All work queue streams cleared and consumer group recreated");
        }
    }
}
