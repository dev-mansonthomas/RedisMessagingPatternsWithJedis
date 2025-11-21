package com.redis.patterns.service;

import com.redis.patterns.dto.DLQEvent;
import com.redis.patterns.dto.DLQParameters;
import com.redis.patterns.dto.TestScenarioRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for running DLQ test scenarios.
 * 
 * This service provides two types of demonstrations:
 * 1. Step-by-step: Educational walkthrough of the DLQ pattern
 * 2. High-volume: Performance test with thousands of messages
 * 
 * All scenarios run asynchronously and broadcast events via WebSocket
 * for real-time visualization in the UI.
 * 
 * @author Redis Patterns Team
 */
@Service
@RequiredArgsConstructor
public class DLQTestScenarioService {

    private static final Logger log = LoggerFactory.getLogger(DLQTestScenarioService.class);
    private final DLQMessagingService dlqMessagingService;
    private final WebSocketEventService webSocketEventService;
    
    private final AtomicBoolean highVolumeRunning = new AtomicBoolean(false);
    private final Random random = new Random();

    /**
     * Runs a step-by-step demonstration of the DLQ pattern.
     * 
     * This scenario demonstrates:
     * 1. Message production
     * 2. Message consumption (without ACK)
     * 3. Message reclaim (deliveries < threshold)
     * 4. Message processing and ACK
     * 5. Message exceeding threshold and routing to DLQ
     * 
     * @param request Test scenario parameters
     */
    @Async
    public void runStepByStepScenario(TestScenarioRequest request) {
        DLQParameters params = request.getParameters();
        
        log.info("Starting step-by-step DLQ scenario");
        
        try {
            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.TEST_STARTED)
                .streamName(params.getStreamName())
                .details("Step-by-step scenario started")
                .build());
            
            // Step 1: Initialize
            dlqMessagingService.cleanup(params.getStreamName(), params.getDlqStreamName());
            Thread.sleep(500);
            dlqMessagingService.initializeConsumerGroup(params.getStreamName(), params.getConsumerGroup());
            Thread.sleep(500);
            
            // Step 2: Produce a message
            Map<String, String> payload1 = new HashMap<>();
            payload1.put("type", "order");
            payload1.put("id", "1001");
            payload1.put("scenario", "nominal");
            String msgId1 = dlqMessagingService.produceMessage(params.getStreamName(), payload1);
            Thread.sleep(1000);
            
            // Step 3: Consume without ACK (deliveries = 1)
            List<String> consumed = dlqMessagingService.consumeMessages(params, 1);
            Thread.sleep(1000);
            
            // Step 4: Reclaim (deliveries still 1, below threshold)
            dlqMessagingService.claimOrDLQ(params);
            Thread.sleep(1000);
            
            // Step 5: ACK the message
            if (!consumed.isEmpty()) {
                dlqMessagingService.acknowledgeMessage(
                    params.getStreamName(), 
                    params.getConsumerGroup(), 
                    consumed.get(0)
                );
            }
            Thread.sleep(1000);
            
            // Step 6: Produce another message for DLQ scenario
            Map<String, String> payload2 = new HashMap<>();
            payload2.put("type", "order");
            payload2.put("id", "2001");
            payload2.put("scenario", "dlq");
            dlqMessagingService.produceMessage(params.getStreamName(), payload2);
            Thread.sleep(1000);
            
            // Step 7: Consume without ACK
            dlqMessagingService.consumeMessages(params, 1);
            Thread.sleep(1000);
            
            // Step 8: Reclaim (deliveries = 2, at threshold)
            dlqMessagingService.claimOrDLQ(params);
            Thread.sleep(1000);
            
            // Step 9: Reclaim again (deliveries >= 2, goes to DLQ)
            dlqMessagingService.claimOrDLQ(params);
            Thread.sleep(1000);
            
            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.TEST_COMPLETED)
                .streamName(params.getStreamName())
                .details("Step-by-step scenario completed successfully")
                .build());
            
            log.info("Step-by-step scenario completed");

        } catch (Exception e) {
            log.error("Error in step-by-step scenario", e);
            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.ERROR)
                .streamName(params.getStreamName())
                .details("Scenario error: " + e.getMessage())
                .build());
        }
    }

    /**
     * Runs a high-volume, high-speed test with thousands of messages.
     *
     * This scenario:
     * 1. Produces many messages rapidly
     * 2. Simulates processing with configurable failure rate
     * 3. Messages that fail multiple times go to DLQ
     * 4. Provides real-time progress updates
     * 5. Can be stopped mid-execution
     *
     * @param request Test scenario parameters
     */
    @Async
    public void runHighVolumeScenario(TestScenarioRequest request) {
        if (!highVolumeRunning.compareAndSet(false, true)) {
            log.warn("High-volume test already running");
            return;
        }

        DLQParameters params = request.getParameters();
        int messageCount = request.getMessageCount();
        int failureRate = request.getFailureRatePercent();

        log.info("Starting high-volume test: {} messages, {}% failure rate",
            messageCount, failureRate);

        AtomicInteger produced = new AtomicInteger(0);
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        try {
            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.TEST_STARTED)
                .streamName(params.getStreamName())
                .details(String.format("High-volume test started: %d messages, %d%% failure rate",
                    messageCount, failureRate))
                .build());

            // Initialize
            dlqMessagingService.cleanup(params.getStreamName(), params.getDlqStreamName());
            Thread.sleep(500);
            dlqMessagingService.initializeConsumerGroup(params.getStreamName(), params.getConsumerGroup());
            Thread.sleep(500);

            // Produce messages in batches
            int batchSize = 100;
            for (int i = 0; i < messageCount && highVolumeRunning.get(); i++) {
                Map<String, String> payload = new HashMap<>();
                payload.put("type", "order");
                payload.put("id", String.valueOf(10000 + i));
                payload.put("batch", String.valueOf(i / batchSize));

                // Determine if this message should fail
                boolean shouldFail = random.nextInt(100) < failureRate;
                payload.put("shouldFail", String.valueOf(shouldFail));

                dlqMessagingService.produceMessage(params.getStreamName(), payload);
                produced.incrementAndGet();

                // Small delay between messages
                if (i % batchSize == 0) {
                    Thread.sleep(request.getDelayMs());

                    // Send progress update
                    webSocketEventService.broadcastEvent(DLQEvent.builder()
                        .eventType(DLQEvent.EventType.PROGRESS_UPDATE)
                        .streamName(params.getStreamName())
                        .details(String.format("Progress: %d/%d messages produced",
                            produced.get(), messageCount))
                        .build());
                }
            }

            // Process messages
            log.info("Processing {} messages", produced.get());

            int processRounds = 0;
            while (highVolumeRunning.get() && processRounds < 10) {
                // Consume messages
                List<String> consumed = dlqMessagingService.consumeMessages(params, batchSize);

                if (consumed.isEmpty()) {
                    // Try to reclaim pending messages
                    var response = dlqMessagingService.claimOrDLQ(params);

                    if (response.getMessagesReclaimed() == 0) {
                        break; // No more messages to process
                    }

                    // Simulate processing with failures
                    for (String msgId : response.getReclaimedMessageIds()) {
                        // Randomly fail some messages
                        if (random.nextInt(100) >= failureRate) {
                            dlqMessagingService.acknowledgeMessage(
                                params.getStreamName(),
                                params.getConsumerGroup(),
                                msgId
                            );
                            processed.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                        }
                    }
                } else {
                    // Process consumed messages
                    for (String msgId : consumed) {
                        if (random.nextInt(100) >= failureRate) {
                            dlqMessagingService.acknowledgeMessage(
                                params.getStreamName(),
                                params.getConsumerGroup(),
                                msgId
                            );
                            processed.incrementAndGet();
                        }
                    }
                }

                processRounds++;
                Thread.sleep(request.getDelayMs());

                // Progress update
                if (processRounds % 2 == 0) {
                    webSocketEventService.broadcastEvent(DLQEvent.builder()
                        .eventType(DLQEvent.EventType.PROGRESS_UPDATE)
                        .streamName(params.getStreamName())
                        .details(String.format("Processed: %d, Failed: %d",
                            processed.get(), failed.get()))
                        .build());
                }
            }

            // Final stats
            long dlqCount = dlqMessagingService.getStreamLength(params.getDlqStreamName());

            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.TEST_COMPLETED)
                .streamName(params.getStreamName())
                .details(String.format(
                    "High-volume test completed: Produced=%d, Processed=%d, DLQ=%d",
                    produced.get(), processed.get(), dlqCount))
                .build());

            log.info("High-volume test completed: produced={}, processed={}, dlq={}",
                produced.get(), processed.get(), dlqCount);

        } catch (Exception e) {
            log.error("Error in high-volume scenario", e);
            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.ERROR)
                .streamName(params.getStreamName())
                .details("High-volume test error: " + e.getMessage())
                .build());
        } finally {
            highVolumeRunning.set(false);
        }
    }

    /**
     * Stops the currently running high-volume test.
     */
    public void stopHighVolumeTest() {
        if (highVolumeRunning.compareAndSet(true, false)) {
            log.info("High-volume test stop requested");
            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.INFO)
                .details("High-volume test stopped by user")
                .build());
        }
    }

    /**
     * Checks if a high-volume test is currently running.
     *
     * @return true if running
     */
    public boolean isHighVolumeTestRunning() {
        return highVolumeRunning.get();
    }
}

