package com.example.resumescreening.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the Claude API, used only for writing suggestions in the job spec editor.
 *
 * @param apiKey         {@code ANTHROPIC_API_KEY}; empty is allowed, and suggestions are then turned off
 * @param model          the Claude model that drafts questions, levels, and requirements
 * @param maxTokens      the most a single suggestion may write
 * @param maxAttempts    total attempts per call, including the first
 * @param initialBackoff delay before the first retry; doubled after each one
 */
@ConfigurationProperties("anthropic")
public record AnthropicProperties(
		String apiKey,
		URI baseUrl,
		String model,
		int maxTokens,
		int maxAttempts,
		Duration initialBackoff,
		Duration timeout) {
}
