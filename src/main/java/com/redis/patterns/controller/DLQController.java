package com.redis.patterns.controller;

import com.redis.patterns.dto.DLQParameters;
import com.redis.patterns.dto.DLQResponse;
import com.redis.patterns.dto.TestScenarioRequest;
import com.redis.patterns.service.DLQMessagingService;
import com.redis.patterns.service.DLQTestScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for DLQ operations.
 * 
 * This controller provides HTTP endpoints for:
 * - Executing DLQ operations (claim_or_dlq)
 * - Managing streams and consumer groups
 * - Running test scenarios
 * - Monitoring system status
 * 
 * All endpoints return structured JSON responses and handle errors gracefully.
 * 
 * @author Redis Patterns Team
 */
@Slf4j
@RestController
@RequestMapping("/dlq")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Configure appropriately for production
public class DLQController {

    private final DLQMessagingService dlqMessagingService;
    private final DLQTestScenarioService testScenarioService;

    /**
     * Executes the claim_or_dlq operation with the provided parameters.
     * 
     * POST /api/dlq/claim
     * 
     * @param params DLQ parameters
     * @return Response containing operation results
     */
    @PostMapping("/claim")
    public ResponseEntity<DLQResponse> claimOrDLQ(@Valid @RequestBody DLQParameters params) {
        log.info("Received claim_or_dlq request: {}", params);
        
        try {
            DLQResponse response = dlqMessagingService.claimOrDLQ(params);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error executing claim_or_dlq", e);
            return ResponseEntity.internalServerError()
                .body(DLQResponse.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build());
        }
    }

    /**
     * Initializes the consumer group for a stream.
     * 
     * POST /api/dlq/init
     * 
     * @param params DLQ parameters containing stream and group names
     * @return Success response
     */
    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> initializeConsumerGroup(
            @Valid @RequestBody DLQParameters params) {
        log.info("Initializing consumer group: {}", params.getConsumerGroup());
        
        try {
            dlqMessagingService.initializeConsumerGroup(
                params.getStreamName(), 
                params.getConsumerGroup()
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Consumer group initialized");
            response.put("streamName", params.getStreamName());
            response.put("consumerGroup", params.getConsumerGroup());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error initializing consumer group", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Produces a test message to the stream.
     * 
     * POST /api/dlq/produce
     * 
     * @param request Request containing stream name and message payload
     * @return Response with message ID
     */
    @PostMapping("/produce")
    public ResponseEntity<Map<String, Object>> produceMessage(
            @RequestBody Map<String, Object> request) {
        String streamName = (String) request.get("streamName");
        @SuppressWarnings("unchecked")
        Map<String, String> payload = (Map<String, String>) request.get("payload");
        
        log.info("Producing message to stream: {}", streamName);
        
        try {
            String messageId = dlqMessagingService.produceMessage(streamName, payload);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("messageId", messageId);
            response.put("streamName", streamName);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error producing message", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Gets statistics about the streams.
     *
     * GET /api/dlq/stats?streamName=xxx&dlqStreamName=yyy&groupName=zzz
     *
     * @param streamName Main stream name
     * @param dlqStreamName DLQ stream name
     * @param groupName Consumer group name
     * @return Statistics map
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam String streamName,
            @RequestParam String dlqStreamName,
            @RequestParam String groupName) {

        log.debug("Getting stats for stream: {}", streamName);

        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("streamLength", dlqMessagingService.getStreamLength(streamName));
            stats.put("dlqLength", dlqMessagingService.getStreamLength(dlqStreamName));
            stats.put("pendingCount", dlqMessagingService.getPendingCount(streamName, groupName));
            stats.put("success", true);

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting stats", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Reads messages from a stream.
     *
     * GET /api/dlq/messages?streamName=xxx&count=10
     *
     * @param streamName Stream name to read from
     * @param count Number of messages to read (default: 10)
     * @return List of messages
     */
    @GetMapping("/messages")
    public ResponseEntity<Map<String, Object>> getMessages(
            @RequestParam String streamName,
            @RequestParam(defaultValue = "10") int count) {

        log.debug("Reading {} messages from stream: {}", count, streamName);

        try {
            var messages = dlqMessagingService.readMessages(streamName, count);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("streamName", streamName);
            response.put("messages", messages);
            response.put("count", messages.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error reading messages from stream: {}", streamName, e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Runs a test scenario (step-by-step or high-volume).
     *
     * POST /api/dlq/test
     *
     * @param request Test scenario request
     * @return Success response
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> runTestScenario(
            @Valid @RequestBody TestScenarioRequest request) {
        log.info("Running test scenario: {}", request.getScenarioType());

        try {
            if (request.getScenarioType() == TestScenarioRequest.ScenarioType.STEP_BY_STEP) {
                testScenarioService.runStepByStepScenario(request);
            } else if (request.getScenarioType() == TestScenarioRequest.ScenarioType.HIGH_VOLUME) {
                testScenarioService.runHighVolumeScenario(request);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Test scenario started");
            response.put("scenarioType", request.getScenarioType());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error starting test scenario", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Stops the currently running high-volume test.
     *
     * POST /api/dlq/test/stop
     *
     * @return Success response
     */
    @PostMapping("/test/stop")
    public ResponseEntity<Map<String, Object>> stopTest() {
        log.info("Stopping high-volume test");

        testScenarioService.stopHighVolumeTest();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Stop signal sent");

        return ResponseEntity.ok(response);
    }

    /**
     * Gets the status of the high-volume test.
     *
     * GET /api/dlq/test/status
     *
     * @return Status response
     */
    @GetMapping("/test/status")
    public ResponseEntity<Map<String, Object>> getTestStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("running", testScenarioService.isHighVolumeTestRunning());
        response.put("success", true);

        return ResponseEntity.ok(response);
    }

    /**
     * Cleans up streams for testing.
     *
     * DELETE /api/dlq/cleanup
     *
     * @param streamName Main stream name
     * @param dlqStreamName DLQ stream name
     * @return Success response
     */
    @DeleteMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanup(
            @RequestParam String streamName,
            @RequestParam String dlqStreamName) {
        log.info("Cleaning up streams: {}, {}", streamName, dlqStreamName);

        try {
            dlqMessagingService.cleanup(streamName, dlqStreamName);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Streams cleaned up");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error cleaning up streams", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}

