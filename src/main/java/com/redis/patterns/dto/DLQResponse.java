package com.redis.patterns.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Data Transfer Object for DLQ operation responses.
 * 
 * This class wraps the results of DLQ operations, providing
 * structured information about what was processed.
 * 
 * @author Redis Patterns Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DLQResponse {

    /**
     * Number of messages reclaimed for processing
     */
    private int messagesReclaimed;

    /**
     * Number of messages routed to DLQ
     */
    private int messagesToDLQ;

    /**
     * List of message IDs that were reclaimed
     */
    private List<String> reclaimedMessageIds;

    /**
     * Success status of the operation
     */
    private boolean success;

    /**
     * Error message if operation failed
     */
    private String errorMessage;

    /**
     * Additional details about the operation
     */
    private String details;
}

