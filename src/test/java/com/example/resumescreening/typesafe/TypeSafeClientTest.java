package com.example.resumescreening.typesafe;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.resumescreening.config.TypeSafeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TypeSafeClientTest {

	private static final String URL = "https://api.typesafe.test/v1/systemone";

	private static final String RESPONSE = """
			{
			  "model": "jev-1.13.0",
			  "answers": {
			    "must_python": {"type": "noul", "noul": 0.93},
			    "comp_depth": {
			      "type": "score", "score": 2.52, "confidence": 0.52,
			      "legend": {"0": "none", "1": "some", "2": "regular", "3": "deep"},
			      "probabilities": {"0": 0.0, "1": 0.0, "2": 0.48, "3": 0.52}
			    }
			  },
			  "usage": {"input_tokens": 535, "output_tokens": 58}
			}
			""";

	@TempDir
	Path cacheDir;

	private final JsonMapper mapper = JsonMapper.builder().build();

	private MockRestServiceServer server;

	private TypeSafeClient client;

	@BeforeEach
	void setUp() {
		client = client("test-key");
	}

	private TypeSafeClient client(String apiKey) {
		TypeSafeProperties props = new TypeSafeProperties(apiKey, URI.create("https://api.typesafe.test"), "jev-latest",
				2, 3, Duration.ofMillis(1), Duration.ofSeconds(5), cacheDir);
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		return new TypeSafeClient(builder, props, mapper, new AnswerCache(props, mapper));
	}

	private static SystemOneRequest request(String resume) {
		Map<String, Question> questions = new LinkedHashMap<>();
		questions.put("must_python", new Question.Noul(
				Map.of("requirement", "Has used Python professionally", "question",
						"Does `resume` show evidence that the candidate meets `requirement`?"),
				new Question.NoulCriteria("yes means", "no means")));
		questions.put("comp_depth", new Question.Score("How deep?", List.of("none", "some", "regular", "deep")));
		return new SystemOneRequest(Map.of("resume", resume), "jev-latest", questions);
	}

	@Test
	void sendsTypedQuestionsAndParsesAnswers() {
		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer test-key"))
			.andExpect(content().json("""
					{
					  "model": "jev-latest",
					  "state": {"resume": "Python dev"},
					  "questions": {
					    "must_python": {
					      "type": "noul",
					      "instructions": {
					        "requirement": "Has used Python professionally",
					        "question": "Does `resume` show evidence that the candidate meets `requirement`?"
					      },
					      "criteria": {"true": "yes means", "false": "no means"}
					    },
					    "comp_depth": {
					      "type": "score",
					      "instructions": "How deep?",
					      "criteria": ["none", "some", "regular", "deep"]
					    }
					  }
					}
					""", JsonCompareMode.STRICT))
			.andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

		SystemOneResponse response = client.evaluate(request("Python dev"));

		server.verify();
		assertThat(response.noul("must_python").noul()).isEqualTo(0.93);
		Answer.Score score = response.score("comp_depth");
		assertThat(score.score()).isEqualTo(2.52);
		assertThat(score.confidence()).isEqualTo(0.52);
		assertThat(score.probabilities()).containsEntry("3", 0.52);
		assertThat(response.usage().inputTokens()).isEqualTo(535);
	}

	@Test
	void omitsNoulCriteriaWhenAbsent() {
		server.expect(requestTo(URL))
			.andExpect(content().json("""
					{"questions": {"q": {"type": "noul", "instructions": "Is it?"}}}
					"""))
			.andExpect(request -> assertThat(request.getBody().toString()).doesNotContain("criteria"))
			.andRespond(withSuccess("""
					{"model": "m", "answers": {"q": {"type": "noul", "noul": 0.5}},
					 "usage": {"input_tokens": 1, "output_tokens": 1}}
					""", MediaType.APPLICATION_JSON));

		client.evaluate(new SystemOneRequest("s", "jev-latest", Map.of("q", new Question.Noul("Is it?", null))));

		server.verify();
	}

	@Test
	void retriesRateLimitAndOverload() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(529)));
		server.expect(requestTo(URL)).andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

		SystemOneResponse response = client.evaluate(request("retry me"));

		server.verify();
		assertThat(response.noul("must_python").noul()).isEqualTo(0.93);
	}

	@Test
	void givesUpAfterMaxAttempts() {
		for (int i = 0; i < 3; i++) {
			server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		}

		assertThatThrownBy(() -> client.evaluate(request("always limited")))
			.isInstanceOfSatisfying(TypeSafeException.class, ex -> assertThat(ex.status()).isEqualTo(429));
		server.verify();
	}

	@Test
	void doesNotRetryClientErrors() {
		server.expect(requestTo(URL))
			.andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).body("{\"detail\":\"bad question\"}"));

		assertThatThrownBy(() -> client.evaluate(request("bad")))
			.isInstanceOf(TypeSafeException.class)
			.hasMessageContaining("422")
			.hasMessageContaining("bad question");
		server.verify();
	}

	@Test
	void secondIdenticalRequestIsServedFromCache() {
		server.expect(requestTo(URL)).andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

		SystemOneResponse first = client.evaluate(request("cached"));
		SystemOneResponse second = client(" ").evaluate(request("cached")); // no key, no server expectations

		assertThat(second).isEqualTo(first);
	}

	@Test
	void changedStateMissesCache() {
		server.expect(requestTo(URL)).andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
		client.evaluate(request("one"));

		assertThatThrownBy(() -> client(" ").evaluate(request("two"))).isInstanceOf(MissingApiKeyException.class);
	}

	@Test
	void cacheKeyIgnoresMapOrdering() {
		AnswerCache cache = new AnswerCache(new TypeSafeProperties(null, null, null, 1, 1, null, null, cacheDir),
				mapper);
		Map<String, Object> ab = new LinkedHashMap<>();
		ab.put("a", 1);
		ab.put("b", 2);
		Map<String, Object> ba = new LinkedHashMap<>();
		ba.put("b", 2);
		ba.put("a", 1);

		assertThat(cache.key(new SystemOneRequest(ab, "m", Map.of())))
			.isEqualTo(cache.key(new SystemOneRequest(ba, "m", Map.of())));
	}

}
