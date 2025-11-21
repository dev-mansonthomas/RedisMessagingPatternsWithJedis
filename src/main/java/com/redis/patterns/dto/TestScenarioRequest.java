package com.redis.patterns.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for test scenario requests.
 * 
 * This class defines parameters for running automated test scenarios,
 * including step-by-step demonstrations and high-volume tests.
 * 
 * @author Redis Patterns Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestScenarioRequest {

    /**
     * Type of test scenario to run
     */
    private ScenarioType scenarioType;

    /**
     * DLQ parameters to use for the test
     */
    private DLQParameters parameters;

    /**
     * Number of messages to generate for high-volume tests
     */
    @Min(value = 1, message = "Message count must be at least 1")
    @Max(value = 100000, message = "Message count cannot exceed 100,000")
    @Builder.Default
    private int messageCount = 1000;

    /**
     * Percentage of messages that should fail (0-100)
     * Used in high-volume tests to simulate failures
     */
    @Min(value = 0, message = "Failure rate must be between 0 and 100")
    @Max(value = 100, message = "Failure rate must be between 0 and 100")
    @Builder.Default
    private int failureRatePercent = 1;

    /**
     * Delay between message batches in milliseconds
     * Used to control the speed of high-volume tests
     */
    @Min(value = 0, message = "Delay must be non-negative")
    @Builder.Default
    private long delayMs = 10L;

    /**
     * Enum defining the types of test scenarios
     */
    public enum ScenarioType {
        /**
         * Step-by-step demonstration of DLQ pattern
         */
        STEP_BY_STEP,

        /**
         * High-volume, high-speed test with many messages
         */
        HIGH_VOLUME
    }
}

