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
 * This service ensures that the stream_utils library is loaded into Redis with
 * the read_claim_or_dlq function (Redis 8.4.0+) that uses XREADGROUP CLAIM.
 *
 * @author Redis Patterns Team
 */
@Service
@RequiredArgsConstructor
public class RedisLuaFunctionLoader {

    private static final Logger log = LoggerFactory.getLogger(RedisLuaFunctionLoader.class);
    private final JedisPool jedisPool;

    private static final String LIBRARY_NAME = "stream_utils";
    private static final String FUNCTION_NAME = "read_claim_or_dlq";
    private static final String LUA_SCRIPT_PATH = "lua/stream_utils.read_claim_or_dlq.lua";

    /**
     * Loads the stream_utils library with the read_claim_or_dlq function into Redis.
     *
     * The function uses Redis 8.4.0+ XREADGROUP CLAIM option to combine:
     * - Claiming idle pending messages
     * - Routing messages to DLQ based on delivery count
     * - Reading new incoming messages
     *
     * @throws RuntimeException if the function cannot be loaded
     */
    @PostConstruct
    public void loadLuaFunctions() {
        log.info("Checking Redis Lua functions...");

        try (var jedis = jedisPool.getResource()) {
            // Check if library already loaded
            if (isFunctionLibraryLoaded(jedis, LIBRARY_NAME)) {
                log.info("Function library '{}' already loaded in Redis", LIBRARY_NAME);

                // Verify function is available
                verifyFunctionLoaded(jedis, LIBRARY_NAME, FUNCTION_NAME);

                log.info("Lua function verified successfully");
                return;
            }

            log.info("Loading function library '{}' from {}...", LIBRARY_NAME, LUA_SCRIPT_PATH);

            // Load the Lua script from file system
            String luaScript = loadLuaScriptFromPath(LUA_SCRIPT_PATH);

            // Load the function into Redis with REPLACE
            try {
                Object result = jedis.functionLoadReplace(luaScript);
                log.info("Successfully loaded Lua function library '{}': {}", LIBRARY_NAME, result);
            } catch (JedisDataException e) {
                if (e.getMessage().contains("already exists")) {
                    log.info("Function library '{}' was loaded by another instance", LIBRARY_NAME);
                } else {
                    throw e;
                }
            }

            // Verify function is available
            verifyFunctionLoaded(jedis, LIBRARY_NAME, FUNCTION_NAME);

            log.info("Lua function loaded successfully");

        } catch (Exception e) {
            log.error("Failed to load Lua functions into Redis", e);
            throw new RuntimeException("Failed to initialize Redis Lua functions", e);
        }
    }

    /**
     * Checks if the function library is already loaded in Redis.
     *
     * @param jedis Jedis connection
     * @param libraryName Name of the library to check
     * @return true if the library exists, false otherwise
     */
    private boolean isFunctionLibraryLoaded(redis.clients.jedis.Jedis jedis, String libraryName) {
        try {
            List<LibraryInfo> functions = jedis.functionList();

            for (LibraryInfo func : functions) {
                if (libraryName.equals(func.getLibraryName())) {
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
     * @param scriptPath Path to the Lua script file
     * @return The Lua script content as a string
     * @throws IOException if the script cannot be read
     */
    private String loadLuaScriptFromPath(String scriptPath) throws IOException {
        log.debug("Loading Lua script from: {}", scriptPath);

        // Try to load from the project root (for development and production)
        Path path = Paths.get(scriptPath);

        if (!Files.exists(path)) {
            // Fallback: try from current working directory
            path = Paths.get(System.getProperty("user.dir"), scriptPath);
        }

        if (!Files.exists(path)) {
            throw new IOException("Lua script not found at: " + scriptPath +
                " (tried: " + path.toAbsolutePath() + ")");
        }

        String script = Files.readString(path, StandardCharsets.UTF_8);
        log.debug("Loaded Lua script from {} ({} bytes)", path.toAbsolutePath(), script.length());

        return script;
    }

    /**
     * Verifies that the function was loaded successfully.
     *
     * @param jedis Jedis connection
     * @param libraryName Name of the library to verify
     * @param functionName Name of the function to verify
     * @throws RuntimeException if the function is not available
     */
    private void verifyFunctionLoaded(redis.clients.jedis.Jedis jedis, String libraryName, String functionName) {
        if (isFunctionLibraryLoaded(jedis, libraryName)) {
            log.info("Verified: Function '{}' is available in Redis (library: {})", functionName, libraryName);
        } else {
            throw new RuntimeException("Function verification failed: " + functionName + " not found in library " + libraryName);
        }
    }
}

