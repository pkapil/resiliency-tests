package com.example.resiliency;

import dev.failsafe.CircuitBreaker;
import dev.failsafe.CircuitBreakerOpenException;
import dev.failsafe.Failsafe;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Learning exercises for the Failsafe CircuitBreaker API.
 *
 * A circuit breaker watches execution results and stops calling a failing
 * dependency so it can recover — and so callers fail fast instead of timing
 * out one by one.
 *
 * States:
 * - CLOSED:    normal. Executions flow through; failures are counted.
 * - OPEN:      failing. Executions are rejected instantly with
 *              CircuitBreakerOpenException for `delay` duration.
 * - HALF_OPEN: probing. After the delay, a limited number of trial
 *              executions are allowed through to test the waters.
 *
 * CircuitBreakerOpenException extends FailsafeException — thrown directly,
 * not wrapped (same shape as BulkheadFullException).
 *
 * WATCH THE LOG: "breaker OPEN/HALF_OPEN/CLOSED" lines are state transitions
 * reported by onOpen/onHalfOpen/onClose listeners; "supplier invoked" lines
 * mark an actual execution. When the breaker is OPEN you will see a rejection
 * with NO supplier invocation — the call died at the gate.
 */
class CircuitBreakerTest {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTest.class);

    /**
     * CLOSED -> OPEN: the failure threshold trips the breaker.
     *
     * withFailureThreshold(2) means: after 2 consecutive failures, open.
     * withDelay(10s) means: stay open for 10 seconds before half-opening.
     *
     * The test throws from the supplier twice through the breaker, then
     * asserts the breaker reports isOpen() — the circuit has tripped.
     */
    @Test
    void opensAfterFailureThreshold() {
        CircuitBreaker<Object> breaker = CircuitBreaker.builder()
                .withFailureThreshold(2)
                .withDelay(Duration.ofSeconds(10))
                .onOpen(e -> log.error(">>> breaker tripped OPEN"))
                .build();

        log.info("breaker starts {}", breaker.getState());
        assertTrue(breaker.isClosed());

        // Two failing executions trip the threshold
        for (int i = 1; i <= 2; i++) {
            int n = i;
            assertThrows(RuntimeException.class,
                    () -> Failsafe.with(breaker).get(() -> {
                        log.warn("supplier invoked (call {}) — throwing failure", n);
                        throw new RuntimeException("downstream is down #" + n);
                    }));
            log.info("failure {}/2 recorded", n);
        }

        log.info("breaker is now {}", breaker.getState());
        assertTrue(breaker.isOpen());
    }

    /**
     * OPEN behavior: fail fast. Once open, calls are rejected BEFORE the
     * supplier runs — `attempts` stays 0, proving the call never executed.
     * This is the "stop wasting resources on a dead dependency" behavior.
     *
     * Watch the log: the rejection is recorded, but there is NO
     * "supplier invoked" line for the second call.
     */
    @Test
    void openBreakerRejectsCallsFast() {
        CircuitBreaker<Object> breaker = CircuitBreaker.builder()
                .withFailureThreshold(1)
                .withDelay(Duration.ofMinutes(5)) // stays open for the whole test
                .onOpen(e -> log.error(">>> breaker tripped OPEN"))
                .build();

        assertThrows(RuntimeException.class,
                () -> Failsafe.with(breaker).get(() -> {
                    log.warn("supplier invoked — throwing failure to trip the breaker");
                    throw new RuntimeException("trip it");
                }));
        assertTrue(breaker.isOpen());

        AtomicInteger attempts = new AtomicInteger();
        assertThrows(CircuitBreakerOpenException.class,
                () -> Failsafe.with(breaker).get(() -> {
                    attempts.incrementAndGet();
                    log.info("supplier invoked — you should NEVER see this line");
                    return attempts.get();
                }));
        log.error("call rejected instantly — supplier ran {} times", attempts.get());
        assertEquals(0, attempts.get()); // rejected before the supplier ran
    }

    /**
     * OPEN -> HALF_OPEN -> CLOSED: after the delay expires, the next caller
     * becomes a trial execution. If it succeeds enough times
     * (withSuccessThreshold), the breaker closes and normal flow resumes.
     *
     * IMPORTANT — state transitions are LAZY: the breaker does not flip to
     * HALF_OPEN on a timer. It stays logically OPEN until the next permit
     * request arrives; that request is what triggers the transition. So right
     * after the delay elapses, isOpen() still reports true and isHalfOpen()
     * false — verified below.
     *
     * Timeline (each step logs):
     * 1. One failure opens the breaker (failure threshold = 1, delay = 150ms).
     * 2. Immediate call -> rejected (still inside the delay window).
     * 3. Sleep > 150ms -> delay elapsed, but state still reports OPEN.
     * 4. tryAcquirePermit() -> allowed: THIS call performs the lazy
     *    OPEN -> HALF_OPEN transition, and the permit acts as the probe.
     * 5. recordSuccess() meets the success threshold -> CLOSED.
     */
    @Test
    void halfOpenThenClosesOnSuccess() throws Exception {
        CircuitBreaker<Object> breaker = CircuitBreaker.builder()
                .withFailureThreshold(1)
                .withSuccessThreshold(1)
                .withDelay(Duration.ofMillis(150))
                .onOpen(e -> log.error(">>> OPEN"))
                .onHalfOpen(e -> log.warn(">>> HALF_OPEN — trial request admitted"))
                .onClose(e -> log.info(">>> CLOSED — recovered"))
                .build();

        // Trip it open
        assertThrows(RuntimeException.class,
                () -> Failsafe.with(breaker).get(() -> {
                    throw new RuntimeException("trip");
                }));
        log.info("state after failure: {}", breaker.getState());
        assertTrue(breaker.isOpen());

        // Still inside the delay: rejected
        assertThrows(CircuitBreakerOpenException.class,
                () -> Failsafe.with(breaker).get(() -> "nope"));
        log.warn("call inside delay window -> rejected, state={}", breaker.getState());

        // Delay has elapsed — but the state is still reported as OPEN because
        // the transition only happens when the next execution asks for a permit
        Thread.sleep(250);
        log.info("after sleeping past delay, state is STILL {} (lazy transition)",
                breaker.getState());
        assertTrue(breaker.isOpen());

        // Asking for a permit lazily transitions OPEN -> HALF_OPEN
        assertTrue(breaker.tryAcquirePermit());
        log.info("tryAcquirePermit() granted — state is now {}", breaker.getState());
        assertTrue(breaker.isHalfOpen());

        // Probe succeeds -> success threshold met -> breaker closes
        breaker.recordSuccess();
        log.info("probe succeeded — state is now {}", breaker.getState());
        assertTrue(breaker.isClosed());
    }

    /**
     * HALF_OPEN -> OPEN: a failed probe re-opens the breaker and the delay
     * starts over. The dependency isn't trusted until a probe succeeds.
     *
     * Note the same laziness: after the delay the breaker still reports OPEN;
     * the next call is permitted through as the half-open probe, and its
     * failure snaps the breaker straight back to OPEN.
     */
    @Test
    void failedProbeReopensBreaker() throws Exception {
        CircuitBreaker<Object> breaker = CircuitBreaker.builder()
                .withFailureThreshold(1)
                .withDelay(Duration.ofMillis(150))
                .onOpen(e -> log.error(">>> OPEN"))
                .onHalfOpen(e -> log.warn(">>> HALF_OPEN — admitting probe"))
                .build();

        // Trip it, then wait past the delay
        assertThrows(RuntimeException.class,
                () -> Failsafe.with(breaker).get(() -> {
                    throw new RuntimeException("trip");
                }));
        Thread.sleep(250);
        log.info("delay elapsed; reported state is still {} (lazy)", breaker.getState());
        assertTrue(breaker.isOpen()); // not half-open yet — transition is lazy

        // Next call is let through as the probe; it fails -> re-opens
        assertThrows(RuntimeException.class,
                () -> Failsafe.with(breaker).get(() -> {
                    log.warn("probe ran and FAILED — breaker should re-open");
                    throw new RuntimeException("still down");
                }));
        assertTrue(breaker.isOpen());
    }

    /**
     * Standalone usage: drive the breaker manually with tryAcquirePermit()
     * plus recordSuccess()/recordFailure(). Useful when results aren't
     * exceptions — e.g., you decide an HTTP 500 body counts as a failure.
     *
     * tryAcquirePermit() == "is this execution allowed right now?"
     * (false while OPEN). recordFailure() counts toward the threshold;
     * recordSuccess() counts toward closing when half-open.
     */
    @Test
    void standalonePermitAndRecord() {
        CircuitBreaker<Object> breaker = CircuitBreaker.builder()
                .withFailureThreshold(2)
                .withDelay(Duration.ofMinutes(1))
                .onOpen(e -> log.error(">>> OPEN after threshold"))
                .build();

        assertTrue(breaker.tryAcquirePermit());
        log.info("permit granted — recording failure 1/2");
        breaker.recordFailure();
        log.info("state after 1 failure: {} (still closed)", breaker.getState());
        assertTrue(breaker.isClosed());         // 1 of 2: still closed

        breaker.recordFailure();
        log.info("state after 2 failures: {}", breaker.getState());
        assertTrue(breaker.isOpen());           // 2 of 2: tripped

        assertFalse(breaker.tryAcquirePermit());
        log.warn("permit request -> DENIED (open)");
    }

    /**
     * Rate-based tripping: withFailureThreshold(failures, executions) opens
     * when N failures occur within the last M executions — catches flaky
     * (not fully dead) dependencies that never fail twice in a row.
     *
     * Here: open when 2 of the last 3 executions fail.
     * Sequence: fail, succeed, fail -> 2 failures in last 3 -> OPEN.
     */
    @Test
    void failureRateThreshold() {
        CircuitBreaker<Object> breaker = CircuitBreaker.builder()
                .withFailureThreshold(2, 3)
                .withDelay(Duration.ofMinutes(1))
                .onOpen(e -> log.error(">>> OPEN — 2 of last 3 calls failed"))
                .build();

        Runnable failingCall = () -> assertThrows(RuntimeException.class,
                () -> Failsafe.with(breaker).get(() -> {
                    log.warn("supplier invoked — intermittent failure");
                    throw new RuntimeException("intermittent");
                }));

        failingCall.run(); // failure 1
        log.info("after fail #1 — state={}", breaker.getState());

        String ok = Failsafe.with(breaker).get(() -> {
            log.info("supplier invoked — this one succeeds");
            return "ok";
        });
        assertEquals("ok", ok);
        log.info("after success — state={} (rate not met yet)", breaker.getState());
        assertTrue(breaker.isClosed());

        failingCall.run(); // failure 2 within window -> trips
        log.info("after fail #2 within window — state={}", breaker.getState());
        assertTrue(breaker.isOpen());
    }
}
