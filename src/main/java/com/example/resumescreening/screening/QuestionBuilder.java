package com.example.resumescreening.screening;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.typesafe.Question;

/**
 * Turns a job spec into the questions asked of every resume: one Noul per
 * must-have and one Score per skill, all in a single request.
 */
public final class QuestionBuilder {

	static final String MUST_HAVE_QUESTION = "Does `resume` show evidence that the candidate meets `requirement`?";

	static final Question.NoulCriteria MUST_HAVE_CRITERIA = new Question.NoulCriteria(
			"The resume describes experience that satisfies the requirement",
			"The resume does not mention it, or what it describes falls short of the requirement");

	private QuestionBuilder() {
	}

	public static String mustHaveId(JobSpec.MustHave mustHave) {
		return "must_" + mustHave.id();
	}

	public static String skillId(JobSpec.Skill skill) {
		return "skill_" + skill.id();
	}

	public static Map<String, Question> build(JobSpec job) {
		Map<String, Question> questions = new LinkedHashMap<>();
		for (JobSpec.MustHave req : job.mustHaves()) {
			Map<String, String> instructions = new LinkedHashMap<>();
			instructions.put("requirement", req.requirement());
			instructions.put("question", MUST_HAVE_QUESTION);
			questions.put(mustHaveId(req), new Question.Noul(instructions, MUST_HAVE_CRITERIA));
		}
		for (JobSpec.Skill skill : job.skills()) {
			questions.put(skillId(skill), new Question.Score(skill.question(), skill.levels()));
		}
		return questions;
	}

}
