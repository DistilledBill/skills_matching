package com.example.resumescreening.screening;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.screening.CandidateResult.Status;
import com.example.resumescreening.typesafe.Answer;
import com.example.resumescreening.typesafe.SystemOneResponse;

/** Turns raw judgments into a decision. All policy lives here, none in the model. */
public final class ScreeningPolicy {

	/** Skills weighted at least this much send low-confidence answers to review. */
	static final double HEAVY_WEIGHT = 0.2;

	private ScreeningPolicy() {
	}

	public static CandidateResult decide(String name, JobSpec job, SystemOneResponse response) {
		JobSpec.Thresholds t = job.thresholds();
		Status status = Status.MEETS;
		List<String> reasons = new ArrayList<>();
		Map<String, Double> mustHaves = new LinkedHashMap<>();
		Map<String, Double> scores = new LinkedHashMap<>();
		Map<String, Double> confidences = new LinkedHashMap<>();

		for (JobSpec.MustHave req : job.mustHaves()) {
			double p = response.noul(QuestionBuilder.mustHaveId(req)).noul();
			mustHaves.put(req.id(), p);
			if (p < t.mustHaveFail()) {
				status = Status.MISSING;
				reasons.add(fmt("not shown: %s (%.2f)", req.id(), p));
			}
			else if (p < t.mustHavePass()) {
				status = worse(status, Status.REVIEW);
				reasons.add(fmt("unclear: %s (%.2f)", req.id(), p));
			}
		}

		double totalWeight = job.totalWeight();
		double composite = 0;
		for (JobSpec.Skill skill : job.skills()) {
			Answer.Score answer = response.score(QuestionBuilder.skillId(skill));
			double normalized = answer.score() / skill.maxLevel();
			scores.put(skill.id(), normalized);
			confidences.put(skill.id(), answer.confidence());
			composite += skill.weight() / totalWeight * normalized;
			if (skill.weight() >= HEAVY_WEIGHT && answer.confidence() < t.minConfidence()) {
				status = worse(status, Status.REVIEW);
				reasons.add(fmt("uncertain: %s (conf %.2f)", skill.id(), answer.confidence()));
			}
		}

		return new CandidateResult(0, name, status, composite, reasons, mustHaves, scores, confidences);
	}

	private static Status worse(Status a, Status b) {
		return a.compareTo(b) >= 0 ? a : b;
	}

	private static String fmt(String format, Object... args) {
		return String.format(Locale.ROOT, format, args);
	}

}
