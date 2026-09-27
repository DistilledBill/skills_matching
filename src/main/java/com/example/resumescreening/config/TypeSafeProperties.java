package com.example.resumescreening.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the TypeSafe System One API.
 *
 * @param apiKey         bearer token; empty is allowed so the app can start without one
 * @param maxConcurrent  in-flight API requests across the whole app
 * @param maxAttempts    total attempts per request, including the first
 * @param initialBackoff delay before the first retry; doubled after each one
 * @param cacheDir       where raw responses are cached, keyed by request
 */
@ConfigurationProperties("typesafe")
public record TypeSafeProperties(
		String apiKey,
		URI baseUrl,
		String model,
		int maxConcurrent,
		int maxAttempts,
		Duration initialBackoff,
		Duration timeout,
		Path cacheDir) {
}
