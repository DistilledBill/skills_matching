package com.example.resumescreening.job;

import java.util.List;

/**
 * A role to screen for, loaded from {@code jobs/<id>.yaml}. Everything the
 * model judges comes from here; weights and thresholds are applied in code.
 */
public record JobSpec(String id, String title, String summary, List<MustHave> mustHaves,
		List<Skill> skills, Thresholds thresholds) {

	/** Becomes one Noul: does the resume show evidence of this? */
	public record MustHave(String id, String requirement) {
	}

	/** Becomes one Score. Levels run from least to most. */
	public record Skill(String id, double weight, String question, List<String> levels) {

		public int maxLevel() {
			return levels.size() - 1;
		}

	}

	/**
	 * @param mustHavePass  at or above: requirement met
	 * @param mustHaveFail  below: requirement not shown; between: human review
	 * @param minConfidence a Score below this on a weight >= 0.2 skill goes to review
	 */
	public record Thresholds(double mustHavePass, double mustHaveFail, double minConfidence) {
	}

	public JobSpec withId(String id) {
		return new JobSpec(id, title, summary, mustHaves, skills, thresholds);
	}

	public double totalWeight() {
		return skills.stream().mapToDouble(Skill::weight).sum();
	}

}
