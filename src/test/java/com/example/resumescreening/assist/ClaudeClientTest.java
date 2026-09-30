package com.example.resumescreening.assist;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.resumescreening.config.AnthropicProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Talks to a mock server only; never the real Claude API. */
class ClaudeClientTest {

	private static final ClaudeClient.Tool TOOL = new ClaudeClient.Tool("suggest_question", "The question.",
			Map.of("type", "object", "properties", Map.of("question", Map.of("type", "string"))));

	private static final String ANSWER = """
			{"content": [{"type": "tool_use", "id": "t1", "name": "suggest_question",
			  "input": {"question": "How deep is it, based on `resume`?"}}],
			 "stop_reason": "tool_use", "usage": {"input_tokens": 120, "output_tokens": 30}}
			""";

	private MockRestServiceServer server;

	private ClaudeClient client(String apiKey) {
		AnthropicProperties props = new AnthropicProperties(apiKey, URI.create("https://api.anthropic.test"),
				"claude-sonnet-5", 2000, 3, Duration.ofMillis(1), Duration.ofSeconds(5));
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		return new ClaudeClient(builder, props, JsonMapper.builder().build());
	}

	private static List<Map<String, Object>> ask() {
		return List.of(Map.of("role", "user", "content", "Write a question."));
	}

	@Test
	void forcesTheToolAndReturnsItsInput() {
		ClaudeClient claude = client("test-key");
		server.expect(requestTo("https://api.anthropic.test/v1/messages"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("x-api-key", "test-key"))
			.andExpect(header("anthropic-version", "2023-06-01"))
			.andExpect(jsonPath("$.model").value("claude-sonnet-5"))
			.andExpect(jsonPath("$.max_tokens").value(2000))
			.andExpect(jsonPath("$.system").value("the rules"))
			.andExpect(jsonPath("$.tool_choice.type").value("tool"))
			.andExpect(jsonPath("$.tool_choice.name").value("suggest_question"))
			.andExpect(jsonPath("$.tools[0].input_schema.type").value("object"))
			.andExpect(jsonPath("$.messages[0].content").value("Write a question."))
			.andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

		JsonNode input = claude.callTool("the rules", ask(), TOOL);

		assertThat(input.path("question").asString()).isEqualTo("How deep is it, based on `resume`?");
		server.verify();
	}

	@Test
	void retriesOverloadAndRateLimits() {
		ClaudeClient claude = client("test-key");
		server.expect(requestTo("https://api.anthropic.test/v1/messages")).andRespond(withStatus(HttpStatusCode.valueOf(529)));
		server.expect(requestTo("https://api.anthropic.test/v1/messages")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		server.expect(requestTo("https://api.anthropic.test/v1/messages")).andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

		assertThat(claude.callTool("r", ask(), TOOL).has("question")).isTrue();
		server.verify();
	}

	@Test
	void reportsTheApisOwnErrorMessageWithoutRetryingClientErrors() {
		ClaudeClient claude = client("test-key");
		server.expect(times(1), requestTo("https://api.anthropic.test/v1/messages"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"model: not found\"}}"));

		assertThatThrownBy(() -> claude.callTool("r", ask(), TOOL)).isInstanceOf(AssistException.class)
			.hasMessage("Claude returned 400: model: not found");
		server.verify();
	}

	@Test
	void isOffWithoutAKeyAndNeverCallsTheApi() {
		ClaudeClient claude = client(" ");
		assertThat(claude.enabled()).isFalse();
		assertThatThrownBy(() -> claude.callTool("r", ask(), TOOL)).isInstanceOf(AssistUnavailableException.class);
		server.verify();
	}

	@Test
	void failsClearlyWhenNoToolCallComesBack() {
		ClaudeClient claude = client("test-key");
		server.expect(requestTo("https://api.anthropic.test/v1/messages")).andRespond(withSuccess(
				"{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"max_tokens\"}",
				MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> claude.callTool("r", ask(), TOOL)).isInstanceOf(AssistException.class)
			.hasMessageContaining("max_tokens");
	}

}
