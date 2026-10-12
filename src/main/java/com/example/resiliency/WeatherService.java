package com.example.resiliency;

import dev.failsafe.Bulkhead;
import dev.failsafe.Failsafe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls the Open-Meteo forecast API through a shared Bulkhead so the number of
 * in-flight HTTP calls can never exceed the bulkhead's permit count.
 *
 * The bulkhead is INJECTED (not created here) — that's the pattern: one shared
 * Bulkhead instance per downstream resource, so every call site that hits
 * api.open-meteo.com respects the same concurrency cap.
 */
public class WeatherService {

    private static final URI FORECAST_URI = URI.create(
            "https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41"
                    + "&current=temperature_2m,wind_speed_10m"
                    + "&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Bulkhead<String> bulkhead;

    public WeatherService(Bulkhead<String> bulkhead) {
        this.bulkhead = bulkhead;
    }

    /**
     * The guarded path: Failsafe acquires a bulkhead permit, runs the HTTP
     * call, releases the permit — throwing BulkheadFullException instantly
     * if the bulkhead is saturated (zero-wait default).
     */
    public String forecastJson() {
        return Failsafe.with(bulkhead).get(this::fetchForecast);
    }

    /**
     * The raw HTTP call with no policy around it — exposed so callers/tests
     * can compose their own policy stacks (retry, breaker, rate limiter...)
     * around it. Logs request and response so traffic is visible.
     */
    public String fetchForecast() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(FORECAST_URI)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        // println (not a logger) so output shows without a logging backend
        System.out.println("HTTP -> GET " + FORECAST_URI);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("HTTP <- " + response.statusCode() + " (" + response.body().length() + " bytes)");

        if (response.statusCode() != 200) {
            throw new IOException("Open-Meteo returned HTTP " + response.statusCode());
        }
        return response.body();
    }
}
