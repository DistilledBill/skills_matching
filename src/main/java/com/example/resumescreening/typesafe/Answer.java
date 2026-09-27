package com.example.resumescreening.typesafe;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A typed System One answer, returned under the same id as its question. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = Answer.Noul.class, name = "noul"),
		@JsonSubTypes.Type(value = Answer.Score.class, name = "score"),
		@JsonSubTypes.Type(value = Answer.Choice.class, name = "choice") })
public sealed interface Answer {

	/** Probability that the answer is yes, 0 to 1. */
	record Noul(double noul) implements Answer {
	}

	/**
	 * Probability-weighted level (can land between levels). {@code legend} and
	 * {@code probabilities} are keyed by level index as a string.
	 */
	record Score(double score, double confidence, Map<String, String> legend, Map<String, Double> probabilities)
			implements Answer {
	}

	record Choice(String choice, double confidence, Map<String, Double> probabilities) implements Answer {
	}

}
