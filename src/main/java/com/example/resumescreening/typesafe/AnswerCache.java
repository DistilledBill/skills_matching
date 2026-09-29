package com.example.resumescreening.typesafe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.example.resumescreening.config.TypeSafeProperties;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * File cache of raw System One responses, one folder per job ({@code <cache-dir>/<jobId>/<key>.json}), keyed
 * by a hash of the full request (model, state, and questions). Weights and thresholds are not part of the
 * request, so changing them re-ranks from cache; changing a question re-asks. Keeping each job in its own
 * folder is what lets {@link #clear} remove everything cached for one job.
 */
@Component
public class AnswerCache {

	private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]+");

	private final Path dir;

	private final JsonMapper canonical;

	public AnswerCache(TypeSafeProperties props, JsonMapper mapper) {
		this.dir = props.cacheDir();
		this.canonical = mapper.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
	}

	public String key(SystemOneRequest request) {
		// Round-trip through plain maps so every level of the JSON is key-sorted.
		byte[] json = canonical.writeValueAsBytes(canonical.convertValue(request, Object.class));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public Optional<String> get(String jobId, String key) {
		Path path = pathFor(jobId, key);
		if (!Files.exists(path)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readString(path));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public void put(String jobId, String key, String responseJson) {
		try {
			Path jobDir = Files.createDirectories(jobDir(jobId));
			Path tmp = Files.createTempFile(jobDir, key, ".tmp");
			Files.writeString(tmp, responseJson, StandardCharsets.UTF_8);
			Files.move(tmp, pathFor(jobId, key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** How many answers are cached for this job. */
	public int count(String jobId) {
		return answers(jobDir(jobId)).size();
	}

	/** Deletes every cached answer for this job and returns how many there were. Other jobs are untouched. */
	public int clear(String jobId) {
		List<Path> answers = answers(jobDir(jobId));
		try {
			for (Path answer : answers) {
				Files.deleteIfExists(answer);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return answers.size();
	}

	/** Answers in the old flat layout ({@code <cache-dir>/<key>.json}), from before answers were kept per job. */
	public List<Path> legacyAnswers() {
		return answers(dir);
	}

	/** Moves an answer from the old flat layout into its job's folder. */
	public void adopt(Path legacyAnswer, String jobId) {
		requireLegacy(legacyAnswer);
		try {
			String key = legacyAnswer.getFileName().toString().replaceFirst("\\.json$", "");
			Files.createDirectories(jobDir(jobId));
			Files.move(legacyAnswer, pathFor(jobId, key), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** Deletes an answer in the old flat layout. */
	public void deleteLegacy(Path legacyAnswer) {
		requireLegacy(legacyAnswer);
		try {
			Files.deleteIfExists(legacyAnswer);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private void requireLegacy(Path answer) {
		if (!dir.equals(answer.getParent()) || !answer.getFileName().toString().endsWith(".json")) {
			throw new IllegalArgumentException("Not a flat-layout answer in " + dir + ": " + answer);
		}
	}

	private static List<Path> answers(Path folder) {
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".json")).toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private Path jobDir(String jobId) {
		if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
			throw new IllegalArgumentException("Invalid job id for the answer cache: " + jobId);
		}
		return dir.resolve(jobId);
	}

	private Path pathFor(String jobId, String key) {
		return jobDir(jobId).resolve(key + ".json");
	}

}
