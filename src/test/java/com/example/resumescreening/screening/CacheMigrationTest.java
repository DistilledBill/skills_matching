package com.example.resumescreening.screening;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.example.resumescreening.TestAnswers;
import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.job.JobSpecRepository;
import com.example.resumescreening.typesafe.AnswerCache;
import com.example.resumescreening.typesafe.TypeSafeClient;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/** The move from the flat cache layout to one folder per job. Uses its own temp cache folder. */
@SpringBootTest
class CacheMigrationTest {

	private static final Path CACHE_DIR = tempDir();

	@DynamicPropertySource
	static void cacheDir(DynamicPropertyRegistry registry) {
		registry.add("typesafe.cache-dir", CACHE_DIR::toString);
	}

	@Autowired
	CacheMigration migration;

	@Autowired
	AnswerCache cache;

	@Autowired
	ScreeningService screening;

	@Autowired
	JobSpecRepository jobs;

	@Autowired
	ResumeLoader loader;

	@MockitoBean
	TypeSafeClient client;

	@Test
	void movesMatchingAnswersIntoTheirJobFolderAndDeletesTheRest() throws IOException {
		JobSpec job = jobs.get(TestAnswers.JOB_ID);
		ResumeDocument candidateA = loader.fromFolder(TestAnswers.RESUMES_FOLDER).getFirst();
		String matched = cache.key(screening.request(job, candidateA));
		String unmatched = cache.key(screening.request(job, new ResumeDocument("upload", "An uploaded resume")));
		Files.writeString(CACHE_DIR.resolve(matched + ".json"), "{\"answer\":\"a\"}");
		Files.writeString(CACHE_DIR.resolve(unmatched + ".json"), "{}");

		CacheMigration.Result result = migration.migrate();

		assertThat(result.moved()).isEqualTo(Map.of(TestAnswers.JOB_ID, 1));
		assertThat(result.deleted()).isEqualTo(1);
		assertThat(cache.get(TestAnswers.JOB_ID, matched)).contains("{\"answer\":\"a\"}");
		assertThat(cache.legacyAnswers()).isEmpty();
		assertThat(CACHE_DIR.resolve(unmatched + ".json")).doesNotExist();

		assertThat(migration.migrate()).isEqualTo(new CacheMigration.Result(Map.of(), 0));
	}

	private static Path tempDir() {
		try {
			return Files.createTempDirectory("cache-migration-test");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
