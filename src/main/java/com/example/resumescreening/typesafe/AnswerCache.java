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
import java.util.Optional;

import com.example.resumescreening.config.TypeSafeProperties;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * File cache of raw System One responses, keyed by a hash of the full request
 * (model, state, and questions). Weights and thresholds are not part of the
 * request, so changing them re-ranks from cache; changing a question re-asks.
 */
@Component
public class AnswerCache {

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

	public Optional<String> get(String key) {
		Path path = pathFor(key);
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

	public void put(String key, String responseJson) {
		try {
			Files.createDirectories(dir);
			Path tmp = Files.createTempFile(dir, key, ".tmp");
			Files.writeString(tmp, responseJson, StandardCharsets.UTF_8);
			Files.move(tmp, pathFor(key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private Path pathFor(String key) {
		return dir.resolve(key + ".json");
	}

}
