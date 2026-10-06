package com.utils;

import com.service.BusinessException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded process-local limits: IP and account budgets are independent. */
public final class RateLimiter {
    private static final long MINUTE = 60_000;
    private static final long RETENTION = 15 * MINUTE;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final LongSupplier clock;
    private final int capacity;
    private long nextSweep;

    public RateLimiter() {
        this(System::currentTimeMillis, 10_000);
    }

    RateLimiter(LongSupplier clock, int capacity) {
        this.clock = clock;
        this.capacity = capacity;
    }

    public synchronized String check(String remoteAddress, String username, String operation) {
        long now = clock.getAsLong();
        if (now >= nextSweep) {
            buckets.entrySet()
                    .removeIf(e -> now - e.getValue().touched >= RETENTION && now >= e.getValue().lockedUntil);
            nextSweep = now + MINUTE;
        }
        hit("ip:" + remoteAddress, 60, now);
        String key = "account:" + operation + ":" + canonicalUsername(username);
        hit(key, ("/login".equals(operation) || "/password".equals(operation)) ? 5 : 20, now);
        return key;
    }

    private void hit(String key, int limit, long now) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= capacity) {
                evictIdlest(now);
            }
            bucket = new Bucket();
            buckets.put(key, bucket);
        }
        bucket.touched = now;
        if (now < bucket.lockedUntil) {
            throw new BusinessException(429, Messages.ERR_RATE_LIMIT_LOCKED);
        }
        prune(bucket.hits, now - MINUTE);
        if (bucket.hits.size() >= limit) {
            throw new BusinessException(429, Messages.ERR_RATE_LIMIT);
        }
        bucket.hits.addLast(now);
    }

    public synchronized void recordFailure(String key) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return;
        }
        long now = clock.getAsLong();
        prune(bucket.failures, now - RETENTION);
        bucket.touched = now;
        if (bucket.failures.size() < 10) {
            bucket.failures.addLast(now);
        }
        if (bucket.failures.size() >= 10) {
            bucket.lockedUntil = now + RETENTION;
            bucket.failures.clear();
        }
    }

    public synchronized void recordSuccess(String key) {
        Bucket bucket = buckets.get(key);
        if (bucket != null) {
            bucket.failures.clear();
        }
    }

    /**
     * At capacity, drop the least-recently-touched bucket instead of refusing the
     * new key. Refusing would turn a distributed flood of unknown usernames into a
     * denial of service for everyone else, since the flood's own buckets would keep
     * the map full until they aged out.
     *
     * <p>Buckets still serving a lockout are never evicted: discarding one would
     * hand an attacker a way to clear their own lockout by filling the map. When
     * every bucket is locked the map is genuinely saturated and the request is
     * refused.
     */
    private void evictIdlest(long now) {
        String idlest = null;
        long idlestTouched = Long.MAX_VALUE;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            Bucket candidate = entry.getValue();
            if (now < candidate.lockedUntil) {
                continue;
            }
            if (candidate.touched < idlestTouched) {
                idlestTouched = candidate.touched;
                idlest = entry.getKey();
            }
        }
        if (idlest == null) {
            throw new BusinessException(429, Messages.ERR_RATE_LIMIT);
        }
        buckets.remove(idlest);
    }

    private static void prune(ArrayDeque<Long> entries, long cutoff) {
        while (!entries.isEmpty() && entries.peekFirst() <= cutoff) {
            entries.removeFirst();
        }
    }

    static String canonicalUsername(String value) {
        String canonical = Validation.canonicalUsername(value);
        return canonical == null ? "<invalid>" : canonical;
    }

    synchronized int size() {
        return buckets.size();
    }

    private static final class Bucket {
        private final ArrayDeque<Long> hits = new ArrayDeque<>();
        private final ArrayDeque<Long> failures = new ArrayDeque<>();
        private long touched;
        private long lockedUntil;
    }
}
