package com.redis.patterns.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;

/**
 * Redis configuration class that provides a shared JedisPool bean.
 *
 * This configuration is shared across all messaging patterns (DLQ and future patterns)
 * to ensure efficient connection management and resource utilization.
 *
 * The JedisPool provides:
 * - Connection pooling for better performance
 * - Automatic connection recovery
 * - Thread-safe operations
 * - Resource management
 *
 * IMPORTANT: The Lua function loader (RedisLuaFunctionLoader) will automatically
 * load the claim_or_dlq function AFTER this bean is created and the Redis connection
 * is successfully established. This ensures the function is available before any
 * DLQ operations are performed.
 *
 * @author Redis Patterns Team
 */
@Configuration
@RequiredArgsConstructor
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);
    private final RedisProperties redisProperties;

    /**
     * Creates and configures a JedisPool bean for Redis connections.
     * 
     * This pool is shared across all messaging patterns to ensure
     * efficient resource utilization and connection management.
     * 
     * @return Configured JedisPool instance
     */
    @Bean(destroyMethod = "close")
    public JedisPool jedisPool() {
        log.info("Initializing Redis connection pool...");
        log.info("Redis Host: {}", redisProperties.getHost());
        log.info("Redis Port: {}", redisProperties.getPort());
        
        // Configure the connection pool
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(redisProperties.getPool().getMaxTotal());
        poolConfig.setMaxIdle(redisProperties.getPool().getMaxIdle());
        poolConfig.setMinIdle(redisProperties.getPool().getMinIdle());
        poolConfig.setMaxWait(Duration.ofMillis(redisProperties.getPool().getMaxWaitMillis()));
        poolConfig.setTestOnBorrow(redisProperties.getPool().isTestOnBorrow());
        poolConfig.setTestOnReturn(redisProperties.getPool().isTestOnReturn());
        poolConfig.setTestWhileIdle(redisProperties.getPool().isTestWhileIdle());
        
        // Enable JMX monitoring
        poolConfig.setJmxEnabled(true);
        poolConfig.setJmxNamePrefix("redis-pool");
        
        log.info("Pool Configuration - MaxTotal: {}, MaxIdle: {}, MinIdle: {}", 
                poolConfig.getMaxTotal(), poolConfig.getMaxIdle(), poolConfig.getMinIdle());
        
        try {
            JedisPool pool;
            
            // Create pool with or without password
            if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
                log.info("Creating Redis pool with authentication");
                pool = new JedisPool(
                    poolConfig,
                    redisProperties.getHost(),
                    redisProperties.getPort(),
                    redisProperties.getTimeout(),
                    redisProperties.getPassword()
                );
            } else {
                log.info("Creating Redis pool without authentication");
                pool = new JedisPool(
                    poolConfig,
                    redisProperties.getHost(),
                    redisProperties.getPort(),
                    redisProperties.getTimeout()
                );
            }
            
            // Test the connection
            try (var jedis = pool.getResource()) {
                String pong = jedis.ping();
                log.info("Redis connection test successful: {}", pong);
            }
            
            log.info("Redis connection pool initialized successfully");
            return pool;
            
        } catch (Exception e) {
            log.error("Failed to initialize Redis connection pool", e);
            throw new RuntimeException("Failed to connect to Redis at " + 
                redisProperties.getHost() + ":" + redisProperties.getPort(), e);
        }
    }
}

