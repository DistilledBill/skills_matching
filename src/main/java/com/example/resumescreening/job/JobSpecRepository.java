package com.example.resumescreening.job;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.example.resumescreening.config.ScreeningProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Loads and validates every {@code *.yaml} job spec at startup; the id is the file name. Specs can also be
 * created, saved, and reloaded from disk while the app runs. Each loaded spec keeps a version (a hash of its
 * file) so a save never overwrites an edit made on disk since the spec was loaded.
 */
@Component
public class JobSpecRepository {

	private static final Logger log = LoggerFactory.getLogger(JobSpecRepository.class);

	/** Job ids name files and answer-cache folders, so they are kept to safe file-name characters. */
	static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]+");

	/** Must-have and skill ids become question ids ({@code must_<id>}, {@code skill_<id>}). */
	static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_]+");

	static final String HEADER = """
			# Job spec for the screener. Everything the model judges comes from here;
			# weights and thresholds are applied in code, so editing them re-ranks from
			# cached answers without new API calls.

			""";

	private final Path dir;

	private final YAMLMapper yaml;

	private final Map<String, Loaded> jobs = new ConcurrentSkipListMap<>();

	private record Loaded(JobSpec job, Path file, String version) {
	}

	/** The outcome of reloading every spec from disk. */
	public record ReloadResult(List<String> loaded, List<String> removed, Map<String, List<String>> errors) {
	}

	public JobSpecRepository(ScreeningProperties props) {
		this.dir = props.jobsDir();
		this.yaml = YAMLMapper.builder()
			.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
			.disable(YAMLWriteFeature.SPLIT_LINES)
			.enable(YAMLWriteFeature.MINIMIZE_QUOTES)
			.enable(YAMLWriteFeature.INDENT_ARRAYS_WITH_INDICATOR)
			.build();
		if (!Files.isDirectory(dir)) {
			log.warn("Job spec folder {} does not exist; no jobs loaded", dir.toAbsolutePath());
			return;
		}
		for (Path file : specFiles()) {
			Loaded loaded = read(file);
			List<String> errors = validate(loaded.job());
			if (!errors.isEmpty()) {
				throw new IllegalStateException("Invalid job spec " + file + ": " + String.join("; ", errors));
			}
			jobs.put(loaded.job().id(), loaded);
		}
		log.info("Loaded {} job spec(s) from {}: {}", jobs.size(), dir, jobs.keySet());
	}

	public List<JobSpec> all() {
		return jobs.values().stream().map(Loaded::job).toList();
	}

	public List<VersionedJobSpec> allVersioned() {
		return jobs.values().stream().map(l -> new VersionedJobSpec(l.job(), l.version())).toList();
	}

	public JobSpec get(String id) {
		return loaded(id).job();
	}

	public VersionedJobSpec versioned(String id) {
		Loaded l = loaded(id);
		return new VersionedJobSpec(l.job(), l.version());
	}

	/** Writes a new spec to {@code <id>.yaml}. The id must be new, both in the app and on disk. */
	public synchronized VersionedJobSpec create(JobSpec spec) {
		if (spec == null) {
			throw new InvalidJobSpecException("(new)", List.of("the spec is missing"));
		}
		String id = spec.id();
		requireValid(id, spec);
		Path file = dir.resolve(id + ".yaml");
		if (jobs.containsKey(id) || Files.exists(file) || Files.exists(dir.resolve(id + ".yml"))) {
			throw new JobSpecConflictException("A job spec with id '" + id + "' already exists");
		}
		return write(spec, file);
	}

	/**
	 * Replaces a spec's file. {@code expectedVersion} is the version the editor loaded; if the file has changed
	 * on disk since then, nothing is written.
	 */
	public synchronized VersionedJobSpec update(String id, JobSpec spec, String expectedVersion) {
		Loaded current = loaded(id);
		if (spec == null || !StringUtils.hasText(expectedVersion)) {
			throw new InvalidJobSpecException(id, List.of("a save needs the spec and the version it was loaded at"));
		}
		JobSpec withId = spec.withId(id);
		requireValid(id, withId);
		if (!Files.exists(current.file()) || !versionOf(current.file()).equals(expectedVersion)) {
			throw new JobSpecConflictException("Job spec '" + id
					+ "' changed on disk since it was loaded. Load the version on disk, then make your changes again.");
		}
		return write(withId, current.file());
	}

	/** Re-reads one spec from disk. An invalid file is rejected and the version already loaded stays. */
	public synchronized VersionedJobSpec reload(String id) {
		if (id == null || !JOB_ID.matcher(id).matches()) {
			throw new JobNotFoundException(id);
		}
		Path file = jobs.containsKey(id) ? jobs.get(id).file() : dir.resolve(id + ".yaml");
		if (!Files.isRegularFile(file)) {
			throw new JobNotFoundException(id);
		}
		Loaded loaded = readForReload(id, file);
		jobs.put(id, loaded);
		return new VersionedJobSpec(loaded.job(), loaded.version());
	}

	/**
	 * Re-reads every spec from disk. Valid files replace what is loaded, new files are added, and specs whose
	 * file is gone are dropped. An invalid file is reported and its loaded version, if any, stays.
	 */
	public synchronized ReloadResult reloadAll() {
		List<String> loaded = new ArrayList<>();
		Map<String, List<String>> errors = new TreeMap<>();
		Set<String> seen = new HashSet<>();
		for (Path file : specFiles()) {
			String id = idOf(file);
			seen.add(id);
			try {
				jobs.put(id, readForReload(id, file));
				loaded.add(id);
			}
			catch (InvalidJobSpecException ex) {
				errors.put(id, ex.errors());
			}
		}
		List<String> removed = jobs.keySet().stream().filter(id -> !seen.contains(id)).toList();
		removed.forEach(jobs::remove);
		log.info("Reloaded job specs: {} loaded, {} removed, {} invalid", loaded.size(), removed.size(), errors.size());
		return new ReloadResult(loaded, removed, errors);
	}

	/** Every problem with a spec, as messages for the person editing it; empty when the spec is valid. */
	public static List<String> validate(JobSpec job) {
		List<String> errors = new ArrayList<>();
		// The id names this job's answer-cache folder, so keep it to safe file-name characters.
		check(errors, job.id() != null && JOB_ID.matcher(job.id()).matches(),
				"the file name (the job id) may only use letters, digits, '_' and '-'");
		check(errors, StringUtils.hasText(job.title()), "title is required");
		check(errors, StringUtils.hasText(job.targetLevel()), "target_level is required");
		check(errors, StringUtils.hasText(job.summary()), "summary is required");
		check(errors, job.mustHaves() != null, "must_haves is required (may be empty)");
		check(errors, job.skills() != null && !job.skills().isEmpty(), "at least one skill is required");
		check(errors, job.thresholds() != null, "thresholds is required");

		Set<String> ids = new HashSet<>();
		for (JobSpec.MustHave m : job.mustHaves() == null ? List.<JobSpec.MustHave>of() : job.mustHaves()) {
			if (!checkItemId(errors, "must_have", m.id())) {
				continue;
			}
			check(errors, ids.add("must_" + m.id()), "duplicate must_have id " + m.id());
			check(errors, StringUtils.hasText(m.requirement()), "must_have " + m.id() + " needs a requirement");
		}
		for (JobSpec.Skill s : job.skills() == null ? List.<JobSpec.Skill>of() : job.skills()) {
			if (!checkItemId(errors, "skill", s.id())) {
				continue;
			}
			check(errors, ids.add("skill_" + s.id()), "duplicate skill id " + s.id());
			check(errors, s.weight() > 0, "skill " + s.id() + " needs a positive weight");
			check(errors, StringUtils.hasText(s.question()), "skill " + s.id() + " needs a question");
			check(errors, s.levels() != null && s.levels().size() >= 2 && s.levels().size() <= 10,
					"skill " + s.id() + " needs 2 to 10 levels");
			check(errors, s.levels() == null || s.levels().stream().allMatch(StringUtils::hasText),
					"skill " + s.id() + " has an empty level");
		}

		JobSpec.Thresholds t = job.thresholds();
		if (t != null) {
			check(errors, 0 <= t.mustHaveFail() && t.mustHaveFail() <= t.mustHavePass() && t.mustHavePass() <= 1,
					"thresholds need 0 <= must_have_fail <= must_have_pass <= 1");
			check(errors, 0 <= t.minConfidence() && t.minConfidence() <= 1, "min_confidence must be between 0 and 1");
		}
		return errors;
	}

	/** The YAML a spec is saved as: the standard header, then snake_case keys in the usual order. */
	public String toYaml(JobSpec spec) {
		String body = yaml.writeValueAsString(new SpecFile(spec.title(), spec.targetLevel(),
				spec.summary() == null ? null : spec.summary().strip(), spec.mustHaves(), spec.skills(),
				spec.thresholds()));
		return HEADER + body.replaceFirst("(?m)^must_haves:",
				"\n# Each must-have becomes one Noul: \"does the resume show evidence of this?\"\nmust_haves:")
			.replaceFirst("(?m)^skills:",
					"\n# Each skill becomes one Score. Levels run from least to most and must\n"
							+ "# each describe a concrete situation on their own.\nskills:")
			.replaceFirst("(?m)^thresholds:", "\nthresholds:");
	}

	/** A spec as written to its file: no id (the file name is the id), keys in the order people expect. */
	private record SpecFile(String title, String targetLevel, String summary, List<JobSpec.MustHave> mustHaves,
			List<JobSpec.Skill> skills, JobSpec.Thresholds thresholds) {
	}

	private VersionedJobSpec write(JobSpec spec, Path file) {
		try {
			Files.createDirectories(dir);
			byte[] bytes = toYaml(spec).getBytes(StandardCharsets.UTF_8);
			Path tmp = Files.createTempFile(dir, spec.id(), ".tmp");
			Files.write(tmp, bytes);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			Loaded loaded = new Loaded(spec, file, hash(bytes));
			jobs.put(spec.id(), loaded);
			log.info("Saved job spec {}", file);
			return new VersionedJobSpec(loaded.job(), loaded.version());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private Loaded readForReload(String id, Path file) {
		Loaded loaded;
		try {
			loaded = read(file);
		}
		catch (JacksonException ex) {
			throw new InvalidJobSpecException(id, List.of("the file is not valid YAML: " + ex.getOriginalMessage()));
		}
		requireValid(id, loaded.job());
		return loaded;
	}

	private Loaded read(Path file) {
		try {
			byte[] bytes = Files.readAllBytes(file);
			JobSpec job = yaml.readValue(bytes, JobSpec.class).withId(idOf(file));
			return new Loaded(job, file, hash(bytes));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private Loaded loaded(String id) {
		Loaded l = id == null ? null : jobs.get(id);
		if (l == null) {
			throw new JobNotFoundException(id);
		}
		return l;
	}

	private static void requireValid(String id, JobSpec spec) {
		List<String> errors = validate(spec);
		if (!errors.isEmpty()) {
			throw new InvalidJobSpecException(id, errors);
		}
	}

	private List<Path> specFiles() {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(JobSpecRepository::isYaml).sorted().toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String versionOf(Path file) {
		try {
			return hash(Files.readAllBytes(file));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String idOf(Path file) {
		return StringUtils.stripFilenameExtension(file.getFileName().toString());
	}

	private static boolean isYaml(Path file) {
		String name = file.getFileName().toString();
		return name.endsWith(".yaml") || name.endsWith(".yml");
	}

	private static boolean checkItemId(List<String> errors, String kind, String id) {
		if (!StringUtils.hasText(id)) {
			errors.add("every " + kind + " needs an id");
			return false;
		}
		check(errors, ITEM_ID.matcher(id).matches(),
				kind + " id " + id + " may only use lowercase letters, digits and '_'");
		return true;
	}

	private static void check(List<String> errors, boolean condition, String message) {
		if (!condition) {
			errors.add(message);
		}
	}

}
