package com.redis.patterns.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Redis connection.
 * 
 * These properties are loaded from application.yml and provide
 * type-safe configuration for the Redis connection pool.
 * 
 * @author Redis Patterns Team
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "redis")
public class RedisProperties {

    /**
     * Redis server hostname or IP address
     */
    private String host = "localhost";

    /**
     * Redis server port
     */
    private int port = 6379;

    /**
     * Redis password (optional)
     */
    private String password;

    /**
     * Connection timeout in milliseconds
     */
    private int timeout = 2000;

    /**
     * Connection pool configuration
     */
    private Pool pool = new Pool();

    @Data
    public static class Pool {
        /**
         * Maximum number of connections in the pool
         */
        private int maxTotal = 50;

        /**
         * Maximum number of idle connections
         */
        private int maxIdle = 20;

        /**
         * Minimum number of idle connections
         */
        private int minIdle = 5;

        /**
         * Maximum wait time for a connection (milliseconds)
         */
        private long maxWaitMillis = 3000;

        /**
         * Test connection on borrow
         */
        private boolean testOnBorrow = true;

        /**
         * Test connection on return
         */
        private boolean testOnReturn = false;

        /**
         * Test idle connections
         */
        private boolean testWhileIdle = true;
    }
}

