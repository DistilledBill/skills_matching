package com.example.resumescreening.typesafe;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A typed System One question. {@code instructions} may be a string or any
 * JSON-serializable structure (e.g. a map with the question and the data it
 * refers to by backticked name).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = Question.Noul.class, name = "noul"),
		@JsonSubTypes.Type(value = Question.Score.class, name = "score") })
public sealed interface Question {

	/** Yes/no question; the answer is the probability of yes. */
	record Noul(Object instructions, @JsonInclude(JsonInclude.Include.NON_NULL) NoulCriteria criteria)
			implements Question {
	}

	/** Rating on ordered levels, least to most; at least 2 and at most 10. */
	record Score(Object instructions, List<String> criteria) implements Question {
	}

	/** What a yes and a no mean. */
	record NoulCriteria(@JsonProperty("true") String whenTrue, @JsonProperty("false") String whenFalse) {
	}

}
