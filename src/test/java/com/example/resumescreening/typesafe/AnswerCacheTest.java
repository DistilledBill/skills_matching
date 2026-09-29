package com.example.resumescreening.typesafe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.resumescreening.config.TypeSafeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerCacheTest {

	@TempDir
	Path dir;

	private AnswerCache cache() {
		return new AnswerCache(new TypeSafeProperties(null, null, null, 1, 1, null, null, dir),
				JsonMapper.builder().build());
	}

	@Test
	void keepsEachJobInItsOwnFolder() {
		AnswerCache cache = cache();
		cache.put("job_a", "k1", "{\"a\":1}");
		cache.put("job_b", "k1", "{\"b\":1}");

		assertThat(dir.resolve("job_a/k1.json")).hasContent("{\"a\":1}");
		assertThat(cache.get("job_a", "k1")).contains("{\"a\":1}");
		assertThat(cache.get("job_b", "k1")).contains("{\"b\":1}");
		assertThat(cache.get("job_c", "k1")).isEmpty();
	}

	@Test
	void clearRemovesOnlyThatJobsAnswers() {
		AnswerCache cache = cache();
		cache.put("job_a", "k1", "{}");
		cache.put("job_a", "k2", "{}");
		cache.put("job_b", "k1", "{}");

		assertThat(cache.count("job_a")).isEqualTo(2);
		assertThat(cache.clear("job_a")).isEqualTo(2);
		assertThat(cache.count("job_a")).isZero();
		assertThat(cache.get("job_b", "k1")).isPresent();
		assertThat(cache.clear("never_cached")).isZero();
	}

	@Test
	void rejectsJobIdsThatAreNotPlainFolderNames() {
		AnswerCache cache = cache();
		for (String bad : new String[] { "..", "../x", "a/b", "", "a.b" }) {
			assertThatThrownBy(() -> cache.get(bad, "k")).as(bad).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void adoptsAndDeletesOnlyFlatLayoutAnswers() throws IOException {
		AnswerCache cache = cache();
		Path keep = Files.writeString(dir.resolve("k1.json"), "{}");
		Path drop = Files.writeString(dir.resolve("k2.json"), "{}");
		cache.put("job_a", "k3", "{}");

		assertThat(cache.legacyAnswers()).containsExactlyInAnyOrder(keep, drop);
		cache.adopt(keep, "job_a");
		cache.deleteLegacy(drop);

		assertThat(cache.legacyAnswers()).isEmpty();
		assertThat(cache.get("job_a", "k1")).contains("{}");
		assertThat(drop).doesNotExist();
		assertThatThrownBy(() -> cache.deleteLegacy(dir.resolve("job_a/k3.json")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(cache.get("job_a", "k3")).isPresent();
	}

}
