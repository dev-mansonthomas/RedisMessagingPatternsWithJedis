package com.redis.patterns.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for test scenario requests.
 *
 * This class defines parameters for running automated test scenarios.
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
     * Enum defining the types of test scenarios
     */
    public enum ScenarioType {
        /**
         * Step-by-step demonstration of DLQ pattern
         */
        STEP_BY_STEP
    }
}

