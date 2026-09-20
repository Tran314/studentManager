package com.utils;

import com.service.BusinessException;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-internal sliding-window rate limiter.
 *
 * <p>Two-stage protection per key:
 * <ol>
 *   <li>Sliding window: reject after {@code maxWindow} hits within {@code window}.</li>
 *   <li>Lockout: after {@code lockThreshold} failures (recorded via
 *       {@link #recordFailure(String)}), reject for {@code lockDuration}.</li>
 * </ol>
 *
 * <p>Per-deque mutations happen inside a {@code synchronized} block on the deque
 * instance, which is safe because ConcurrentHashMap guarantees a stable mapping
 * from key to deque for the lifetime of the synchronization.
 *
 * <p>Memory bound: keys are pruned by a probabilistic sweep that runs roughly
 * every {@link #CLEANUP_EVERY_NTH_CALL} {@code check()} calls. Without this an
 * attacker who rotates {@code username} could grow the maps without bound
 * because the sliding-window deques are themselves capped at
 * {@code maxWindow} entries per key.
 */
public final class RateLimiter {

    private static final ConcurrentHashMap<String, Deque<Long>> HITS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Deque<Long>> FAILURES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> LOCK_UNTIL = new ConcurrentHashMap<>();

    /** Counter for probabilistic cleanup; only the parity matters. */
    private static final AtomicInteger CALL_COUNTER = new AtomicInteger();
    /** Run cleanup every Nth check(); cheap, amortised. */
    private static final int CLEANUP_EVERY_NTH_CALL = 64;

    private RateLimiter() {
    }

    public static void check(String key, int maxWindow, Duration window, int lockThreshold, Duration lockDuration) {
        maybeCleanup();

        long now = System.currentTimeMillis();

        Long locked = LOCK_UNTIL.get(key);
        if (locked != null && locked > now) {
            long seconds = Math.max(1, (locked - now + 999) / 1000);
            throw new BusinessException(429, "尝试过于频繁，已临时锁定，请约 " + seconds + " 秒后再试。");
        }

        long cutoff = now - window.toMillis();
        Deque<Long> hits = HITS.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst() < cutoff) {
                hits.pollFirst();
            }
            if (hits.size() >= maxWindow) {
                throw new BusinessException(429, "尝试过于频繁，请稍后重试。");
            }
            hits.addLast(now);
        }

        long lockCutoff = now - lockDuration.toMillis();
        Deque<Long> fails = FAILURES.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (fails) {
            while (!fails.isEmpty() && fails.peekFirst() < lockCutoff) {
                fails.pollFirst();
            }
            if (fails.size() >= lockThreshold) {
                fails.clear();
                LOCK_UNTIL.put(key, now + lockDuration.toMillis());
                HITS.remove(key);
                throw new BusinessException(429, "连续失败次数过多，已临时锁定，请稍后重试。");
            }
        }
    }

    public static void recordFailure(String key) {
        FAILURES.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()).addLast(System.currentTimeMillis());
    }

    public static void recordSuccess(String key) {
        FAILURES.remove(key);
    }

    /**
     * Probabilistic cleanup. Runs in amortised O(1) by walking the maps once
     * every CLEANUP_EVERY_NTH_CALL calls. The walk itself is O(N) but N is the
     * number of currently-tracked keys, not the lifetime number.
     */
    private static void maybeCleanup() {
        if (CALL_COUNTER.incrementAndGet() % CLEANUP_EVERY_NTH_CALL != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        // Any key whose newest entry is older than 15 minutes is safe to drop;
        // 15 minutes is the longest window the limiter is configured with.
        long stale = now - Duration.ofMinutes(15).toMillis();
        LOCK_UNTIL.entrySet().removeIf(e -> e.getValue() < now);
        HITS.entrySet().removeIf(entry -> isStale(entry.getValue(), stale));
        FAILURES.entrySet().removeIf(entry -> isStale(entry.getValue(), stale));
    }

    private static boolean isStale(Deque<Long> deque, long threshold) {
        synchronized (deque) {
            Long newest = deque.peekLast();
            return newest == null || newest < threshold;
        }
    }
}
