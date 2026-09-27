package com.example.resumescreening.typesafe;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SystemOneResponse(String model, Map<String, Answer> answers, Usage usage) {

	public record Usage(@JsonProperty("input_tokens") int inputTokens, @JsonProperty("output_tokens") int outputTokens) {
	}

	public Answer.Noul noul(String id) {
		return answer(id, Answer.Noul.class);
	}

	public Answer.Score score(String id) {
		return answer(id, Answer.Score.class);
	}

	private <T extends Answer> T answer(String id, Class<T> type) {
		Answer answer = answers.get(id);
		if (!type.isInstance(answer)) {
			throw new TypeSafeException("Expected a " + type.getSimpleName().toLowerCase() + " answer for '" + id
					+ "' but got " + answer);
		}
		return type.cast(answer);
	}

}
