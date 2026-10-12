package com.example.resiliency;

import dev.failsafe.Bulkhead;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;

import java.time.Duration;

/**
 * Runnable demo: one real Open-Meteo call wrapped in a retry + bulkhead
 * stack. Prints the response body to the console.
 *
 * Run it with: mvn -q compile exec:java
 * (tests use SLF4J loggers; this main uses println so it works without a
 * logging backend on the classpath)
 */
public class App {
    public static void main(String[] args) {
        // Bulkhead: at most 2 requests to the API in flight at once
        Bulkhead<String> bulkhead = Bulkhead.of(2);
        // Retry: up to 3 attempts on transient IO errors, 200ms apart
        RetryPolicy<String> retry = RetryPolicy.<String>builder()
                .handle(java.io.IOException.class)
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(200))
                .onFailedAttempt(e -> System.out.println(
                        "attempt " + e.getAttemptCount() + " failed: " + e.getLastException()))
                .build();

        WeatherService service = new WeatherService(bulkhead);

        System.out.println("Calling Open-Meteo through retry + bulkhead...");
        String json = Failsafe.with(retry, bulkhead).get(service::fetchForecast);
        System.out.println("Response body:");
        System.out.println(json);
    }
}
