package com.example.resiliency;

import dev.failsafe.Failsafe;
import dev.failsafe.RateLimitExceededException;
import dev.failsafe.RateLimiter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Learning exercises for the Failsafe RateLimiter API.
 *
 * A RateLimiter caps how MANY executions may start per time period — unlike
 * a Bulkhead, which caps how many can be IN FLIGHT at once. RateLimiter is
 * about throughput over time; Bulkhead is about parallelism at an instant.
 *
 * Two flavors:
 * - smooth: permits are spaced evenly across the period (steady pace)
 * - bursty: all permits become available at the start of each period (spiky)
 *
 * RateLimitExceededException extends FailsafeException — thrown directly.
 *
 * WATCH THE LOG: "permit -> GRANTED/DENIED" lines show each decision; any
 * "supplier ran" line only appears AFTER a permit was handed out.
 */
class RateLimiterTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterTest.class);

    /**
     * Bursty limiter: all N permits are available the instant each period
     * begins; unused permits do NOT roll over.
     *
     * With 3 permits per 500ms:
     * - the first 3 tryAcquirePermit() calls succeed immediately
     * - the 4th fails — the burst is spent, next refill is next period
     * - after sleeping past the period, a new burst is available
     */
    @Test
    void burstyLimiterRefillsEachPeriod() throws Exception {
        RateLimiter<Object> limiter = RateLimiter.<Object>burstyBuilder(3, Duration.ofMillis(500))
                .build();
        assertTrue(limiter.isBursty());
        log.info("bursty limiter: 3 permits per 500ms period");

        // First burst: all 3 permits available at once
        for (int i = 1; i <= 3; i++) {
            assertTrue(limiter.tryAcquirePermit());
            log.info("permit #{}/3 -> GRANTED", i);
        }

        // Burst spent — nothing left until the next period
        assertFalse(limiter.tryAcquirePermit());
        log.warn("permit #4 -> DENIED (burst spent, waiting for next period)");

        // Next period begins -> permits refill
        Thread.sleep(600);
        assertTrue(limiter.tryAcquirePermit());
        log.info("after 600ms — new period, permit -> GRANTED");
    }

    /**
     * Smooth limiter: permits trickle out at a steady pace — with 3 permits
     * per 600ms, roughly one permit becomes available every 200ms.
     *
     * - 1st permit: available immediately
     * - 2nd attempt right away: refused (next permit isn't due yet)
     * - after ~1 permit-interval: available again
     *
     * Smooth pacing is what you want to avoid stampeding a downstream API;
     * bursty is what you want when a spike at period-start is acceptable.
     */
    @Test
    void smoothLimiterSpacesPermits() throws Exception {
        RateLimiter<Object> limiter = RateLimiter.<Object>smoothBuilder(3, Duration.ofMillis(600))
                .build();
        assertTrue(limiter.isSmooth());
        log.info("smooth limiter: 3 permits spread over 600ms (~1 every 200ms)");

        assertTrue(limiter.tryAcquirePermit());
        log.info("permit #1 -> GRANTED immediately");

        assertFalse(limiter.tryAcquirePermit());
        log.warn("permit #2 -> DENIED — next permit not due yet");

        Thread.sleep(250); // > one permit interval (~200ms)
        assertTrue(limiter.tryAcquirePermit());
        log.info("after 250ms — permit #2 -> GRANTED");
    }

    /**
     * Through the Failsafe executor: with no wait configured, an execution
     * that arrives when no permit is available fails with
     * RateLimitExceededException — same fail-fast shape as the bulkhead.
     *
     * Watch the log: call 1 runs its supplier; call 2 is rejected BEFORE the
     * supplier — the "never logged" line never prints.
     */
    @Test
    void rejectsThroughFailsafeWhenOutOfPermits() {
        RateLimiter<Object> limiter = RateLimiter.<Object>burstyBuilder(1, Duration.ofMinutes(1))
                .build(); // 1 permit per minute: guarantee the 2nd call is rejected

        assertEquals("first", Failsafe.with(limiter).get(() -> {
            log.info("call 1: permit granted, supplier running");
            return "first";
        }));

        AtomicInteger attempts = new AtomicInteger();
        assertThrows(RateLimitExceededException.class,
                () -> Failsafe.with(limiter).get(() -> {
                    attempts.incrementAndGet();
                    log.info("call 2 supplier running — you should NEVER see this");
                    return attempts.get();
                }));
        log.error("call 2 REJECTED — supplier invoked {} times", attempts.get());
        assertEquals(0, attempts.get()); // rejected before the supplier ran
    }

    /**
     * Waiting mode: withMaxWaitTime lets an execution park until its permit
     * is due (up to the deadline) instead of being rejected.
     *
     * Setup: 1 permit per 400ms, maxWaitTime 2s.
     * - Call A consumes the only permit of this period.
     * - Call B arrives with no permit due for ~400ms — it waits, then runs
     *   when the next permit rolls in.
     * - We prove B was parked by checking the future isn't done shortly after
     *   submission, then it completes after the wait.
     */
    @Test
    void waitsForPermitWithMaxWaitTime() throws Exception {
        RateLimiter<Object> limiter = RateLimiter.<Object>smoothBuilder(1, Duration.ofMillis(400))
                .withMaxWaitTime(Duration.ofSeconds(2))
                .build();
        log.info("smooth limiter: 1 permit per 400ms, callers may wait up to 2s");

        CountDownLatch firstDone = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            // Consume the first permit
            pool.submit(() -> {
                Failsafe.with(limiter).run(() -> log.info("call A: permit granted, running"));
                firstDone.countDown();
            });
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));

            // Next permit isn't due for ~400ms — this call waits for it
            var second = pool.submit(() -> Failsafe.with(limiter).get(() -> {
                log.info("call B: permit arrived, running now");
                return "permitted";
            }));
            Thread.sleep(100);
            assertFalse(second.isDone());
            log.warn("call B is PARKED — no permit due yet");

            assertEquals("permitted", second.get(5, TimeUnit.SECONDS));
            log.info("call B completed after waiting for its permit");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Wait deadline: if no permit arrives within maxWaitTime, the execution
     * is rejected with RateLimitExceededException — just like a bulkhead's
     * maxWaitTime deadline.
     */
    @Test
    void timesOutWaitingForPermit() {
        RateLimiter<Object> limiter = RateLimiter.<Object>burstyBuilder(1, Duration.ofMinutes(1))
                .withMaxWaitTime(Duration.ofMillis(100))
                .build();

        Failsafe.with(limiter).run(() -> log.info("call A: spent the only permit"));

        // Next permit is a whole minute away; deadline is 100ms -> rejected
        assertThrows(RateLimitExceededException.class,
                () -> Failsafe.with(limiter).get(() -> "never"));
        log.error("call B: waited 100ms, no permit came -> REJECTED");
    }

    /**
     * Multiple permits per execution: heavyweight operations can cost more
     * than one permit — e.g., a big batch counts double against the rate.
     *
     * With 4 permits per minute: acquiring 2 at once leaves 2, so a 3-permit
     * request is refused while a 2-permit request still fits.
     */
    @Test
    void multiplePermitsPerExecution() {
        RateLimiter<Object> limiter = RateLimiter.<Object>burstyBuilder(4, Duration.ofMinutes(1))
                .build();
        log.info("bursty limiter: budget of 4 permits per period");

        assertTrue(limiter.tryAcquirePermits(2));
        log.info("acquired 2 -> GRANTED (2 of 4 left)");

        assertFalse(limiter.tryAcquirePermits(3));
        log.warn("acquired 3 -> DENIED (needs 3, only 2 left)");

        assertTrue(limiter.tryAcquirePermits(2));
        log.info("acquired 2 -> GRANTED (0 left)");

        assertFalse(limiter.tryAcquirePermit());
        log.warn("acquired 1 -> DENIED (fully spent)");
    }
}
