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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for running DLQ test scenarios.
 *
 * This service provides step-by-step demonstrations of the DLQ pattern.
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
            dlqMessagingService.produceMessage(params.getStreamName(), payload1);
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
}

