package com.example.resumescreening.screening;

import java.util.ArrayList;
import java.util.List;

import com.example.resumescreening.TestAnswers;
import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.screening.CandidateResult.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScreeningPolicyTest {

	private final JobSpec job = TestAnswers.sampleJob();

	@Test
	void strongCandidateMeetsWithFullComposite() {
		CandidateResult result = ScreeningPolicy.decide("a", job, TestAnswers.strong(job).response());

		assertThat(result.status()).isEqualTo(Status.MEETS);
		assertThat(result.composite()).isCloseTo(1.0, within(1e-9));
		assertThat(result.reasons()).isEmpty();
	}

	@Test
	void compositeIsWeightedAverageOfNormalizedScores() {
		// python_depth has 5 levels (0-4) and weight 0.30; everything else at 0.
		TestAnswers answers = TestAnswers.strong(job);
		job.skills().forEach(c -> answers.score(c.id(), 0, 0.9));
		answers.score("python_depth", 2, 0.9);

		CandidateResult result = ScreeningPolicy.decide("a", job, answers.response());

		assertThat(result.scores().get("python_depth")).isCloseTo(0.5, within(1e-9));
		assertThat(result.composite()).isCloseTo(0.30 / job.totalWeight() * 0.5, within(1e-9));
	}

	@Test
	void mustHaveBelowFailThresholdIsMissing() {
		CandidateResult result = ScreeningPolicy.decide("c", job,
				TestAnswers.strong(job).mustHave("python_professional", 0.05).response());

		assertThat(result.status()).isEqualTo(Status.MISSING);
		assertThat(result.reasons()).containsExactly("not shown: Python professional (0.05)");
	}

	@Test
	void mustHaveBetweenThresholdsGoesToReview() {
		CandidateResult result = ScreeningPolicy.decide("b", job,
				TestAnswers.strong(job).mustHave("backend_services", 0.5).response());

		assertThat(result.status()).isEqualTo(Status.REVIEW);
		assertThat(result.reasons()).containsExactly("unclear: Backend services (0.50)");
	}

	@Test
	void missingIsNotDowngradedByLaterReviewReasons() {
		CandidateResult result = ScreeningPolicy.decide("x", job,
				TestAnswers.strong(job)
					.mustHave("python_professional", 0.05)
					.mustHave("backend_services", 0.5)
					.score("python_depth", 2, 0.1)
					.response());

		assertThat(result.status()).isEqualTo(Status.MISSING);
		assertThat(result.reasons()).hasSize(3);
	}

	@Test
	void lowConfidenceOnHeavySkillGoesToReview() {
		// python_depth weight 0.30 >= 0.2; min_confidence 0.45
		CandidateResult result = ScreeningPolicy.decide("d", job,
				TestAnswers.strong(job).score("python_depth", 3, 0.30).response());

		assertThat(result.status()).isEqualTo(Status.REVIEW);
		assertThat(result.reasons()).containsExactly("uncertain: Python depth (conf 0.30)");
	}

	@Test
	void lowConfidenceOnLightSkillIsIgnored() {
		// domain_relevance weight 0.10 < 0.2
		CandidateResult result = ScreeningPolicy.decide("d", job,
				TestAnswers.strong(job).score("domain_relevance", 1, 0.10).response());

		assertThat(result.status()).isEqualTo(Status.MEETS);
	}

	@Test
	void rankingOrdersByStatusThenComposite() {
		List<CandidateResult> results = new ArrayList<>(List.of(
				ScreeningPolicy.decide("missing", job,
						TestAnswers.strong(job).mustHave("python_professional", 0.0).response()),
				ScreeningPolicy.decide("review", job,
						TestAnswers.strong(job).mustHave("backend_services", 0.5).response()),
				ScreeningPolicy.decide("meets-low", job,
						TestAnswers.strong(job).score("python_depth", 0, 0.9).response()),
				ScreeningPolicy.decide("meets-high", job, TestAnswers.strong(job).response())));

		results.sort(CandidateResult.RANKING);

		assertThat(results).extracting(CandidateResult::name)
			.containsExactly("meets-high", "meets-low", "review", "missing");
	}

}
