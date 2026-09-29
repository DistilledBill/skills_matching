package com.example.resumescreening.screening;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.job.JobSpecRepository;
import com.example.resumescreening.typesafe.AnswerCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Moves answers cached in the old flat layout ({@code .cache/<key>.json}) into their job's folder
 * ({@code .cache/<jobId>/<key>.json}) when the app starts. An answer is matched by rebuilding the request for
 * every resume in each job's {@code <jobId>_candidates} folder with the current spec. Answers that match
 * nothing (uploads, other folders, older spec wording) are deleted. Once no flat-layout answers are left, this
 * does nothing.
 */
@Component
public class CacheMigration {

	private static final Logger log = LoggerFactory.getLogger(CacheMigration.class);

	private final JobSpecRepository jobs;

	private final ResumeLoader loader;

	private final ScreeningService screening;

	private final AnswerCache cache;

	public CacheMigration(JobSpecRepository jobs, ResumeLoader loader, ScreeningService screening, AnswerCache cache) {
		this.jobs = jobs;
		this.loader = loader;
		this.screening = screening;
		this.cache = cache;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		migrate();
	}

	/** Moved answers per job id, and how many unmatched answers were deleted. */
	public record Result(Map<String, Integer> moved, int deleted) {
	}

	public Result migrate() {
		List<Path> legacy = cache.legacyAnswers();
		if (legacy.isEmpty()) {
			return new Result(Map.of(), 0);
		}
		Map<String, Path> byKey = new HashMap<>();
		legacy.forEach(p -> byKey.put(p.getFileName().toString().replaceFirst("\\.json$", ""), p));

		Map<String, Integer> moved = new TreeMap<>();
		for (JobSpec job : jobs.all()) {
			for (ResumeDocument resume : candidates(job)) {
				Path answer = byKey.remove(cache.key(screening.request(job, resume)));
				if (answer != null) {
					cache.adopt(answer, job.id());
					moved.merge(job.id(), 1, Integer::sum);
				}
			}
		}
		byKey.values().forEach(cache::deleteLegacy);

		log.info("Answer cache moved to one folder per job: moved {} {}, deleted {} that matched no job's resumes",
				moved.values().stream().mapToInt(Integer::intValue).sum(), moved, byKey.size());
		return new Result(moved, byKey.size());
	}

	private List<ResumeDocument> candidates(JobSpec job) {
		try {
			return loader.fromFolder(job.id() + "_candidates");
		}
		catch (InvalidResumeException ex) {
			return List.of(); // no folder for this job, or nothing in it
		}
	}

}
