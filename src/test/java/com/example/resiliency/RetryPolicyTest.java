package com.example.resiliency;

import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetryPolicy tutorial for beginners — every test narrates itself in the logs.
 *
 * HOW RETRY WORKS (read this first):
 *
 *   Failsafe.with(retry).get(() -> callApi())
 *
 *   1. Failsafe runs your supplier — that's ATTEMPT 1.
 *   2. If it throws a HANDLED exception, the retry policy waits `delay`,
 *      then runs the supplier AGAIN — that's ATTEMPT 2 (i.e. "retry #1").
 *   3. This repeats until either the supplier succeeds or maxAttempts is
 *      reached. Whatever happened last is returned/thrown to you.
 *
 *   maxAttempts(3) == 1 initial try + up to 2 retries.
 *
 * WHAT TO WATCH IN THE LOGS:
 *   "REQUEST  #n ->"  = an actual call going out (one per attempt)
 *   "RESPONSE #n <-"  = what came back (a body on success, an error on failure)
 *   "attempt n FAILED" / "retry scheduled" / "SUCCESS" / "RETRIES EXCEEDED"
 *                     = the policy's own events, with timings
 *
 * Listener order per failed attempt:
 *   onFailedAttempt -> onRetryScheduled -> (sleep) -> next attempt
 *   on giving up  -> onRetriesExceeded -> onFailure
 *   on success    -> onSuccess
 */
class RetryPolicyTest {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicyTest.class);
    private static final String API_URL =
            "GET https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41";

    /**
     * Simulates calling a remote API so the log shows real-looking
     * REQUEST/RESPONSE lines. Fails with fake "HTTP 503" bodies for the
     * first two calls, then returns a JSON body — like a service recovering
     * from a transient outage.
     */
    private String callFlakyEndpoint(AtomicInteger calls) {
        int n = calls.incrementAndGet();
        log.info("REQUEST  #{} -> {}", n, API_URL);
        if (n < 3) {
            String errBody = "{\"error\":true,\"reason\":\"Service temporarily unavailable\"}";
            log.warn("RESPONSE #{} <- HTTP 503 {}", n, errBody);
            throw new RuntimeException("HTTP 503: " + errBody);
        }
        String body = "{\"current\":{\"temperature_2m\":9.2,\"wind_speed_10m\":12.5}}";
        log.info("RESPONSE #{} <- HTTP 200 {}", n, body);
        return body;
    }

    /**
     * Simulates an endpoint that NEVER recovers — every call gets a 503.
     * Used to demonstrate what happens after retries are exhausted.
     */
    private String callDeadEndpoint(AtomicInteger calls) {
        int n = calls.incrementAndGet();
        log.info("REQUEST  #{} -> {}", n, API_URL);
        String errBody = "{\"error\":true,\"reason\":\"Service temporarily unavailable\"}";
        log.warn("RESPONSE #{} <- HTTP 503 {}", n, errBody);
        throw new RuntimeException("HTTP 503: " + errBody);
    }

    // ---------------------------------------------------------------------
    // 1. RETRY ATTEMPTS
    // ---------------------------------------------------------------------

    /**
     * Attempt counter, made visible: the supplier fails on calls 1 and 2,
     * succeeds on call 3 — and the caller just gets the result.
     *
     * Expected log flow:
     *   REQUEST #1 -> RESPONSE #1 <- 503 -> attempt 1 FAILED -> retry in 50ms
     *   REQUEST #2 -> RESPONSE #2 <- 503 -> attempt 2 FAILED -> retry in 50ms
     *   REQUEST #3 -> RESPONSE #3 <- 200 -> SUCCESS on attempt 3
     *
     * Key idea: the CALLER never saw the two failures — retry absorbed them.
     * In real code this is how you survive brief outages and blips.
     */
    @Test
    void retryAttempts_flakyEndpointSucceeds() {
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .onFailedAttempt(e -> log.warn("attempt {} FAILED ({}ms): {}",
                        e.getAttemptCount(), e.getElapsedAttemptTime().toMillis(),
                        e.getLastException().getMessage()))
                .onRetryScheduled(e -> log.info("retry scheduled, next attempt in {}ms",
                        e.getDelay().toMillis()))
                .onSuccess(e -> log.info("SUCCESS on attempt {} ({}ms total)",
                        e.getAttemptCount(), e.getElapsedTime().toMillis()))
                .build();

        AtomicInteger calls = new AtomicInteger();
        String result = Failsafe.with(retry).get(() -> callFlakyEndpoint(calls));

        assertTrue(result.contains("\"temperature_2m\""));
        assertEquals(3, calls.get()); // the endpoint was hit 3 times
    }

    /**
     * Selective retrying: handle() restricts which failures retry.
     *
     * The policy retries IOException only. The supplier throws
     * IllegalStateException (think "HTTP 400 bad request" — retrying the
     * same malformed request is pointless). Watch the log: ONE request,
     * one failure, NO "retry scheduled" line — it propagates instantly.
     */
    @Test
    void retryAttempts_onlyHandledExceptions() {
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .handle(IOException.class)
                .withMaxAttempts(5) // room for retries — never used
                .onFailedAttempt(e -> log.warn("attempt {} failed with {}: retryable?",
                        e.getAttemptCount(), e.getLastException().getClass().getSimpleName()))
                .onRetryScheduled(e -> log.info("retry scheduled in {}ms", e.getDelay().toMillis()))
                .onFailure(e -> log.error("not retryable — propagating: {}",
                        e.getException().getClass().getSimpleName()))
                .build();

        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class,
                () -> Failsafe.with(retry).get(() -> {
                    int n = calls.incrementAndGet();
                    log.info("REQUEST  #{} -> {}", n, API_URL);
                    log.warn("RESPONSE #{} <- HTTP 400 {\"error\":true,\"reason\":\"latitude required\"}", n);
                    throw new IllegalStateException("HTTP 400 bad request");
                }));

        assertEquals(1, calls.get()); // hit once, never retried
    }

    /**
     * abortOn(): the inverse of handle() — "even though this matches the
     * retry conditions, do NOT retry it". The classic use: retry all runtime
     * errors EXCEPT the ones that signal a permanent problem.
     *
     * Watch the log: attempt 1 fails -> onAbort fires -> done.
     * maxAttempts(5) had 4 retries available; abortOn overrode them.
     */
    @Test
    void retryAttempts_abortOnPermanentError() {
        RetryPolicy<Object> retry = RetryPolicy.<Object>builder()
                .handle(RuntimeException.class)             // normally retried...
                .abortOn(IllegalArgumentException.class)    // ...except this one
                .withMaxAttempts(5)
                .onFailedAttempt(e -> log.warn("attempt {} failed: {}",
                        e.getAttemptCount(), e.getLastException().getMessage()))
                .onAbort(e -> log.error("ABORT — {} is permanent, retrying is pointless",
                        e.getException().getClass().getSimpleName()))
                .onRetryScheduled(e -> log.info("retry scheduled in {}ms", e.getDelay().toMillis()))
                .build();

        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class,
                () -> Failsafe.with(retry).get(() -> {
                    int n = calls.incrementAndGet();
                    log.info("REQUEST  #{} -> {}", n, API_URL);
                    log.warn("RESPONSE #{} <- HTTP 404 {\"error\":true}", n);
                    throw new IllegalArgumentException("HTTP 404 not found");
                }));

        assertEquals(1, calls.get());
    }

    /**
     * Listener hooks, counted: the same events we log are also measurable.
     * onRetry fires per retry that WILL happen; onFailedAttempt per failure;
     * onRetriesExceeded once when the policy gives up.
     *
     * With maxAttempts(3) and an always-failing supplier:
     *   failed attempts = 3, retries scheduled = 2, exceeded = 1.
     */
    @Test
    void retryAttempts_listenerCounts() {
        AtomicInteger retriesScheduled = new AtomicInteger();
        AtomicInteger failedAttempts = new AtomicInteger();
        AtomicInteger exceeded = new AtomicInteger();

        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(10))
                .onFailedAttempt(e -> {
                    failedAttempts.incrementAndGet();
                    log.warn("onFailedAttempt #{}: {}", e.getAttemptCount(),
                            e.getLastException().getMessage());
                })
                .onRetry(e -> {
                    retriesScheduled.incrementAndGet();
                    log.info("onRetry — attempt {} failed, another is coming",
                            e.getAttemptCount());
                })
                .onRetriesExceeded(e -> {
                    exceeded.incrementAndGet();
                    log.error("onRetriesExceeded — giving up");
                })
                .build();

        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeException.class,
                () -> Failsafe.with(retry).get(() -> callDeadEndpoint(calls)));

        assertEquals(2, retriesScheduled.get());
        assertEquals(3, failedAttempts.get());
        assertEquals(1, exceeded.get());
        assertEquals(3, calls.get());
    }

    // ---------------------------------------------------------------------
    // 2. AFTER EXHAUSTION
    // ---------------------------------------------------------------------

    /**
     * What the caller sees when every attempt fails: THE LAST EXCEPTION.
     * There is no wrapper like "RetriesExceededException" — the supplier's
     * own error propagates. (Observe the moment via onRetriesExceeded.)
     *
     * Expected log flow: 3 requests, 3 x 503, then:
     *   RETRIES EXCEEDED (3 attempts, ~30ms) -> FAILURE returned to caller.
     * The caller's assertThrows catches the supplier's RuntimeException.
     */
    @Test
    void exhaustion_lastExceptionPropagates() {
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(10))
                .onFailedAttempt(e -> log.warn("attempt {} failed: {}",
                        e.getAttemptCount(), e.getLastException().getMessage()))
                .onRetriesExceeded(e -> log.error("RETRIES EXCEEDED — {} attempts, {}ms total",
                        e.getAttemptCount(), e.getElapsedTime().toMillis()))
                .onFailure(e -> log.error("FAILURE returned to caller: {}",
                        e.getException().getMessage()))
                .build();

        AtomicInteger calls = new AtomicInteger();

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> Failsafe.with(retry).get(() -> callDeadEndpoint(calls)));

        assertTrue(e.getMessage().startsWith("HTTP 503")); // the supplier's own error
        assertEquals(3, calls.get());                      // 1 try + 2 retries
    }

    /**
     * withMaxDuration: a wall-clock cap that can cut retries short BEFORE
     * maxAttempts is reached — "keep retrying for up to N total, but never
     * longer than T milliseconds overall".
     *
     * maxAttempts=5 but maxDuration=120ms with 50ms delays: attempts run at
     * roughly t=0, t=50, t=100 — the 4th would start past the deadline so
     * the policy gives up early. Watch the log: fewer than 5 requests, then
     * RETRIES EXCEEDED — maxDuration won.
     */
    @Test
    void exhaustion_maxDurationCutsRetriesShort() {
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(5)                        // generous...
                .withMaxDuration(Duration.ofMillis(120))   // ...but capped by time
                .withDelay(Duration.ofMillis(50))
                .onFailedAttempt(e -> log.warn("attempt {} failed at {}ms elapsed",
                        e.getAttemptCount(), e.getElapsedTime().toMillis()))
                .onRetriesExceeded(e -> log.error("gave up — maxDuration {}ms hit",
                        e.getElapsedTime().toMillis()))
                .build();

        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeException.class,
                () -> Failsafe.with(retry).get(() -> callDeadEndpoint(calls)));

        // Deadline enforcement: fewer attempts than maxAttempts(5)
        assertTrue(calls.get() < 5, "expected <5 attempts, got " + calls.get());
    }

    // ---------------------------------------------------------------------
    // 3. EXPONENTIAL RETRY (BACKOFF)
    // ---------------------------------------------------------------------

    /**
     * Fixed delay vs exponential backoff.
     *
     * withDelay(50ms)   -> gaps of 50, 50, 50...      (metronome)
     * withBackoff(min,max) -> gaps of min, min*2, min*4... capped at max
     *
     * Why backoff: a struggling service needs breathing room to recover;
     * hammering it every 50ms makes things worse. Exponentially longer gaps
     * give it time while still retrying promptly at first.
     *
     * withBackoff(50ms, 400ms) produces delays 50 -> 100 -> 200 -> 400 (capped).
     * Watch the "sleeping Nms" lines double in the log — and the per-attempt
     * timestamps spread further apart each round.
     */
    @Test
    void exponentialBackoff_delaysDouble() {
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(4)
                .withBackoff(Duration.ofMillis(50), Duration.ofMillis(400)) // 50,100,200
                .onFailedAttempt(e -> log.warn("attempt {} failed at {}ms elapsed",
                        e.getAttemptCount(), e.getElapsedTime().toMillis()))
                .onRetryScheduled(e -> log.info("sleeping {}ms before next attempt",
                        e.getDelay().toMillis()))
                .onRetriesExceeded(e -> log.error("gave up after {}ms total",
                        e.getElapsedTime().toMillis()))
                .build();

        AtomicInteger calls = new AtomicInteger();
        long start = System.nanoTime();

        assertThrows(RuntimeException.class,
                () -> Failsafe.with(retry).get(() -> {
                    int n = calls.incrementAndGet();
                    log.info("REQUEST  #{} -> {} (t={}ms)",
                            n, API_URL, (System.nanoTime() - start) / 1_000_000);
                    throw new RuntimeException("HTTP 503");
                }));

        assertEquals(4, calls.get());
        // Total waits: 50 + 100 + 200 = 350ms minimum between the 4 attempts
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 340, "expected ~350ms of backoff, was " + elapsedMs + "ms");
    }

    /**
     * Backoff in the happy path: transient failures recover mid-backoff —
     * the log shows the growing waits AND the eventual success, which is the
     * whole point of backing off instead of giving up instantly.
     *
     * Backoff 50->400ms, supplier fails twice then succeeds: waits are
     * ~50ms then ~100ms before attempt 3 lands the 200 response.
     */
    @Test
    void exponentialBackoff_flakyEndpointRecovers() {
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(5)
                .withBackoff(Duration.ofMillis(50), Duration.ofMillis(400))
                .onFailedAttempt(e -> log.warn("attempt {} FAILED: {}",
                        e.getAttemptCount(), e.getLastException().getMessage()))
                .onRetryScheduled(e -> log.info("backing off {}ms", e.getDelay().toMillis()))
                .onSuccess(e -> log.info("RECOVERED on attempt {} ({}ms total)",
                        e.getAttemptCount(), e.getElapsedTime().toMillis()))
                .build();

        AtomicInteger calls = new AtomicInteger();
        String result = Failsafe.with(retry).get(() -> callFlakyEndpoint(calls));

        assertTrue(result.contains("\"temperature_2m\""));
        assertEquals(3, calls.get());
    }

    /**
     * Jitter: randomness on top of backoff. Why it exists — if a service
     * goes down and 1000 clients all retry with identical backoff, they
     * stampede back in lockstep every interval ("thundering herd").
     * withJitter() randomizes each delay so the herd spreads out.
     *
     * Watch the log: the scheduled delays hover around the backoff values
     * but are NOT the tidy 50/100/200 — each is randomly adjusted.
     */
    @Test
    void exponentialBackoff_withJitter() {
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(4)
                .withBackoff(Duration.ofMillis(50), Duration.ofMillis(400))
                .withJitter(0.5) // +/-50% randomization on each delay
                .onRetryScheduled(e -> log.info("jittered wait: {}ms", e.getDelay().toMillis()))
                .onRetriesExceeded(e -> log.error("gave up after {}ms",
                        e.getElapsedTime().toMillis()))
                .build();

        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeException.class,
                () -> Failsafe.with(retry).get(() -> callDeadEndpoint(calls)));

        assertEquals(4, calls.get());
    }

    // ---------------------------------------------------------------------
    // 4. REAL REQUEST / RESPONSE BODY
    // ---------------------------------------------------------------------

    /**
     * A REAL call to Open-Meteo with request and response body printed.
     *
     * The supplier logs the outgoing request, then the response body
     * (truncated so logs stay readable). On a healthy API you get exactly
     * one request and one HTTP 200 body — the retry policy stays invisible,
     * which is precisely what "healthy path" should look like.
     *
     * To see retries for real, point WeatherService at an unreachable URL
     * or flip the IOException handle in a copy of this test — every
     * REQUEST/RESPONSE pair will repeat per attempt.
     */
    @Test
    void realApiCall_requestAndResponseLogged() {
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .handle(IOException.class)
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(200))
                .onFailedAttempt(e -> log.warn("attempt {} failed: {}",
                        e.getAttemptCount(), e.getLastException()))
                .onRetryScheduled(e -> log.info("retrying in {}ms", e.getDelay().toMillis()))
                .onSuccess(e -> log.info("SUCCESS on attempt {}", e.getAttemptCount()))
                .build();

        WeatherService service = new WeatherService(dev.failsafe.Bulkhead.of(2));
        AtomicInteger calls = new AtomicInteger();

        String body = Failsafe.with(retry).get(() -> {
            int n = calls.incrementAndGet();
            log.info("REQUEST  #{} -> {}", n, API_URL);
            String response = service.fetchForecast();
            // Print the actual response body, trimmed for the console
            String preview = response.length() > 200
                    ? response.substring(0, 200) + "...[truncated]"
                    : response;
            log.info("RESPONSE #{} <- HTTP 200 {}", n, preview);
            return response;
        });

        assertTrue(body.contains("\"current\""));
        assertEquals(1, calls.get()); // healthy API: exactly one request
    }
}
