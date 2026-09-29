package com.example.resumescreening.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.resumescreening.TestAnswers;
import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.screening.ResumeDocument;
import com.example.resumescreening.screening.ScreeningService;
import com.example.resumescreening.typesafe.AnswerCache;
import com.example.resumescreening.typesafe.TypeSafeClient;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Endpoints added for the web app. Uses its own empty answer cache, never the project's .cache/. */
@SpringBootTest
@AutoConfigureMockMvc
class WebAppApiTest {

	private static final String BASE = "/api/screenings/" + TestAnswers.JOB_ID;

	private static final Path CACHE_DIR = tempDir();

	@DynamicPropertySource
	static void cacheDir(DynamicPropertyRegistry registry) {
		registry.add("typesafe.cache-dir", CACHE_DIR::toString);
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	ScreeningService screening;

	@Autowired
	AnswerCache cache;

	@MockitoBean
	TypeSafeClient client;

	private final JobSpec job = TestAnswers.sampleJob();

	@Test
	void listsResumeFolders() throws Exception {
		mvc.perform(get("/api/resume-folders"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.path == '%s')].fileCount", TestAnswers.RESUMES_FOLDER).value(10));
	}

	@Test
	void listsResumesInAFolder() throws Exception {
		mvc.perform(get("/api/resume-folders/" + TestAnswers.RESUMES_FOLDER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(10))
			.andExpect(jsonPath("$[0].fileName").value("candidate_a.txt"))
			.andExpect(jsonPath("$[0].name").value("candidate_a"))
			.andExpect(jsonPath("$[0].format").value("txt"));
		mvc.perform(get("/api/resume-folders/..")).andExpect(status().isBadRequest());
	}

	@Test
	void previewPicksResumeByName() throws Exception {
		mvc.perform(post(BASE + "/preview").param("path", TestAnswers.RESUMES_FOLDER).param("name", "candidate_c"))
			.andExpect(status().isOk())
			.andExpect(header().string(ScreeningController.CACHED_HEADER, "false"))
			.andExpect(jsonPath("$.state.resume").value(containsString("Senior Java engineer")))
			.andExpect(jsonPath("$.state.resume").value(not(containsString("@example.com"))));
		verifyNoInteractions(client);
	}

	@Test
	void previewOfUnknownNameIs400() throws Exception {
		mvc.perform(post(BASE + "/preview").param("path", TestAnswers.RESUMES_FOLDER).param("name", "nobody"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(containsString("nobody")));
	}

	@Test
	void previewTextMatchesPreviewOfTheSavedFile() throws Exception {
		String text = Files.readString(Path.of("resumes", TestAnswers.RESUMES_FOLDER, "candidate_a.txt"));
		String fromFile = mvc
			.perform(post(BASE + "/preview").param("path", TestAnswers.RESUMES_FOLDER).param("name", "candidate_a"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		mvc.perform(post(BASE + "/preview-text").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"candidate_a\",\"text\":" + json(text) + "}"))
			.andExpect(status().isOk())
			.andExpect(content().json(fromFile))
			.andExpect(jsonPath("$.state.resume").value(containsString("[email] | [phone] | [link]")))
			.andExpect(jsonPath("$.questions.length()").value(7));
		verifyNoInteractions(client);
	}

	@Test
	void previewTextRejectsEmptyText() throws Exception {
		mvc.perform(post(BASE + "/preview-text").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"x\",\"text\":\"  \"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void reportsCachedAnswers() throws Exception {
		mvc.perform(post(BASE + "/cache-status").param("path", TestAnswers.RESUMES_FOLDER))
			.andExpect(jsonPath("$.total").value(10))
			.andExpect(jsonPath("$.cached").value(0));

		cache.put(TestAnswers.JOB_ID,
				cache.key(screening.request(job, new ResumeDocument("x", "Only resume in the cache"))), "{}");
		cache.put(TestAnswers.JOB_ID, cache.key(screening.request(job, candidate("candidate_b"))), "{}");

		mvc.perform(post(BASE + "/preview").param("path", TestAnswers.RESUMES_FOLDER).param("name", "candidate_b"))
			.andExpect(header().string(ScreeningController.CACHED_HEADER, "true"));
		mvc.perform(post(BASE + "/cache-status").param("path", TestAnswers.RESUMES_FOLDER))
			.andExpect(jsonPath("$.total").value(10))
			.andExpect(jsonPath("$.cached").value(1));
		verifyNoInteractions(client);
	}

	@Test
	void countsAndClearsEverythingCachedForOneJob() throws Exception {
		String otherJob = "technical_product_owner";
		int before = cache.count(TestAnswers.JOB_ID);
		int otherBefore = cache.count(otherJob);
		cache.put(TestAnswers.JOB_ID, "a1", "{}");
		cache.put(TestAnswers.JOB_ID, "a2", "{}");
		cache.put(otherJob, "b1", "{}");

		mvc.perform(get(BASE + "/cache")).andExpect(jsonPath("$.count").value(before + 2));
		mvc.perform(delete(BASE + "/cache"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.removed").value(before + 2));
		mvc.perform(get(BASE + "/cache")).andExpect(jsonPath("$.count").value(0));
		assertThat(cache.count(otherJob)).isEqualTo(otherBefore + 1);

		mvc.perform(delete("/api/screenings/no_such_job/cache")).andExpect(status().isNotFound());
		verifyNoInteractions(client);
	}

	@Test
	void pageUrlsServeTheAppButApiAndFilesDoNot() throws Exception {
		mvc.perform(get("/jobs/senior_backend_engineer/results"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("test-index")));
		mvc.perform(get("/api/no-such-endpoint")).andExpect(status().isNotFound());
		mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
	}

	private static ResumeDocument candidate(String name) throws IOException {
		return new ResumeDocument(name,
				Files.readString(Path.of("resumes", TestAnswers.RESUMES_FOLDER, name + ".txt")));
	}

	private static String json(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	private static Path tempDir() {
		try {
			return Files.createTempDirectory("answer-cache-test");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
