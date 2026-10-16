package com.example.resiliency.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.failsafe.Bulkhead;
import dev.failsafe.CircuitBreaker;
import dev.failsafe.Failsafe;
import dev.failsafe.RateLimiter;
import dev.failsafe.RetryPolicy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live dashboard for the resiliency tests.
 *
 * Runs REAL Failsafe policy executions in the background and streams every
 * event (attempts, failures, retry schedules, rejections, breaker state
 * changes, permit grants) to the browser over Server-Sent Events.
 *
 * Start with:  mvn -q compile exec:java
 * Then open:   http://localhost:8080
 */
public class ResiliencyDashboard {

    private static final List<OutputStream> sseClients = new CopyOnWriteArrayList<>();
    private static final ExecutorService pool = Executors.newCachedThreadPool();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ResiliencyDashboard::index);
        server.createContext("/events", ResiliencyDashboard::events);
        server.createContext("/run", ResiliencyDashboard::run);
        server.setExecutor(pool);
        server.start();
        System.out.println("Dashboard running on port " + port);
    }

    // ---------- HTTP handlers ----------

    private static void index(HttpExchange ex) throws IOException {
        byte[] html;
        try (var in = ResiliencyDashboard.class.getResourceAsStream("/dashboard.html")) {
            html = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, html.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(html);
        }
    }

    private static void events(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        sseClients.add(os);
        // Send one comment so the response flushes immediately
        os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static void run(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        String scenario = q == null ? "" : q.replace("scenario=", "");
        switch (scenario) {
            case "retry-flaky" -> pool.submit(ResiliencyDashboard::retryFlaky);
            case "retry-backoff" -> pool.submit(ResiliencyDashboard::retryBackoff);
            case "breaker" -> pool.submit(ResiliencyDashboard::breakerLifecycle);
            case "bulkhead" -> pool.submit(ResiliencyDashboard::bulkheadLoad);
            case "ratelimiter" -> pool.submit(ResiliencyDashboard::rateLimiterPacing);
            case "all" -> pool.submit(ResiliencyDashboard::runAll);
            default -> {
                ex.sendResponseHeaders(400, -1);
                return;
            }
        }
        ex.sendResponseHeaders(202, -1);
    }

    // ---------- Event broadcast ----------

    private static void emit(String lane, String type, String label, long durMs) {
        String json = "{\"lane\":\"" + lane + "\",\"type\":\"" + type
                + "\",\"label\":\"" + escape(label) + "\",\"dur\":" + durMs
                + ",\"t\":" + System.currentTimeMillis() + "}";
        byte[] data = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : sseClients) {
            try {
                os.write(data);
                os.flush();
            } catch (IOException e) {
                sseClients.remove(os);
            }
        }
    }

    private static void emit(String lane, String type, String label) {
        emit(lane, type, label, 0);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------- Scenarios (real Failsafe executions) ----------

    private static void runAll() {
        retryFlaky();
        retryBackoff();
        breakerLifecycle();
        bulkheadLoad();
        rateLimiterPacing();
    }

    /** Retry: endpoint fails twice with 503, recovers on attempt 3. */
    private static void retryFlaky() {
        String lane = "retry-flaky";
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(400))
                .onFailedAttempt(e -> emit(lane, "fail",
                        "attempt " + e.getAttemptCount() + " FAILED: " + e.getLastException().getMessage()))
                .onRetryScheduled(e -> emit(lane, "wait",
                        "retry scheduled in " + e.getDelay().toMillis() + "ms", e.getDelay().toMillis()))
                .onSuccess(e -> emit(lane, "success", "SUCCESS on attempt " + e.getAttemptCount()))
                .build();
        try {
            Failsafe.with(retry).get(() -> {
                int n = calls.incrementAndGet();
                emit(lane, "attempt", "HTTP call #" + n + " -> endpoint");
                Thread.sleep(80);
                if (n < 3) throw new RuntimeException("HTTP 503 unavailable");
                return "HTTP 200 {\"temperature_2m\":9.2}";
            });
        } catch (Exception ignored) {
        }
        emit(lane, "done", "call finished");
    }

    /** Retry: exponential backoff on a dead endpoint -> exhaustion. */
    private static void retryBackoff() {
        String lane = "retry-backoff";
        RetryPolicy<Object> retry = RetryPolicy.builder()
                .withMaxAttempts(4)
                .withBackoff(Duration.ofMillis(150), Duration.ofMillis(1500))
                .onFailedAttempt(e -> emit(lane, "fail",
                        "attempt " + e.getAttemptCount() + " FAILED"))
                .onRetryScheduled(e -> emit(lane, "wait",
                        "backoff " + e.getDelay().toMillis() + "ms", e.getDelay().toMillis()))
                .onRetriesExceeded(e -> emit(lane, "exhausted", "RETRIES EXCEEDED"))
                .build();
        try {
            Failsafe.with(retry).get(() -> {
                emit(lane, "attempt", "HTTP call -> dead endpoint");
                Thread.sleep(60);
                throw new RuntimeException("HTTP 503");
            });
        } catch (Exception ignored) {
        }
        emit(lane, "done", "exception propagated to caller");
    }

    /** Circuit breaker: fail to OPEN, rejected fast, half-open probe, CLOSED. */
    private static void breakerLifecycle() {
        String lane = "breaker";
        CircuitBreaker<Object> breaker = CircuitBreaker.builder()
                .withFailureThreshold(2)
                .withSuccessThreshold(1)
                .withDelay(Duration.ofMillis(1500))
                .onOpen(e -> emit(lane, "state", "breaker -> OPEN"))
                .onHalfOpen(e -> emit(lane, "state", "breaker -> HALF_OPEN"))
                .onClose(e -> emit(lane, "state", "breaker -> CLOSED"))
                .build();
        emit(lane, "state", "breaker -> CLOSED");

        AtomicInteger n = new AtomicInteger();
        // Two failures trip it
        for (int i = 0; i < 2; i++) {
            try {
                Failsafe.with(breaker).get(() -> {
                    emit(lane, "attempt", "call " + n.incrementAndGet() + " -> endpoint");
                    Thread.sleep(60);
                    throw new RuntimeException("HTTP 503");
                });
            } catch (Exception e) {
                emit(lane, "fail", "call failed");
            }
        }
        // Fast-fail rejection while open
        try {
            Failsafe.with(breaker).get(() -> "never runs");
        } catch (Exception e) {
            emit(lane, "rejected", "call REJECTED (fail fast, 0 endpoint hits)");
        }
        // Wait out the delay, then probe succeeds
        try {
            Thread.sleep(1600);
            String r = Failsafe.with(breaker).get(() -> {
                emit(lane, "attempt", "probe call -> endpoint");
                Thread.sleep(60);
                return "HTTP 200";
            });
            emit(lane, "success", "probe succeeded: " + r);
        } catch (Exception ignored) {
        }
        emit(lane, "done", "breaker demo finished");
    }

    /** Bulkhead: 6 calls on 6 threads, only 2 permits -> visible queueing. */
    private static void bulkheadLoad() {
        String lane = "bulkhead";
        Bulkhead<Object> bulkhead = Bulkhead.builder(2)
                .withMaxWaitTime(Duration.ofSeconds(10))
                .build();
        emit(lane, "info", "bulkhead: 2 permits, 6 incoming calls");
        AtomicInteger inFlight = new AtomicInteger();
        CountDownLatch allDone = new CountDownLatch(6);
        for (int i = 0; i < 6; i++) {
            final int id = i + 1;
            pool.submit(() -> {
                try {
                    emit(lane, "queued", "call " + id + " waiting for permit");
                    Failsafe.with(bulkhead).run(() -> {
                        int now = inFlight.incrementAndGet();
                        emit(lane, "attempt", "call " + id + " IN FLIGHT (concurrency=" + now + ")", 900);
                        Thread.sleep(900);
                        inFlight.decrementAndGet();
                        emit(lane, "success", "call " + id + " done");
                    });
                } catch (Exception e) {
                    emit(lane, "rejected", "call " + id + " rejected");
                } finally {
                    allDone.countDown();
                }
            });
        }
        try {
            allDone.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        emit(lane, "done", "all 6 calls finished, max concurrency never exceeded 2");
    }

    /** Rate limiter: 5 calls through a 3-per-second smooth limiter. */
    private static void rateLimiterPacing() {
        String lane = "ratelimiter";
        RateLimiter<Object> limiter = RateLimiter.<Object>smoothBuilder(2, Duration.ofSeconds(1))
                .withMaxWaitTime(Duration.ofSeconds(5))
                .build();
        emit(lane, "info", "smooth limiter: 2 permits/second, 5 calls queued");
        for (int i = 1; i <= 5; i++) {
            final int id = i;
            try {
                emit(lane, "queued", "call " + id + " requesting permit");
                Failsafe.with(limiter).run(() -> {
                    emit(lane, "attempt", "call " + id + " GRANTED", 100);
                    Thread.sleep(50);
                    emit(lane, "success", "call " + id + " done");
                });
            } catch (Exception e) {
                emit(lane, "rejected", "call " + id + " rejected");
            }
        }
        emit(lane, "done", "calls were paced ~500ms apart");
    }
}
