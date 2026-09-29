package com.example.resumescreening.screening;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * One screened resume.
 *
 * @param composite   weighted average of normalized skill scores, 0 to 1
 * @param mustHaves   probability each must-have is shown, by must-have id
 * @param scores      skill scores normalized to 0 to 1, by skill id
 * @param confidences Score confidence, by skill id
 */
public record CandidateResult(int rank, String name, Status status, double composite, List<String> reasons,
		Map<String, Double> mustHaves, Map<String, Double> scores, Map<String, Double> confidences) {

	/** Declared in ranking order. */
	public enum Status {

		MEETS, REVIEW, MISSING;

		@JsonValue
		public String json() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

	/** Status first, then highest composite. */
	public static final Comparator<CandidateResult> RANKING = Comparator.comparing(CandidateResult::status)
		.thenComparing(Comparator.comparingDouble(CandidateResult::composite).reversed());

	public CandidateResult withRank(int rank) {
		return new CandidateResult(rank, name, status, composite, reasons, mustHaves, scores, confidences);
	}

}
