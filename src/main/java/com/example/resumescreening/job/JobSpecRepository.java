package com.example.resumescreening.job;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.example.resumescreening.config.ScreeningProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Loads and validates every {@code *.yaml} job spec at startup; the id is the file name. */
@Component
public class JobSpecRepository {

	private static final Logger log = LoggerFactory.getLogger(JobSpecRepository.class);

	private final Map<String, JobSpec> jobs = new TreeMap<>();

	public JobSpecRepository(ScreeningProperties props) {
		YAMLMapper yaml = YAMLMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
		Path dir = props.jobsDir();
		if (!Files.isDirectory(dir)) {
			log.warn("Job spec folder {} does not exist; no jobs loaded", dir.toAbsolutePath());
			return;
		}
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(JobSpecRepository::isYaml).sorted().toList()) {
				String id = StringUtils.stripFilenameExtension(file.getFileName().toString());
				JobSpec job = yaml.readValue(file.toFile(), JobSpec.class).withId(id);
				validate(file, job);
				jobs.put(id, job);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		log.info("Loaded {} job spec(s) from {}: {}", jobs.size(), dir, jobs.keySet());
	}

	public List<JobSpec> all() {
		return List.copyOf(jobs.values());
	}

	public JobSpec get(String id) {
		JobSpec job = jobs.get(id);
		if (job == null) {
			throw new JobNotFoundException(id);
		}
		return job;
	}

	private static boolean isYaml(Path file) {
		String name = file.getFileName().toString();
		return name.endsWith(".yaml") || name.endsWith(".yml");
	}

	private static void validate(Path file, JobSpec job) {
		// The id names this job's answer-cache folder, so keep it to safe file-name characters.
		require(file, job.id().matches("[A-Za-z0-9_-]+"),
				"the file name (the job id) may only use letters, digits, '_' and '-'");
		require(file, StringUtils.hasText(job.title()), "title is required");
		require(file, StringUtils.hasText(job.targetLevel()), "target_level is required");
		require(file, StringUtils.hasText(job.summary()), "summary is required");
		require(file, job.mustHaves() != null, "must_haves is required (may be empty)");
		require(file, job.skills() != null && !job.skills().isEmpty(), "at least one skill is required");
		require(file, job.thresholds() != null, "thresholds is required");

		Set<String> ids = new HashSet<>();
		for (JobSpec.MustHave m : job.mustHaves()) {
			require(file, ids.add("must_" + m.id()), "duplicate must_have id " + m.id());
			require(file, StringUtils.hasText(m.requirement()), "must_have " + m.id() + " needs a requirement");
		}
		for (JobSpec.Skill c : job.skills()) {
			require(file, ids.add("skill_" + c.id()), "duplicate skill id " + c.id());
			require(file, c.weight() > 0, "skill " + c.id() + " needs a positive weight");
			require(file, StringUtils.hasText(c.question()), "skill " + c.id() + " needs a question");
			require(file, c.levels() != null && c.levels().size() >= 2 && c.levels().size() <= 10,
					"skill " + c.id() + " needs 2 to 10 levels");
		}

		JobSpec.Thresholds t = job.thresholds();
		require(file, 0 <= t.mustHaveFail() && t.mustHaveFail() <= t.mustHavePass() && t.mustHavePass() <= 1,
				"thresholds need 0 <= must_have_fail <= must_have_pass <= 1");
	}

	private static void require(Path file, boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException("Invalid job spec " + file + ": " + message);
		}
	}

}
