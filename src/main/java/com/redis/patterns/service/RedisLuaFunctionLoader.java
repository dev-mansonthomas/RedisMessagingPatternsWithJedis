package com.redis.patterns.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.resps.LibraryInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service responsible for loading Redis Lua functions on application startup.
 *
 * This service ensures that the stream_utils library is loaded into Redis with
 * all required functions:
 * - read_claim_or_dlq: DLQ pattern (Redis 8.4.0+) that uses XREADGROUP CLAIM
 * - request: Request/Reply pattern request sender
 * - response: Request/Reply pattern response sender
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
    private static final String REQUEST_FUNCTION_NAME = "request";
    private static final String RESPONSE_FUNCTION_NAME = "response";
    private static final String ROUTE_MESSAGE_FUNCTION_NAME = "route_message";
    private static final String ACQUIRE_TOKEN_FUNCTION_NAME = "acquire_token";
    private static final String RELEASE_TOKEN_FUNCTION_NAME = "release_token";
    private static final String RELEASE_LOCK_FUNCTION_NAME = "release_lock";
    private static final String LUA_SCRIPT_PATH = "lua/stream_utils.lua";

    /**
     * Loads the stream_utils library with all functions into Redis.
     *
     * Functions loaded:
     * - read_claim_or_dlq: DLQ pattern (Redis 8.4.0+) with XREADGROUP CLAIM
     * - request: Request/Reply pattern request sender
     * - response: Request/Reply pattern response sender
     *
     * @throws RuntimeException if the functions cannot be loaded
     */
    @PostConstruct
    public void loadLuaFunctions() {
        log.info("Loading Redis Lua functions from unified script...");

        try (var jedis = jedisPool.getResource()) {
            // Load the Lua script from file system
            String luaScript = loadLuaScriptFromPath(LUA_SCRIPT_PATH);

            log.info("Loaded Lua script: {} bytes", luaScript.length());

            // Load the function into Redis with REPLACE (force reload)
            Object result = jedis.functionLoadReplace(luaScript);
            log.info("Successfully loaded Lua function library '{}': {}", LIBRARY_NAME, result);

            // Verify every expected function is actually registered in the library
            verifyFunctionsLoaded(jedis, LIBRARY_NAME, List.of(
                FUNCTION_NAME,
                REQUEST_FUNCTION_NAME,
                RESPONSE_FUNCTION_NAME,
                ROUTE_MESSAGE_FUNCTION_NAME,
                ACQUIRE_TOKEN_FUNCTION_NAME,
                RELEASE_TOKEN_FUNCTION_NAME,
                RELEASE_LOCK_FUNCTION_NAME
            ));

            log.info("All Lua functions loaded and verified successfully");

        } catch (Exception e) {
            log.error("Failed to load Lua functions into Redis", e);
            throw new RuntimeException("Failed to initialize Redis Lua functions", e);
        }
    }

    /**
     * Loads the Lua script content.
     *
     * The classpath is the PRIMARY source so the script ships inside the packaged jar
     * (a build step may copy {@code lua/} onto the classpath). If the resource is absent
     * on the classpath, falls back to reading it from the file system relative to the
     * working directory, which preserves the previous local-dev behaviour.
     *
     * @param scriptPath Path to the Lua script (classpath resource and relative file path)
     * @return The Lua script content as a string
     * @throws IOException if the script cannot be found on the classpath nor the file system
     */
    private String loadLuaScriptFromPath(String scriptPath) throws IOException {
        log.debug("Loading Lua script from: {}", scriptPath);

        // PRIMARY: load from the classpath (works inside a packaged jar)
        ClassPathResource resource = new ClassPathResource(scriptPath);
        if (resource.exists()) {
            try (InputStream in = resource.getInputStream()) {
                String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                log.debug("Loaded Lua script from classpath:{} ({} bytes)", scriptPath, script.length());
                return script;
            }
        }

        // FALLBACK: load from the file system (local dev, running from project root)
        Path path = Paths.get(scriptPath);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir"), scriptPath);
        }

        if (!Files.exists(path)) {
            throw new IOException("Lua script not found on classpath (" + scriptPath +
                ") nor file system (tried: " + path.toAbsolutePath() + ")");
        }

        String script = Files.readString(path, StandardCharsets.UTF_8);
        log.debug("Loaded Lua script from {} ({} bytes)", path.toAbsolutePath(), script.length());

        return script;
    }

    /**
     * Verifies that every expected function is actually registered in the library.
     *
     * Calls FUNCTION LIST and inspects the function names reported for {@code libraryName},
     * rather than only checking that the library exists. If any expected function is
     * missing, throws so application startup fails fast.
     *
     * @param jedis Jedis connection
     * @param libraryName Name of the library to verify
     * @param expectedFunctions Function names that must be present in the library
     * @throws RuntimeException if the library or any expected function is missing
     */
    private void verifyFunctionsLoaded(redis.clients.jedis.Jedis jedis, String libraryName, List<String> expectedFunctions) {
        Set<String> registered = listFunctionNames(jedis, libraryName);

        if (registered.isEmpty()) {
            throw new RuntimeException("Function verification failed: library '" + libraryName + "' not found in Redis");
        }

        for (String expected : expectedFunctions) {
            if (!registered.contains(expected)) {
                throw new RuntimeException("Function verification failed: '" + expected +
                    "' not found in library '" + libraryName + "' (registered: " + registered + ")");
            }
        }

        log.info("Verified {} functions in library '{}': {}", registered.size(), libraryName, registered);
    }

    /**
     * Enumerates the function names registered under a given library via FUNCTION LIST.
     *
     * Jedis 7.1.0 exposes the function metadata as {@code LibraryInfo.getFunctions()},
     * a {@code List<Map<String, Object>>} where each map carries the raw FUNCTION LIST
     * reply for a function, including its {@code name} key.
     *
     * @param jedis Jedis connection
     * @param libraryName Name of the library to inspect
     * @return the set of function names registered in the library (empty if the library is absent)
     */
    private Set<String> listFunctionNames(redis.clients.jedis.Jedis jedis, String libraryName) {
        Set<String> names = new LinkedHashSet<>();
        try {
            List<LibraryInfo> libraries = jedis.functionList();
            for (LibraryInfo library : libraries) {
                if (!libraryName.equals(library.getLibraryName())) {
                    continue;
                }
                for (Map<String, Object> function : library.getFunctions()) {
                    Object name = function.get("name");
                    if (name != null) {
                        names.add(stringValue(name));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error enumerating functions for library '{}': {}", libraryName, e.getMessage());
        }
        return names;
    }

    /**
     * Normalises a FUNCTION LIST value to a String (Jedis may return byte[] depending on RESP version).
     */
    private String stringValue(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }
}

