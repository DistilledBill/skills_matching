package com.example.resumescreening.screening;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.resumescreening.config.TypeSafeProperties;
import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.typesafe.Question;
import com.example.resumescreening.typesafe.SystemOneRequest;
import com.example.resumescreening.typesafe.TypeSafeClient;
import com.example.resumescreening.typesafe.TypeSafeException;

import org.springframework.stereotype.Service;

/**
 * Screens resumes against a job: one TypeSafe request per resume (all
 * questions at once), requests in parallel, then policy and ranking in code.
 */
@Service
public class ScreeningService {

	private final TypeSafeClient client;

	private final String model;

	public ScreeningService(TypeSafeClient client, TypeSafeProperties props) {
		this.client = client;
		this.model = props.model();
	}

	public ScreeningReport screen(JobSpec job, List<ResumeDocument> resumes) {
		Map<String, Question> questions = QuestionBuilder.build(job);
		List<CandidateResult> results = new ArrayList<>();
		// Concurrency is capped by TypeSafeClient; virtual threads just wait on it.
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<CandidateResult>> futures = resumes.stream()
				.map(resume -> executor.submit(() -> ScreeningPolicy.decide(resume.name(), job,
						client.evaluate(request(job, resume, questions)))))
				.toList();
			for (Future<CandidateResult> future : futures) {
				results.add(join(future));
			}
		}
		results.sort(CandidateResult.RANKING);
		List<CandidateResult> ranked = new ArrayList<>();
		for (int i = 0; i < results.size(); i++) {
			ranked.add(results.get(i).withRank(i + 1));
		}
		return new ScreeningReport(job.id(), job.title(), ranked);
	}

	/** The exact request that would be sent for this resume. */
	public SystemOneRequest request(JobSpec job, ResumeDocument resume) {
		return request(job, resume, QuestionBuilder.build(job));
	}

	private SystemOneRequest request(JobSpec job, ResumeDocument resume, Map<String, Question> questions) {
		Map<String, Object> jobState = new LinkedHashMap<>();
		jobState.put("title", job.title());
		jobState.put("summary", job.summary().strip());
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("job", jobState);
		state.put("resume", Redactor.redact(resume.text()));
		return new SystemOneRequest(state, model, questions);
	}

	private static CandidateResult join(Future<CandidateResult> future) {
		try {
			return future.get();
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new TypeSafeException("Screening failed: " + ex.getCause(), null, ex.getCause());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new TypeSafeException("Interrupted while screening", null, ex);
		}
	}

}
