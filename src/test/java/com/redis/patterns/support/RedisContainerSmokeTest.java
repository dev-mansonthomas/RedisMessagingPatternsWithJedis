package com.redis.patterns.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity check that the Testcontainers Redis base wiring works: the container starts, the pool
 * connects, and {@code PING} answers. If this skips, Docker is unavailable in the environment.
 */
class RedisContainerSmokeTest extends AbstractRedisIntegrationTest {

    @Test
    void pingReturnsPong() {
        try (var jedis = jedisPool.getResource()) {
            assertThat(jedis.ping()).isEqualTo("PONG");
        }
    }
}
