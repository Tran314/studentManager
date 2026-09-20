package com.utils;

import static org.junit.jupiter.api.Assertions.*;

import com.service.BusinessException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    @Test
    void accountBudgetSurvivesIpAndCaseChanges() {
        RateLimiter limiter = new RateLimiter();
        for (int i = 0; i < 5; i++) {
            limiter.check("ip" + i, " ＡdMÍn ", "/login");
        }
        assertEquals(
                429,
                assertThrows(BusinessException.class, () -> limiter.check("other", "admin", "/login"))
                        .getStatus());
    }

    @Test
    void ipBudgetSurvivesRotatingAccounts() {
        RateLimiter limiter = new RateLimiter();
        for (int i = 0; i < 60; i++) {
            limiter.check("same", "user" + i, "/login");
        }
        assertThrows(BusinessException.class, () -> limiter.check("same", "next", "/login"));
    }

    @Test
    void capacityFailsClosedAndExpires() {
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(now::get, 4);
        limiter.check("one", "a", "/login");
        limiter.check("two", "b", "/login");
        assertThrows(BusinessException.class, () -> limiter.check("three", "c", "/login"));
        assertEquals(4, limiter.size());
        now.set(900_001);
        assertNotNull(limiter.check("three", "c", "/login"));
        assertEquals(2, limiter.size());
    }

    @Test
    void failuresLockAndSuccessClearsHistory() {
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(now::get, 20);
        for (int i = 0; i < 10; i++) {
            now.addAndGet(61_000);
            limiter.recordFailure(limiter.check("ip", "a", "/login"));
        }
        now.addAndGet(61_000);
        assertThrows(BusinessException.class, () -> limiter.check("another", "a", "/login"));
        now.addAndGet(900_001);
        String key = limiter.check("ip", "a", "/login");
        limiter.recordFailure(key);
        limiter.recordSuccess(key);
        limiter.recordSuccess("missing");
        limiter.recordFailure("missing");
        now.addAndGet(61_000);
        assertNotNull(limiter.check("ip", "a", "/login"));
        assertEquals("<invalid>", RateLimiter.canonicalUsername(null));
        assertEquals("<invalid>", RateLimiter.canonicalUsername("a".repeat(200)));
    }
}
