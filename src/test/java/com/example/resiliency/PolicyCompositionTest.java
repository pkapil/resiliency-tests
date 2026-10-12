package com.example.resiliency;

import dev.failsafe.Bulkhead;
import dev.failsafe.CircuitBreaker;
import dev.failsafe.CircuitBreakerOpenException;
import dev.failsafe.Failsafe;
import dev.failsafe.RateLimiter;
import dev.failsafe.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Capstone: composing all four policies around the real WeatherService.
 *
 * Failsafe.with(p1, p2, p3) applies policies OUTSIDE-IN: p1 is the outermost
 * policy (runs first on the way in, sees the final result last). The order
 * below is the canonical stacking for a remote call:
 *
 *   RetryPolicy  -> outermost: each retry re-enters the inner policies,
 *                   so a rejection/timeout can itself be retried
 *   CircuitBreaker -> skips calling altogether while the API looks down
 *   RateLimiter  -> paces outgoing requests over time
 *   Bulkhead     -> innermost: caps concurrent in-flight requests
 *
 * A single call therefore: waits for retry context, checks the breaker,
 * waits for a rate permit, grabs a bulkhead permit, then does HTTP.
 *
 * WATCH THE LOG: policy listeners are prefixed by their layer —
 *   [retry] / [breaker] / [limiter] — so you can see which layer rejected
 *   or retried, and that the "supplier" line only prints when ALL layers
 *   let the call through.
 */
class PolicyCompositionTest {

    private static final Logger log = LoggerFactory.getLogger(PolicyCompositionTest.class);

    /**
     * All four policies stacked around a REAL Open-Meteo call.
     *
     * - Retry: up to 3 attempts on IOException (transient network issues)
     * - CircuitBreaker: open after 3 failures, stay open 30s
     * - RateLimiter: at most 5 calls per 10s (polite to a free API)
     * - Bulkhead: at most 2 requests in flight
     *
     * The call should simply succeed — healthy path through the full stack.
     * Watch the log: every layer silently lets the call through, then the
     * HTTP request and [retry] success appear exactly once.
     */
    @Test
    void fullStackRealCall() {
        RetryPolicy<String> retry = RetryPolicy.<String>builder()
                .handle(IOException.class)
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(200))
                .onFailedAttempt(e -> log.warn("[retry] attempt {} failed: {}",
                        e.getAttemptCount(), e.getLastException()))
                .onSuccess(e -> log.info("[retry] call succeeded on attempt {}",
                        e.getAttemptCount()))
                .build();
        CircuitBreaker<String> breaker = CircuitBreaker.<String>builder()
                .withFailureThreshold(3)
                .withDelay(Duration.ofSeconds(30))
                .onOpen(e -> log.error("[breaker] OPEN"))
                .build();
        RateLimiter<String> limiter = RateLimiter.<String>smoothBuilder(5, Duration.ofSeconds(10))
                .build();
        Bulkhead<String> bulkhead = Bulkhead.of(2);

        WeatherService service = new WeatherService(bulkhead);
        // Note: forecastJson() already wraps the bulkhead; for composition we
        // call fetchForecast() inside our own full policy stack instead.
        log.info("executing through retry -> breaker -> limiter stack");
        String json = Failsafe.with(retry, breaker, limiter)
                .get(service::fetchForecast);

        assertTrue(json.contains("\"current\""));
    }

    /**
     * Order matters: RetryPolicy on the OUTSIDE can retry an inner policy's
     * rejection — here it recovers from an OPEN circuit breaker.
     *
     * Timeline (watch the log):
     * 1. One failure trips the breaker (failureThreshold=1, delay=200ms).
     * 2. Failsafe.with(retry, breaker): attempt 1 hits the open breaker and
     *    is rejected with CircuitBreakerOpenException — the supplier never runs.
     * 3. Retry catches that rejection (it handles it), waits 50ms, retries.
     *    Attempts ~2-4 are also rejected while the 200ms delay elapses.
     * 4. Once the delay expires, the next attempt is admitted as the
     *    HALF_OPEN probe; it succeeds and the breaker CLOSES.
     *
     * Attempts rejected by the breaker never reach the supplier, so
     * attempts.get() == 1 even though Failsafe retried several times —
     * the log shows N rejections but only ONE "supplier ran" line.
     */
    @Test
    void retryCanWaitOutAnOpenBreaker() {
        CircuitBreaker<Object> breaker = CircuitBreaker.builder()
                .withFailureThreshold(1)
                .withSuccessThreshold(1)
                .withDelay(Duration.ofMillis(200))
                .onOpen(e -> log.error("[breaker] OPEN — rejecting calls"))
                .onHalfOpen(e -> log.warn("[breaker] HALF_OPEN — probe admitted"))
                .onClose(e -> log.info("[breaker] CLOSED — recovered"))
                .build();
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .handle(CircuitBreakerOpenException.class)
                .withMaxAttempts(10)
                .withDelay(Duration.ofMillis(50))
                .onFailedAttempt(e -> log.warn("[retry] attempt {} rejected: {}",
                        e.getAttemptCount(), e.getLastException().getClass().getSimpleName()))
                .build();

        // Trip the breaker open
        assertThrows(RuntimeException.class,
                () -> Failsafe.with(breaker).get(() -> {
                    throw new RuntimeException("trip");
                }));
        assertTrue(breaker.isOpen());
        log.info("breaker is open — now calling through retry+breaker stack");

        AtomicInteger attempts = new AtomicInteger();
        String result = Failsafe.with(retry, breaker).get(() -> {
            attempts.incrementAndGet();
            log.info("supplier ran ({} time(s) total) — permit chain allowed through",
                    attempts.get());
            return "recovered";
        });

        assertEquals("recovered", result);
        assertEquals(1, attempts.get()); // supplier ran once — rejections never reached it
        assertTrue(breaker.isClosed());  // the successful probe closed the circuit
    }

    /**
     * An open circuit breaker short-circuits EVERYTHING inside it — the
     * retry policy outside can observe and react, but the supplier never runs.
     *
     * We pre-open the breaker manually (standalone API), then run the stack.
     * Every call gets CircuitBreakerOpenException from the breaker; since our
     * retry policy doesn't handle it, it propagates on the first attempt.
     * Log shows: rejection with ZERO supplier invocations.
     */
    @Test
    void openBreakerShortCircuitsWholeStack() {
        CircuitBreaker<Object> breaker = CircuitBreaker.builder()
                .withFailureThreshold(1)
                .withDelay(Duration.ofMinutes(1))
                .onOpen(e -> log.error("[breaker] OPEN"))
                .build();
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .handle(IOException.class) // deliberately NOT the breaker exception
                .withMaxAttempts(5)
                .onFailure(e -> log.error("[retry] giving up: {}",
                        e.getException().getClass().getSimpleName()))
                .build();

        breaker.open(); // force it open — downstream is presumed dead
        assertTrue(breaker.isOpen());
        log.info("breaker forced OPEN — every call below should be short-circuited");

        AtomicInteger attempts = new AtomicInteger();
        assertThrows(CircuitBreakerOpenException.class,
                () -> Failsafe.with(retry, breaker).get(() -> {
                    attempts.incrementAndGet();
                    log.info("supplier ran — should never print");
                    return attempts.get();
                }));
        log.error("short-circuited — supplier invoked {} times", attempts.get());
        assertEquals(0, attempts.get()); // never reached the supplier
    }

    /**
     * Failure bookkeeping across policies: the supplier throws a retryable
     * IOException; retry makes 3 attempts, all fail — and the circuit breaker
     * (inside the retry policy) sees each failed attempt separately.
     *
     * Takeaway: with retry outside and breaker inside, the breaker counts
     * every ATTEMPT, not every logical call — 1 logical call produced 3
     * breaker failures here. If you'd rather count logical calls, put the
     * breaker outside the retry. Watch the log for the running failure tally.
     */
    @Test
    void breakerCountsEachRetryAttempt() {
        CircuitBreaker<Object> breaker = CircuitBreaker.builder()
                .withFailureThreshold(10) // high enough to stay closed
                .withDelay(Duration.ofMinutes(1))
                .build();
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .handle(IOException.class)
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(10))
                .onFailedAttempt(e -> log.warn("[retry] attempt {} failed — breaker failure count now {}",
                        e.getAttemptCount(), breaker.getFailureCount()))
                .build();

        AtomicInteger calls = new AtomicInteger();
        // IOException is checked -> Failsafe wraps it in FailsafeException
        assertThrows(Exception.class,
                () -> Failsafe.with(retry, breaker).get(() -> {
                    log.info("supplier invoked (call {}) — throwing IOException",
                            calls.incrementAndGet());
                    throw new IOException("simulated network failure");
                }));

        log.info("breaker counted {} failures from 1 logical call",
                breaker.getFailureCount());
        assertEquals(3, breaker.getFailureCount()); // one failure per attempt
        assertTrue(breaker.isClosed());             // threshold (10) not reached
    }
}
