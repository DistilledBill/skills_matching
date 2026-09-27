package com.example.resumescreening;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.resumescreening.config.ScreeningProperties;
import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.job.JobSpecRepository;
import com.example.resumescreening.typesafe.Answer;
import com.example.resumescreening.typesafe.SystemOneResponse;

/** Builds hand-written System One responses for the sample job. */
public final class TestAnswers {

	public static final String JOB_ID = "senior_backend_engineer";

	/** Folder under {@code resumes/} holding the sample resumes for {@link #JOB_ID}. */
	public static final String RESUMES_FOLDER = JOB_ID + "_candidates";

	private final JobSpec job;

	private final Map<String, Double> nouls = new HashMap<>();

	private final Map<String, double[]> scores = new HashMap<>();

	private TestAnswers(JobSpec job) {
		this.job = job;
	}

	public static JobSpec sampleJob() {
		return new JobSpecRepository(new ScreeningProperties(Path.of("jobs"), Path.of("resumes"))).get(JOB_ID);
	}

	/** Every must-have at 0.95; every competency at its top level with confidence 0.9. */
	public static TestAnswers strong(JobSpec job) {
		TestAnswers answers = new TestAnswers(job);
		job.mustHaves().forEach(m -> answers.mustHave(m.id(), 0.95));
		job.competencies().forEach(c -> answers.score(c.id(), c.maxLevel(), 0.9));
		return answers;
	}

	public TestAnswers mustHave(String id, double noul) {
		nouls.put(id, noul);
		return this;
	}

	public TestAnswers score(String id, double score, double confidence) {
		scores.put(id, new double[] { score, confidence });
		return this;
	}

	public SystemOneResponse response() {
		Map<String, Answer> answers = new LinkedHashMap<>();
		job.mustHaves().forEach(m -> answers.put("must_" + m.id(), new Answer.Noul(nouls.get(m.id()))));
		for (JobSpec.Competency c : job.competencies()) {
			double[] s = scores.get(c.id());
			Map<String, String> legend = new LinkedHashMap<>();
			Map<String, Double> probabilities = new LinkedHashMap<>();
			for (int i = 0; i < c.levels().size(); i++) {
				legend.put(String.valueOf(i), c.levels().get(i));
				probabilities.put(String.valueOf(i), i == Math.round(s[0]) ? 1.0 : 0.0);
			}
			answers.put("comp_" + c.id(), new Answer.Score(s[0], s[1], legend, probabilities));
		}
		return new SystemOneResponse("jev-test", answers, new SystemOneResponse.Usage(1, 1));
	}

}
