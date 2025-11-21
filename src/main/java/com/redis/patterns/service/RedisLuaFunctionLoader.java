package com.redis.patterns.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.resps.LibraryInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Service responsible for loading Redis Lua functions on application startup.
 * 
 * This service ensures that the claim_or_dlq function is loaded into Redis
 * if it doesn't already exist. The function is loaded from the classpath
 * and registered with Redis using the FUNCTION LOAD command.
 * 
 * The claim_or_dlq function provides atomic operations for:
 * - Claiming pending messages from a stream
 * - Routing messages to DLQ based on delivery count
 * - Acknowledging messages that exceed the threshold
 * 
 * @author Redis Patterns Team
 */
@Service
@RequiredArgsConstructor
public class RedisLuaFunctionLoader {

    private static final Logger log = LoggerFactory.getLogger(RedisLuaFunctionLoader.class);
    private final JedisPool jedisPool;

    private static final String LIBRARY_NAME = "stream_utils";
    private static final String FUNCTION_NAME = "claim_or_dlq";
    // Source the existing Lua script from the project root to avoid code duplication
    private static final String LUA_SCRIPT_PATH = "lua/stream_utils.claim_or_dlq.lua";

    /**
     * Loads the claim_or_dlq Lua function into Redis on application startup.
     * 
     * This method:
     * 1. Checks if the function library already exists
     * 2. If not, loads the Lua script from classpath
     * 3. Registers the function with Redis
     * 4. Verifies the function is available
     * 
     * @throws RuntimeException if the function cannot be loaded
     */
    @PostConstruct
    public void loadLuaFunctions() {
        log.info("Checking Redis Lua functions...");
        
        try (var jedis = jedisPool.getResource()) {
            // Check if the function library already exists
            if (isFunctionLibraryLoaded(jedis)) {
                log.info("Function library '{}' already loaded in Redis", LIBRARY_NAME);
                return;
            }
            
            log.info("Function library '{}' not found, loading from classpath...", LIBRARY_NAME);
            
            // Load the Lua script from classpath
            String luaScript = loadLuaScriptFromClasspath();
            
            // Load the function into Redis
            try {
                Object result = jedis.functionLoad(luaScript);
                log.info("Successfully loaded Lua function library '{}': {}", LIBRARY_NAME, result);
            } catch (JedisDataException e) {
                // If function already exists (race condition), that's okay
                if (e.getMessage().contains("already exists")) {
                    log.info("Function library '{}' was loaded by another instance", LIBRARY_NAME);
                } else {
                    throw e;
                }
            }
            
            // Verify the function is available
            verifyFunctionLoaded(jedis);
            
        } catch (Exception e) {
            log.error("Failed to load Lua functions into Redis", e);
            throw new RuntimeException("Failed to initialize Redis Lua functions", e);
        }
    }

    /**
     * Checks if the function library is already loaded in Redis.
     * 
     * @param jedis Jedis connection
     * @return true if the library exists, false otherwise
     */
    private boolean isFunctionLibraryLoaded(redis.clients.jedis.Jedis jedis) {
        try {
            List<LibraryInfo> functions = jedis.functionList();

            for (LibraryInfo func : functions) {
                if (LIBRARY_NAME.equals(func.getLibraryName())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Error checking for existing functions: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Loads the Lua script from the file system.
     *
     * Sources the existing Lua script from the project root to avoid code duplication.
     * The script is located at: lua/stream_utils.claim_or_dlq.lua
     *
     * @return The Lua script content as a string
     * @throws IOException if the script cannot be read
     */
    private String loadLuaScriptFromClasspath() throws IOException {
        log.debug("Loading Lua script from: {}", LUA_SCRIPT_PATH);

        // Try to load from the project root (for development and production)
        Path scriptPath = Paths.get(LUA_SCRIPT_PATH);

        if (!Files.exists(scriptPath)) {
            // Fallback: try from current working directory
            scriptPath = Paths.get(System.getProperty("user.dir"), LUA_SCRIPT_PATH);
        }

        if (!Files.exists(scriptPath)) {
            throw new IOException("Lua script not found at: " + LUA_SCRIPT_PATH +
                " (tried: " + scriptPath.toAbsolutePath() + ")");
        }

        String script = Files.readString(scriptPath, StandardCharsets.UTF_8);
        log.debug("Loaded Lua script from {} ({} bytes)", scriptPath.toAbsolutePath(), script.length());

        return script;
    }

    /**
     * Verifies that the function was loaded successfully.
     * 
     * @param jedis Jedis connection
     * @throws RuntimeException if the function is not available
     */
    private void verifyFunctionLoaded(redis.clients.jedis.Jedis jedis) {
        if (isFunctionLibraryLoaded(jedis)) {
            log.info("Verified: Function '{}' is available in Redis", FUNCTION_NAME);
        } else {
            throw new RuntimeException("Function verification failed: " + FUNCTION_NAME + " not found");
        }
    }
}

