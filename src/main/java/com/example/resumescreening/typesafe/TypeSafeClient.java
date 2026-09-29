package com.example.resumescreening.typesafe;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import com.example.resumescreening.config.TypeSafeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls {@code POST /v1/systemone} over HTTP (TypeSafe has no Java SDK).
 * Responses are cached; transient failures are retried with exponential
 * backoff, as the official SDKs do.
 */
@Component
public class TypeSafeClient {

	private static final Logger log = LoggerFactory.getLogger(TypeSafeClient.class);

	private static final Set<Integer> RETRYABLE = Set.of(429, 500, 502, 503, 504, 529);

	private final RestClient http;

	private final TypeSafeProperties props;

	private final JsonMapper mapper;

	private final AnswerCache cache;

	private final Semaphore inFlight;

	public TypeSafeClient(RestClient.Builder typeSafeRestClientBuilder, TypeSafeProperties props, JsonMapper mapper,
			AnswerCache cache) {
		this.http = typeSafeRestClientBuilder.baseUrl(props.baseUrl().toString()).build();
		this.props = props;
		this.mapper = mapper;
		this.cache = cache;
		this.inFlight = new Semaphore(props.maxConcurrent());
	}

	/** @param jobId the job this request screens for; its answers are cached in that job's folder */
	public SystemOneResponse evaluate(String jobId, SystemOneRequest request) {
		String key = cache.key(request);
		String body = cache.get(jobId, key).orElseGet(() -> {
			String fresh = post(request);
			cache.put(jobId, key, fresh);
			return fresh;
		});
		return mapper.readValue(body, SystemOneResponse.class);
	}

	private String post(SystemOneRequest request) {
		if (!StringUtils.hasText(props.apiKey())) {
			throw new MissingApiKeyException();
		}
		String json = mapper.writeValueAsString(request);
		Duration backoff = props.initialBackoff();
		for (int attempt = 1;; attempt++) {
			boolean lastAttempt = attempt >= props.maxAttempts();
			RawResponse response;
			inFlight.acquireUninterruptibly();
			try {
				response = send(json);
			}
			catch (ResourceAccessException ex) {
				if (lastAttempt) {
					throw new TypeSafeException("TypeSafe request failed: " + ex.getMessage(), null, ex);
				}
				log.warn("TypeSafe request failed (attempt {}): {}", attempt, ex.getMessage());
				response = null;
			}
			finally {
				inFlight.release();
			}
			if (response != null) {
				if (response.status() >= 200 && response.status() < 300) {
					return response.body();
				}
				if (!RETRYABLE.contains(response.status()) || lastAttempt) {
					throw new TypeSafeException("TypeSafe returned " + response.status() + ": " + response.body(),
							response.status(), null);
				}
				log.warn("TypeSafe returned {} (attempt {}), retrying", response.status(), attempt);
			}
			sleep(backoff);
			backoff = backoff.multipliedBy(2);
		}
	}

	private RawResponse send(String json) {
		return http.post()
			.uri("/v1/systemone")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
			.contentType(MediaType.APPLICATION_JSON)
			.body(json)
			.exchangeForRequiredValue((req, res) -> new RawResponse(res.getStatusCode().value(),
					new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
	}

	private static void sleep(Duration backoff) {
		long jitter = ThreadLocalRandom.current().nextLong(backoff.toMillis() / 4 + 1);
		try {
			Thread.sleep(backoff.toMillis() + jitter);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new TypeSafeException("Interrupted while retrying TypeSafe request", null, ex);
		}
	}

	private record RawResponse(int status, String body) {
	}

}
