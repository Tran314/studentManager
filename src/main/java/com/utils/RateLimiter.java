package com.utils;

import com.service.BusinessException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
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
                throw new BusinessException(429, Messages.ERR_RATE_LIMIT);
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

    private static void prune(ArrayDeque<Long> entries, long cutoff) {
        while (!entries.isEmpty() && entries.peekFirst() <= cutoff) {
            entries.removeFirst();
        }
    }

    static String canonicalUsername(String value) {
        if (value == null || value.length() > Limits.USERNAME_MAX * 2) {
            return "<invalid>";
        }
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
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
