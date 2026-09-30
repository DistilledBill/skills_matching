package com.example.resumescreening.assist;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import com.example.resumescreening.config.AnthropicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls the Claude Messages API ({@code POST /v1/messages}) with one forced tool, so the answer always comes
 * back as JSON matching the tool's schema. Transient failures are retried with backoff, like
 * {@code TypeSafeClient}.
 */
@Component
public class ClaudeClient {

	private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);

	private static final Set<Integer> RETRYABLE = Set.of(429, 500, 502, 503, 504, 529);

	static final String API_VERSION = "2023-06-01";

	private final RestClient http;

	private final AnthropicProperties props;

	private final JsonMapper mapper;

	/** A tool Claude must call; its input is the suggestion. */
	public record Tool(String name, String description, Map<String, Object> inputSchema) {
	}

	public ClaudeClient(RestClient.Builder anthropicRestClientBuilder, AnthropicProperties props, JsonMapper mapper) {
		this.http = anthropicRestClientBuilder.baseUrl(props.baseUrl().toString()).build();
		this.props = props;
		this.mapper = mapper;
	}

	public boolean enabled() {
		return StringUtils.hasText(props.apiKey());
	}

	public String model() {
		return props.model();
	}

	/** Sends one conversation turn and returns the input Claude gave the forced tool. */
	public JsonNode callTool(String system, List<Map<String, Object>> messages, Tool tool) {
		if (!enabled()) {
			throw new AssistUnavailableException();
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", props.model());
		body.put("max_tokens", props.maxTokens());
		body.put("system", system);
		body.put("messages", messages);
		body.put("tools", List.of(Map.of("name", tool.name(), "description", tool.description(), "input_schema",
				tool.inputSchema())));
		body.put("tool_choice", Map.of("type", "tool", "name", tool.name()));
		JsonNode response = mapper.readTree(post(mapper.writeValueAsString(body)));

		JsonNode usage = response.path("usage");
		log.info("Claude {} for {}: {} input, {} output tokens", props.model(), tool.name(),
				usage.path("input_tokens").asInt(), usage.path("output_tokens").asInt());
		for (JsonNode block : response.path("content")) {
			if ("tool_use".equals(block.path("type").asString()) && tool.name().equals(block.path("name").asString())) {
				return block.path("input");
			}
		}
		throw new AssistException("Claude did not return a suggestion (stop reason: "
				+ response.path("stop_reason").asString("unknown") + ")");
	}

	private String post(String json) {
		Duration backoff = props.initialBackoff();
		for (int attempt = 1;; attempt++) {
			boolean lastAttempt = attempt >= props.maxAttempts();
			try {
				Raw raw = http.post()
					.uri("/v1/messages")
					.header("x-api-key", props.apiKey())
					.header("anthropic-version", API_VERSION)
					.contentType(MediaType.APPLICATION_JSON)
					.body(json)
					.exchangeForRequiredValue((req, res) -> new Raw(res.getStatusCode().value(),
							new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
				if (raw.status() >= 200 && raw.status() < 300) {
					return raw.body();
				}
				if (!RETRYABLE.contains(raw.status()) || lastAttempt) {
					throw new AssistException("Claude returned " + raw.status() + ": " + errorMessage(raw.body()));
				}
				log.warn("Claude returned {} (attempt {}), retrying", raw.status(), attempt);
			}
			catch (ResourceAccessException ex) {
				if (lastAttempt) {
					throw new AssistException("Could not reach Claude: " + ex.getMessage(), ex);
				}
				log.warn("Claude request failed (attempt {}): {}", attempt, ex.getMessage());
			}
			sleep(backoff);
			backoff = backoff.multipliedBy(2);
		}
	}

	/** The API's own error message when it sent one, so the editor can show why. */
	private String errorMessage(String body) {
		try {
			String message = mapper.readTree(body).path("error").path("message").asString("");
			return message.isEmpty() ? body : message;
		}
		catch (RuntimeException ex) {
			return body;
		}
	}

	private static void sleep(Duration backoff) {
		long jitter = ThreadLocalRandom.current().nextLong(backoff.toMillis() / 4 + 1);
		try {
			Thread.sleep(backoff.toMillis() + jitter);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssistException("Interrupted while retrying the Claude request", ex);
		}
	}

	private record Raw(int status, String body) {
	}

}
