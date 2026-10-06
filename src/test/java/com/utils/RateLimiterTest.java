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
            limiter.check("ip" + i, " AdMiN ", "/login");
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
    void capacityEvictsIdlestKeyInsteadOfRefusingNewOnes() {
        AtomicLong now = new AtomicLong(1);
        RateLimiter limiter = new RateLimiter(now::get, 4);
        limiter.check("one", "a", "/login");
        now.addAndGet(1_000);
        limiter.check("two", "b", "/login");
        assertEquals(4, limiter.size());
        now.addAndGet(1_000);
        // At capacity a third peer must still get through: the two idlest buckets
        // are dropped rather than the request being refused.
        assertNotNull(limiter.check("three", "c", "/login"));
        assertEquals(4, limiter.size());
        // Once everything has aged past retention the periodic sweep reclaims it.
        now.addAndGet(900_001);
        assertNotNull(limiter.check("four", "d", "/login"));
        assertEquals(2, limiter.size());
    }

    @Test
    void evictionNeverClearsAnActiveLockout() {
        AtomicLong now = new AtomicLong(1);
        RateLimiter limiter = new RateLimiter(now::get, 6);
        for (int i = 0; i < 10; i++) {
            now.addAndGet(61_000);
            limiter.recordFailure(limiter.check("attacker", "victim", "/login"));
        }
        // Fill the map far past capacity from unrelated peers. The locked bucket
        // must survive, otherwise filling the map would clear the lockout.
        for (int i = 0; i < 20; i++) {
            now.addAndGet(1_000);
            limiter.check("peer" + i, "user" + i, "/login");
        }
        now.addAndGet(1_000);
        assertEquals(
                429,
                assertThrows(BusinessException.class, () -> limiter.check("elsewhere", "victim", "/login"))
                        .getStatus());
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
