package com.example.resiliency;

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
 * Observability: watching what Failsafe actually does via its event listeners.
 *
 * Failsafe itself is zero-dependency and logs NOTHING. All visibility comes
 * from listeners you attach to the policy builders. Every listener receives
 * an event object carrying:
 *
 *   e.getAttemptCount()       which attempt just finished (1 = first try)
 *   e.getExecutionCount()     which logical execution this is
 *   e.getElapsedTime()        total time since the execution started
 *   e.getElapsedAttemptTime() time spent in just the last attempt
 *   e.getLastException()      the failure that triggered this event
 *   e.getLastResult()         the last result value (if any)
 *   e.isFirstAttempt()/isRetry()
 *
 * Run this class and watch the console — each test narrates its own flow.
 *
 * Retry listener ordering per failed attempt:
 *   onFailedAttempt -> onRetry (a retry will happen) -> onRetryScheduled (delay picked)
 * On giving up:                    onRetriesExceeded -> onFailure
 * On success:                                                onSuccess
 */
class ObservabilityLoggingTest {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityLoggingTest.class);

    /**
     * A flaky call that fails twice then succeeds — every attempt logged.
     *
     * Watch the output: you'll see attempt 1 fail, a 50ms delay scheduled,
     * attempt 2 fail, another delay, attempt 3 succeed — plus the total
     * elapsed time. The supplier itself logs its invocation so you can
     * correlate "endpoint calls" with policy events.
     */
    @Test
    void retryAttemptsAreFullyVisible() {
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .onFailedAttempt(e -> log.warn("attempt {} FAILED after {}ms — {}",
                        e.getAttemptCount(), e.getElapsedAttemptTime().toMillis(),
                        e.getLastException().toString()))
                .onRetryScheduled(e -> log.info("retry #{} scheduled in {}ms",
                        e.getAttemptCount(), e.getDelay().toMillis()))
                .onRetriesExceeded(e -> log.error("gave up after {} attempts",
                        e.getAttemptCount()))
                .onSuccess(e -> log.info("succeeded on attempt {} in {}ms total",
                        e.getAttemptCount(), e.getElapsedTime().toMillis()))
                .build();

        AtomicInteger endpointCalls = new AtomicInteger();

        String result = Failsafe.with(retry).get(() -> {
            int call = endpointCalls.incrementAndGet();
            log.info(">>> calling endpoint (call #{})", call);
            if (call < 3) {
                throw new RuntimeException("HTTP 503 from endpoint");
            }
            return "200 OK";
        });

        assertEquals("200 OK", result);
        assertEquals(3, endpointCalls.get()); // 3 real "calls", all logged
    }

    /**
     * Giving up: when all attempts fail, the listener sequence shows the
     * exact moment Failsafe decides to stop — onRetriesExceeded fires after
     * the last failure, then onFailure reports the final outcome.
     */
    @Test
    void exhaustedRetriesLoggedEndToEnd() {
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(10))
                .onFailedAttempt(e -> log.warn("attempt {} failed: {}",
                        e.getAttemptCount(), e.getLastException().getMessage()))
                .onRetriesExceeded(e -> log.error("RETRIES EXCEEDED — {} attempts, {}ms total",
                        e.getAttemptCount(), e.getElapsedTime().toMillis()))
                .onFailure(e -> log.error("final failure: {}", e.getException().getMessage()))
                .build();

        AtomicInteger endpointCalls = new AtomicInteger();

        assertThrows(RuntimeException.class,
                () -> Failsafe.with(retry).get(() -> {
                    endpointCalls.incrementAndGet();
                    log.info(">>> calling endpoint");
                    throw new RuntimeException("connection refused");
                }));

        assertEquals(3, endpointCalls.get());
    }

    /**
     * Circuit breaker state changes are events too: onOpen / onHalfOpen /
     * onClose fire on transitions, and onFailure fires per rejected call.
     *
     * Watch: failure trips the breaker (OPEN logged), the next call is
     * rejected instantly (note: ZERO attempts — the supplier never runs),
     * the delay lapses, a probe goes through, and onClose reports CLOSED.
     */
    @Test
    void breakerTransitionsAreLogged() throws Exception {
        CircuitBreaker<Object> breaker = CircuitBreaker.builder()
                .withFailureThreshold(1)
                .withSuccessThreshold(1)
                .withDelay(Duration.ofMillis(150))
                .onOpen(e -> log.error("breaker OPEN — calls will now fail fast"))
                .onHalfOpen(e -> log.warn("breaker HALF_OPEN — admitting trial request"))
                .onClose(e -> log.info("breaker CLOSED — normal flow resumed"))
                .onFailure(e -> log.warn("rejected by breaker: {}",
                        e.getException().toString()))
                .build();

        // Trip it
        assertThrows(RuntimeException.class,
                () -> Failsafe.with(breaker).get(() -> {
                    throw new RuntimeException("endpoint down");
                }));

        // Rejected fast — zero endpoint calls
        assertThrows(CircuitBreakerOpenException.class,
                () -> Failsafe.with(breaker).get(() -> "unreachable"));

        // Delay elapses -> next call is the half-open probe -> success closes
        Thread.sleep(250);
        assertEquals("back online", Failsafe.with(breaker).get(() -> "back online"));
        assertTrue(breaker.isClosed());
    }

    /**
     * Rate limiter rejections are visible via onFailure — and note the
     * attempt count stays 1: rejection happens BEFORE any attempt runs.
     */
    @Test
    void rateLimitRejectionsLogged() {
        RateLimiter<Object> limiter = RateLimiter.<Object>burstyBuilder(1, Duration.ofMinutes(1))
                .build();
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(1) // don't retry — just observe
                .onFailure(e -> log.error("call #{} failed: {}",
                        e.getExecutionCount(), e.getException()))
                .build();

        Failsafe.with(retry, limiter).run(() -> log.info(">>> call 1 through"));

        assertThrows(Exception.class,
                () -> Failsafe.with(retry, limiter).run(() -> log.info("never logged")));
    }

    /**
     * The real thing: a genuine Open-Meteo call wrapped in an instrumented
     * retry + breaker stack. On success you see exactly one endpoint call —
     * proof the policies are transparent when everything is healthy.
     */
    @Test
    void realApiCallWithFullInstrumentation() {
        RetryPolicy<String> retry = RetryPolicy.<String>builder()
                .handle(IOException.class)
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(200))
                .onFailedAttempt(e -> log.warn("attempt {} failed: {}",
                        e.getAttemptCount(), e.getLastException()))
                .onSuccess(e -> log.info("SUCCESS — attempts={}, elapsed={}ms",
                        e.getAttemptCount(), e.getElapsedTime().toMillis()))
                .onFailure(e -> log.error("FAILED — {}", e.getException()))
                .build();
        CircuitBreaker<String> breaker = CircuitBreaker.<String>builder()
                .withFailureThreshold(3)
                .withDelay(Duration.ofSeconds(30))
                .onOpen(e -> log.error("breaker OPEN"))
                .onClose(e -> log.info("breaker CLOSED"))
                .build();

        WeatherService service = new WeatherService(dev.failsafe.Bulkhead.of(2));
        AtomicInteger realCalls = new AtomicInteger();

        String json = Failsafe.with(retry, breaker).get(() -> {
            realCalls.incrementAndGet();
            log.info(">>> real HTTP call #{}", realCalls.get());
            return service.fetchForecast();
        });

        assertTrue(json.contains("\"current\""));
        assertEquals(1, realCalls.get());
    }
}
