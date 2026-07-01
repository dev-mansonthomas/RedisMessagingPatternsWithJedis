package com.redis.patterns.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Base class for services that run <b>one Virtual Thread per conversation id</b> (a blocking
 * Redis-stream loop). Owns the idempotent start/stop registry so subclasses only implement the loop.
 *
 * <p>Extracted so the responder and the token listener share a single lifecycle implementation
 * (start/stop/stop-all/reaping hooks) instead of each maintaining its own copy.
 */
@Slf4j
public abstract class AbstractPerCidWorker {

    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    /** Thread-name prefix, e.g. {@code "llm-responder-"}. */
    protected abstract String threadNamePrefix();

    /** The per-cid loop; runs until {@code active} returns false. */
    protected abstract void runLoop(String cid, BooleanSupplier active);

    /** Idempotently start the worker for {@code cid}. */
    public void startFor(String cid) {
        running.computeIfAbsent(cid, key -> {
            AtomicBoolean flag = new AtomicBoolean(true);
            Thread.ofVirtual().name(threadNamePrefix() + key).start(() -> runLoop(key, flag::get));
            log.debug("Started {}{}", threadNamePrefix(), key);
            return flag;
        });
    }

    /** Stop the worker for {@code cid} (its loop exits on its next iteration). */
    public void stopFor(String cid) {
        AtomicBoolean flag = running.remove(cid);
        if (flag != null) {
            flag.set(false);
        }
    }

    /** Stop every worker; also invoked on bean destruction. */
    @PreDestroy
    public void stopAll() {
        running.values().forEach(flag -> flag.set(false));
        running.clear();
    }

    public boolean isRunning(String cid) {
        AtomicBoolean flag = running.get(cid);
        return flag != null && flag.get();
    }

    protected int activeCount() {
        return running.size();
    }
}
