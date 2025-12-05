package com.redis.patterns.controller;

import com.redis.patterns.service.WorkQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for Work Queue / Competing Consumers pattern.
 * 
 * Endpoints:
 * - POST /api/work-queue/produce - Produce a single job
 * - GET /api/work-queue/streams - Get stream names for this pattern
 */
@Slf4j
@RestController
@RequestMapping("/work-queue")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WorkQueueController {

    private final WorkQueueService workQueueService;

    /**
     * Produce a single job to the job stream.
     * 
     * @param processingType "OK" for successful processing, "Error" for failed processing
     * @return Response with the produced message ID
     */
    @PostMapping("/produce")
    public ResponseEntity<Map<String, Object>> produceJob(
            @RequestParam(defaultValue = "OK") String processingType) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            String jobId = "JOB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String messageId = workQueueService.produceJob(jobId, processingType, null);
            
            response.put("success", true);
            response.put("jobId", jobId);
            response.put("messageId", messageId);
            response.put("processingType", processingType);
            
            log.debug("Produced job {} (type={})", jobId, processingType);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to produce job", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get stream names used by this pattern.
     */
    @GetMapping("/streams")
    public ResponseEntity<Map<String, Object>> getStreams() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("streams", workQueueService.getStreamNames());
        return ResponseEntity.ok(response);
    }

    /**
     * Clear all work queue streams and recreate consumer group.
     *
     * DELETE /api/work-queue/clear
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearAllStreams() {
        Map<String, Object> response = new HashMap<>();

        try {
            workQueueService.clearAllStreams();

            response.put("success", true);
            response.put("message", "All streams cleared and consumer group recreated");

            log.info("Work queue streams cleared");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to clear streams", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}

