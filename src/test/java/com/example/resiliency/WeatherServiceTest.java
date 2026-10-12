package com.example.resiliency;

import dev.failsafe.Bulkhead;
import dev.failsafe.BulkheadFullException;
import dev.failsafe.Failsafe;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bulkhead applied to real Open-Meteo API calls.
 *
 * The idea: a Bulkhead is shared across every call site that hits the same
 * downstream resource. No matter how many threads call the API at once, at
 * most "maxConcurrency" HTTP requests can be in flight at any moment.
 *
 * WATCH THE LOG:
 *   "HTTP ->" / "HTTP <-" lines come from inside WeatherService — each one
 *   is a real network call. "permit" lines are the bulkhead's bookkeeping.
 *   You should never see more than `permits` in-flight HTTP calls at once.
 */
class WeatherServiceTest {

    private static final Logger log = LoggerFactory.getLogger(WeatherServiceTest.class);

    /**
     * Happy path: a normal call through a bulkhead that has free permits.
     *
     * Steps:
     * 1. Create a bulkhead allowing 3 concurrent executions.
     * 2. Inject it into WeatherService — every forecastJson() call must
     *    acquire a permit before the HTTP request is sent.
     * 3. The bulkhead is empty, so the permit is granted instantly and the
     *    real HTTPS call to api.open-meteo.com executes.
     * 4. Assert the JSON payload actually contains the fields we asked for.
     */
    @Test
    void callsApiThroughBulkhead() {
        WeatherService service = new WeatherService(Bulkhead.of(3));

        log.info("calling forecastJson() — bulkhead is empty, permit should be granted");
        String json = service.forecastJson();
        log.info("got response ({} bytes), first 120 chars: {}",
                json.length(), json.substring(0, Math.min(120, json.length())));

        assertTrue(json.contains("\"temperature_2m\""));
        assertTrue(json.contains("\"wind_speed_10m\""));
    }

    /**
     * Rejection path: what happens when every permit is already taken.
     *
     * Bulkhead.of(1) creates a zero-wait bulkhead with a single permit.
     * We deliberately grab that permit ourselves (standalone usage of the
     * same bulkhead the service uses), so the bulkhead is saturated before
     * the service is even called.
     *
     * When service.forecastJson() runs, Failsafe asks the bulkhead for a
     * permit, gets refused, and throws BulkheadFullException immediately —
     * the HTTP request is never sent. Note the log: no "HTTP ->" line,
     * proving the call died at the gate.
     */
    @Test
    void saturatedBulkheadRejectsCall() {
        Bulkhead<String> bulkhead = Bulkhead.of(1);
        WeatherService service = new WeatherService(bulkhead);

        // Occupy the only permit without going through the service
        assertTrue(bulkhead.tryAcquirePermit());
        log.info("permit held externally — bulkhead is now full (0 free)");

        // The next call is rejected instantly — never reaches the network
        assertThrows(BulkheadFullException.class, () -> {
            try {
                service.forecastJson();
            } catch (BulkheadFullException e) {
                log.error("REJECTED by bulkhead — request never hit the network: {}", e.toString());
                throw e;
            }
        });
    }

    /**
     * Documents a quirk discovered in Failsafe 3.3.2 — read carefully.
     *
     * Internally, a sync Failsafe.with(bulkhead) call acquires its permit by
     * enqueueing a "waiter" future in the bulkhead's wait queue, then waiting
     * up to maxWaitTime for it to complete. When the bulkhead is full and the
     * wait is zero, the wait times out instantly — but the dead waiter entry
     * is NOT removed from the queue.
     *
     * Consequence: the next releasePermit() hands the freed permit to that
     * stale waiter instead of returning it to the pool. The permit
     * effectively evaporates — the bulkhead still reports full.
     *
     * Timeline below (each step logs):
     * 1. tryAcquirePermit() -> true   (0 permits left)
     * 2. Failsafe call -> rejected    (leaves 1 stale waiter queued)
     * 3. releasePermit()              (permit goes to the dead waiter -> lost)
     * 4. tryAcquirePermit() -> false  (bulkhead still looks full!)
     * 5. releasePermit() again        (queue now empty, permit actually freed)
     * 6. tryAcquirePermit() -> true
     *
     * Lesson: don't mix standalone permit juggling with Failsafe-mediated
     * calls on the same bulkhead — the accounting can drift.
     */
    @Test
    void rejectedSyncCallLeavesStaleWaiter() {
        Bulkhead<Object> bulkhead = Bulkhead.of(1);
        assertTrue(bulkhead.tryAcquirePermit());
        log.info("step 1: manually acquired the only permit — bulkhead full");

        assertThrows(BulkheadFullException.class,
                () -> Failsafe.with(bulkhead).get(() -> "x"));
        log.warn("step 2: Failsafe call rejected — a stale waiter is now stuck in the queue");

        bulkhead.releasePermit();
        log.info("step 3: released — but the permit was absorbed by the stale waiter!");
        assertFalse(bulkhead.tryAcquirePermit());
        log.warn("step 4: tryAcquirePermit() -> false — bulkhead STILL looks full");

        bulkhead.releasePermit();
        log.info("step 5: released again — now the permit actually returns");
        assertTrue(bulkhead.tryAcquirePermit());
        log.info("step 6: tryAcquirePermit() -> true — bulkhead finally free");
    }

    /**
     * The core promise of the pattern: a hard ceiling on concurrency,
     * verified against real network calls.
     *
     * Setup:
     * - Bulkhead with 2 permits and a generous maxWaitTime (30s), so excess
     *   callers WAIT for a permit instead of being rejected.
     * - 8 threads each submit one API call wrapped in Failsafe.with(bulkhead).
     *
     * Instrumentation:
     * - `inFlight` counts how many HTTP requests are executing right now.
     *   It is incremented INSIDE the Failsafe supplier — i.e., only after a
     *   permit was acquired — so it measures true concurrency through the
     *   bulkhead, not just threads that want to run.
     * - `maxSeen` records the high-water mark of inFlight.
     *
     * Watch the log: "in-flight=N" lines never exceed 2 even though 8
     * threads are racing — that's the bulkhead doing its job.
     */
    @Test
    void limitsConcurrentApiCalls() throws Exception {
        int permits = 2;
        Bulkhead<String> bulkhead = Bulkhead.<String>builder(permits)
                .withMaxWaitTime(Duration.ofSeconds(30))
                .build();
        WeatherService service = new WeatherService(bulkhead);

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();

        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = IntStream.range(0, 8)
                    .mapToObj(i -> pool.submit(() -> Failsafe.with(bulkhead).get(() -> {
                        int now = inFlight.incrementAndGet();
                        maxSeen.accumulateAndGet(now, Math::max);
                        log.info("call {}: permit acquired, in-flight={}", i, now);
                        try {
                            return service.fetchForecast();
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    })))
                    .toList();
            for (var f : futures) {
                assertTrue(f.get(60, TimeUnit.SECONDS).contains("\"current\""));
            }
        } finally {
            pool.shutdownNow();
        }

        // 8 threads racing to call the API, but never more than 2 calls in flight
        log.info("peak concurrency observed: {} (cap was {})", maxSeen.get(), permits);
        assertTrue(maxSeen.get() <= permits);
        assertTrue(maxSeen.get() >= 1);
    }
}
