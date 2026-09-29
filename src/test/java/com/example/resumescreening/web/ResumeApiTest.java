package com.example.resumescreening.web;

import java.nio.file.Files;
import java.nio.file.Path;

import com.example.resumescreening.TestAnswers;
import com.example.resumescreening.typesafe.TypeSafeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reading resumes for the viewer. Reads the checked-in sample resumes and never writes. */
@SpringBootTest
@AutoConfigureMockMvc
class ResumeApiTest {

	private static final String FOLDER = "/api/resume-folders/" + TestAnswers.RESUMES_FOLDER;

	private static final String EMAIL = "a.candidate@example.com";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	TypeSafeClient client;

	@AfterEach
	void neverCallsTypeSafe() {
		verifyNoInteractions(client);
	}

	@Test
	void readsResumeWithContactDetailsWhenNotRedacted() throws Exception {
		mvc.perform(get(FOLDER + "/resumes/candidate_a.txt").param("redacted", "false"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fileName").value("candidate_a.txt"))
			.andExpect(jsonPath("$.name").value("candidate_a"))
			.andExpect(jsonPath("$.format").value("txt"))
			.andExpect(jsonPath("$.text", containsString(EMAIL)))
			.andExpect(jsonPath("$.text", containsString("(555) 201-3344")));
	}

	@Test
	void readsResumeAsJevSeesItWhenRedacted() throws Exception {
		for (var request : new RequestBuilder[] {
				get(FOLDER + "/resumes/candidate_a.txt").param("redacted", "true"),
				get(FOLDER + "/resumes/candidate_a.txt") }) {
			mvc.perform(request)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.text", containsString("[email]")))
				.andExpect(jsonPath("$.text", containsString("[phone]")))
				.andExpect(jsonPath("$.text", not(containsString(EMAIL))));
		}
	}

	@Test
	void servesTheStoredFileByteForByte() throws Exception {
		byte[] stored = Files.readAllBytes(Path.of("resumes", TestAnswers.RESUMES_FOLDER, "candidate_a.txt"));
		mvc.perform(get(FOLDER + "/files/candidate_a.txt"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
			.andExpect(header().string("Content-Disposition", containsString("inline")))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(content().bytes(stored));
	}

	@Test
	void readsUploadedResumesInBothModes() throws Exception {
		MockMultipartFile file = new MockMultipartFile("files", "cv.txt", "text/plain",
				("Candidate U\n" + EMAIL + " | (555) 201-3344\n").getBytes());
		mvc.perform(multipart("/api/resumes/text").file(file).param("redacted", "false"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].fileName").value("cv.txt"))
			.andExpect(jsonPath("$[0].name").value("cv"))
			.andExpect(jsonPath("$[0].text", containsString(EMAIL)));
		mvc.perform(multipart("/api/resumes/text").file(file))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].text", containsString("[email]")))
			.andExpect(jsonPath("$[0].text", not(containsString(EMAIL))));
	}

	@Test
	void rejectsBadNamesAndReportsMissingFiles() throws Exception {
		mvc.perform(get(FOLDER + "/resumes/notes.docx")).andExpect(status().isBadRequest());
		mvc.perform(get(FOLDER + "/files/notes.docx")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/resume-folders/../resumes/candidate_a.txt")).andExpect(status().is4xxClientError());
		mvc.perform(get("/api/resume-folders/{folder}/resumes/{file}", "..", "candidate_a.txt"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/resume-folders/{folder}/resumes/{file}", TestAnswers.RESUMES_FOLDER, "..\\x.txt"))
			.andExpect(status().isBadRequest());
		mvc.perform(get(FOLDER + "/resumes/nobody.txt"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail", containsString("nobody.txt")));
		mvc.perform(get(FOLDER + "/files/nobody.pdf")).andExpect(status().isNotFound());
	}

}
