package com.redis.patterns.example;

import com.redis.patterns.dto.DLQMessage;
import com.redis.patterns.dto.DLQParameters;
import com.redis.patterns.service.DLQMessagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Simple example showing how to use the unified DLQ messaging API.
 * 
 * This example demonstrates the recommended approach for processing messages
 * with automatic retry handling.
 * 
 * Key features:
 * - Single loop for all messages (new + retries)
 * - Automatic retry on failure (just don't acknowledge)
 * - Full context available (delivery count, retry status)
 * - Clean error handling
 * 
 * @author Redis Patterns Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleMessageProcessor {

    private final DLQMessagingService dlqService;

    /**
     * Example: Process orders from a Redis Stream with automatic retry.
     * 
     * This method shows the recommended pattern for message processing:
     * 1. Call getNextMessages() to get a batch (retries + new messages)
     * 2. Process each message
     * 3. Acknowledge on success
     * 4. Don't acknowledge on failure (automatic retry)
     */
    public void processOrders() {
        log.info("Starting order processor...");
        
        // Configure DLQ parameters
        DLQParameters params = DLQParameters.builder()
            .streamName("orders")
            .dlqStreamName("orders:dlq")
            .consumerGroup("order-processors")
            .consumerName("processor-1")
            .minIdleMs(5000)      // Retry after 5 seconds of inactivity
            .maxDeliveries(3)     // Max 3 attempts before sending to DLQ
            .count(10)            // Process up to 10 messages per batch
            .build();
        
        boolean running = true;
        
        while (running) {
            try {
                // 🎯 Get next batch of messages (unified API)
                // This returns both retry messages and new messages
                List<DLQMessage> messages = dlqService.getNextMessages(params, 10);
                
                if (messages.isEmpty()) {
                    log.debug("No messages to process, waiting...");
                    Thread.sleep(1000);
                    continue;
                }
                
                log.info("Processing {} messages", messages.size());
                
                // Process all messages with a single loop
                for (DLQMessage msg : messages) {
                    processMessage(msg);
                }
                
                // Small delay between batches
                Thread.sleep(100);
                
            } catch (InterruptedException e) {
                log.info("Processor interrupted, stopping...");
                running = false;
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Error in processing loop", e);
                try {
                    Thread.sleep(5000); // Back off on error
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
        
        log.info("Order processor stopped");
    }
    
    /**
     * Process a single message with error handling.
     * 
     * @param msg Message to process
     */
    private void processMessage(DLQMessage msg) {
        try {
            // Log context information
            if (msg.isRetry()) {
                log.warn("🔄 Retry attempt #{} for order {}", 
                    msg.getDeliveryCount() - 1, 
                    msg.getField("order_id"));
            } else {
                log.info("📨 New order: {}", msg.getField("order_id"));
            }
            
            // Your business logic here
            String orderId = msg.getField("order_id");
            String orderType = msg.getField("type");
            
            // Simulate processing
            processOrderLogic(orderId, orderType);
            
            // ✅ Success: Acknowledge the message
            dlqService.acknowledgeMessage(
                msg.getStreamName(),
                msg.getConsumerGroup(),
                msg.getId()
            );
            
            log.info("✅ Successfully processed order {} ({})", 
                orderId, 
                msg.getStateDescription());
            
        } catch (Exception e) {
            // ❌ Failure: Don't acknowledge
            // The message will be automatically retried
            log.error("❌ Failed to process message {} ({}): {}", 
                msg.getId(), 
                msg.getStateDescription(),
                e.getMessage());
            
            // Optional: Add special handling for last attempt
            if (msg.getDeliveryCount() >= 2) {
                log.error("⚠️ This is the last retry attempt before DLQ!");
                // Could send alert, notification, etc.
            }
        }
    }
    
    /**
     * Simulated business logic for processing an order.
     * 
     * @param orderId Order ID
     * @param orderType Order type
     * @throws Exception if processing fails
     */
    private void processOrderLogic(String orderId, String orderType) throws Exception {
        // Simulate some processing time
        Thread.sleep(50);
        
        // Simulate occasional failures (for demo purposes)
        if (Math.random() < 0.1) { // 10% failure rate
            throw new Exception("Simulated processing failure");
        }
        
        // Your actual business logic would go here
        log.debug("Processing order {} of type {}", orderId, orderType);
    }
}

