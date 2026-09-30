package com.example.resumescreening.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.resumescreening.TestAnswers;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Creating, saving, and reloading job specs. Uses temp jobs and resumes folders, never the checked-in ones. */
@SpringBootTest
@AutoConfigureMockMvc
class JobApiTest {

	private static final Path JOBS = tempDir("jobs");

	private static final Path RESUMES = tempDir("resumes");

	@DynamicPropertySource
	static void folders(DynamicPropertyRegistry registry) {
		try {
			Files.copy(Path.of("jobs", TestAnswers.JOB_ID + ".yaml"), JOBS.resolve(TestAnswers.JOB_ID + ".yaml"));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		registry.add("screening.jobs-dir", JOBS::toString);
		registry.add("screening.resumes-dir", RESUMES::toString);
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@MockitoBean
	TypeSafeClient client;

	private ObjectNode job(String id) throws Exception {
		String body = mvc.perform(get("/api/jobs")).andReturn().getResponse().getContentAsString();
		for (JsonNode node : json.readTree(body)) {
			if (node.get("id").asString().equals(id)) {
				return (ObjectNode) node;
			}
		}
		throw new AssertionError("no job " + id);
	}

	@Test
	void listsJobsWithTheirVersion() throws Exception {
		// The spec's fields sit at the top level, next to its version (not nested under "job").
		ObjectNode backend = job(TestAnswers.JOB_ID);
		assertThat(backend.get("targetLevel").asString()).isEqualTo("Vice President");
		assertThat(backend.get("version").asString()).hasSize(64);
		assertThat(backend.has("job")).isFalse();
	}

	@Test
	void createsAJobWithItsResumeFolder() throws Exception {
		ObjectNode spec = job(TestAnswers.JOB_ID);
		spec.remove("version");
		spec.put("id", "platform_director");
		spec.put("title", "Platform Director");

		mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(spec.toString()))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/jobs/platform_director"))
			.andExpect(jsonPath("$.id").value("platform_director"))
			.andExpect(jsonPath("$.version").isString());

		assertThat(JOBS.resolve("platform_director.yaml")).content().contains("title: Platform Director");
		assertThat(RESUMES.resolve("platform_director_candidates")).isDirectory();
		mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(spec.toString()))
			.andExpect(status().isConflict());
	}

	@Test
	void rejectsAnInvalidSpecWithEveryError() throws Exception {
		mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
			.content("{\"id\":\"bad job\",\"title\":\"\",\"skills\":[]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors", hasItem("title is required")))
			.andExpect(jsonPath("$.errors", hasItem("target_level is required")))
			.andExpect(jsonPath("$.errors", hasItem("at least one skill is required")))
			.andExpect(jsonPath("$.errors", hasItem(
					"the file name (the job id) may only use letters, digits, '_' and '-'")));
	}

	@Test
	void savesWithTheCurrentVersionAndRefusesAStaleOne() throws Exception {
		ObjectNode spec = job(TestAnswers.JOB_ID);
		String version = spec.remove("version").asString();
		spec.put("summary", "An edited summary.");
		String body = json.writeValueAsString(json.createObjectNode().put("version", version).set("spec", spec));

		mvc.perform(put("/api/jobs/" + TestAnswers.JOB_ID).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary").value("An edited summary."));
		mvc.perform(put("/api/jobs/" + TestAnswers.JOB_ID).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("changed on disk")));
		mvc.perform(put("/api/jobs/no_such_job").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isNotFound());
	}

	@Test
	void reloadsSpecsFromDisk() throws Exception {
		Path file = JOBS.resolve("reload_me.yaml");
		Files.writeString(file, Files.readString(JOBS.resolve(TestAnswers.JOB_ID + ".yaml"))
			.replaceFirst("title: .*", "title: Reload Me"));

		mvc.perform(post("/api/jobs/reload"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.loaded", hasItem("reload_me")));
		mvc.perform(post("/api/jobs/reload_me/reload"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Reload Me"));

		Files.writeString(file, "title: Broken\n");
		mvc.perform(post("/api/jobs/reload_me/reload"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors", hasItem("target_level is required")));
		mvc.perform(get("/api/jobs")).andExpect(jsonPath("$[?(@.id == 'reload_me')].title").value("Reload Me"));
		mvc.perform(post("/api/jobs/reload"))
			.andExpect(jsonPath("$.errors.reload_me", hasSize(org.hamcrest.Matchers.greaterThan(0))));
		Files.delete(file);
		mvc.perform(post("/api/jobs/reload")).andExpect(jsonPath("$.removed", hasItem("reload_me")));
		mvc.perform(post("/api/jobs/nope/reload")).andExpect(status().isNotFound());
	}

	private static Path tempDir(String prefix) {
		try {
			return Files.createTempDirectory("job-api-" + prefix);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
