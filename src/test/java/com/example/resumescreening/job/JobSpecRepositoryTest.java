package com.example.resumescreening.job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.resumescreening.config.ScreeningProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobSpecRepositoryTest {

	private static final String SPEC = """
			title: Test Role
			target_level: Vice President
			summary: Build things.
			must_haves: []
			skills:
			  - id: depth
			    weight: 1.0
			    question: How deep, based on `resume`?
			    levels: [none, some]
			thresholds:
			  must_have_pass: 0.8
			  must_have_fail: 0.2
			  min_confidence: 0.45
			""";

	@TempDir
	Path jobs;

	private JobSpecRepository load() {
		return new JobSpecRepository(new ScreeningProperties(jobs, Path.of("resumes")));
	}

	@Test
	void readsTargetLevel() throws IOException {
		Files.writeString(jobs.resolve("test_role.yaml"), SPEC);
		assertThat(load().get("test_role").targetLevel()).isEqualTo("Vice President");
	}

	@Test
	void targetLevelIsRequired() throws IOException {
		Files.writeString(jobs.resolve("test_role.yaml"), SPEC.replace("target_level: Vice President\n", ""));
		assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("test_role.yaml")
			.hasMessageContaining("target_level is required");
	}

	@Test
	void jobIdMustBeASafeFolderName() throws IOException {
		Files.writeString(jobs.resolve("bad name.yaml"), SPEC);
		assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("may only use letters, digits");
	}

}
