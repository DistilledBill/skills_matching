package com.example.resumescreening.web;

import java.nio.file.Files;
import java.nio.file.Path;

import com.example.resumescreening.TestAnswers;
import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.typesafe.MissingApiKeyException;
import com.example.resumescreening.typesafe.SystemOneRequest;
import com.example.resumescreening.typesafe.TypeSafeClient;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full application with only the TypeSafe HTTP call replaced. */
@SpringBootTest
@AutoConfigureMockMvc
class ScreeningApiTest {

	private static final String BASE = "/api/screenings/" + TestAnswers.JOB_ID;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	TypeSafeClient client;

	private final JobSpec job = TestAnswers.sampleJob();

	private static org.mockito.ArgumentMatcher<SystemOneRequest> resumeContaining(String text) {
		return request -> request != null && request.state().toString().contains(text);
	}

	@Test
	void listsJobs() throws Exception {
		mvc.perform(get("/api/jobs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(TestAnswers.JOB_ID))
			.andExpect(jsonPath("$[0].skills.length()").value(5));
	}

	@Test
	void unknownJobIs404() throws Exception {
		mvc.perform(post("/api/screenings/nope/folder")).andExpect(status().isNotFound());
	}

	@Test
	void folderOutsideResumesRootIsRejected() throws Exception {
		mvc.perform(post(BASE + "/folder").param("path", "../jobs"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(containsString("not a folder inside")));
		mvc.perform(post(BASE + "/folder").param("path", "/etc")).andExpect(status().isBadRequest());
		verifyNoInteractions(client);
	}

	@Test
	void unsupportedUploadIsRejected() throws Exception {
		mvc.perform(multipart(BASE).file(new MockMultipartFile("files", "resume.docx", null, new byte[] { 1 })))
			.andExpect(status().isBadRequest());
	}

	@Test
	void previewBuildsRequestWithoutCallingTypeSafe() throws Exception {
		mvc.perform(post(BASE + "/preview").param("path", TestAnswers.RESUMES_FOLDER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.model").value("jev-latest"))
			.andExpect(jsonPath("$.state.job.title").value("Senior Backend Engineer"))
			.andExpect(jsonPath("$.state.resume").value(startsWith("Candidate A\n[email] | [phone] | [link]")))
			.andExpect(jsonPath("$.questions.must_python_professional.type").value("noul"))
			.andExpect(jsonPath("$.questions.must_python_professional.instructions.question")
				.value("Does `resume` show evidence that the candidate meets `requirement`?"))
			.andExpect(jsonPath("$.questions.must_python_professional.criteria.true").exists())
			.andExpect(jsonPath("$.questions.skill_python_depth.type").value("score"))
			.andExpect(jsonPath("$.questions.skill_python_depth.criteria.length()").value(5))
			.andExpect(jsonPath("$.questions.length()").value(7));
		verifyNoInteractions(client);
	}

	@Test
	void screensFolderAndRanks() throws Exception {
		given(client.evaluate(any(), any())).willReturn(TestAnswers.strong(job).response());
		given(client.evaluate(any(), argThat(resumeContaining("Senior Java engineer"))))
			.willReturn(TestAnswers.strong(job).mustHave("python_professional", 0.02).response());
		given(client.evaluate(any(), argThat(resumeContaining("Full-stack developer"))))
			.willReturn(TestAnswers.strong(job).mustHave("backend_services", 0.6).score("python_depth", 1, 0.9)
				.response());

		mvc.perform(post(BASE + "/folder").param("path", TestAnswers.RESUMES_FOLDER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.candidates.length()").value(10))
			.andExpect(jsonPath("$.candidates[0].status").value("meets"))
			.andExpect(jsonPath("$.candidates[8].name").value("candidate_b"))
			.andExpect(jsonPath("$.candidates[8].status").value("review"))
			.andExpect(jsonPath("$.candidates[9].name").value("candidate_c"))
			.andExpect(jsonPath("$.candidates[9].status").value("missing"))
			.andExpect(jsonPath("$.candidates[9].rank").value(10))
			.andExpect(jsonPath("$.candidates[9].reasons[0]").value("not shown: python_professional (0.02)"));
	}

	@Test
	void screensUploadsAsCsv() throws Exception {
		given(client.evaluate(any(), any())).willReturn(TestAnswers.strong(job).response());

		mvc.perform(multipart(BASE).param("format", "csv")
			.file(new MockMultipartFile("files", "candidate_a.txt", "text/plain",
					Files.readAllBytes(Path.of("resumes/senior_backend_engineer_candidates/candidate_a.txt"))))
			.file(new MockMultipartFile("files", "candidate_d.txt", "text/plain",
					Files.readAllBytes(Path.of("resumes/senior_backend_engineer_candidates/candidate_d.txt")))))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/csv"))
			.andExpect(header().string("Content-Disposition", containsString(TestAnswers.JOB_ID + ".csv")))
			.andExpect(content().string(startsWith("rank,candidate,status,composite,reasons,must_python_professional")))
			.andExpect(content().string(containsString("1,candidate_a,meets,1.000,,0.950,0.950")));
	}

	@Test
	void missingApiKeyIs503() throws Exception {
		given(client.evaluate(any(), any())).willThrow(new MissingApiKeyException());

		mvc.perform(post(BASE + "/folder").param("path", TestAnswers.RESUMES_FOLDER))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.detail").value(containsString("TYPESAFE_API_KEY")));
	}

}
