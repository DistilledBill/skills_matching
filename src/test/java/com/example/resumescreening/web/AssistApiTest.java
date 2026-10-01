package com.example.resumescreening.web;

import com.example.resumescreening.TestAnswers;
import com.example.resumescreening.assist.AssistUnavailableException;
import com.example.resumescreening.assist.ClaudeClient;
import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.typesafe.TypeSafeClient;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The suggestion endpoints, with the Claude client mocked: no test ever calls the real API. */
@SpringBootTest
@AutoConfigureMockMvc
class AssistApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@MockitoBean
	ClaudeClient claude;

	@MockitoBean
	TypeSafeClient typeSafe;

	private final JobSpec job = TestAnswers.sampleJob();

	private String body(Integer index, String hint, Integer count) {
		return json.writeValueAsString(new AssistController.Request(job, index, hint, count));
	}

	@Test
	void reportsWhetherSuggestionsAreOn() throws Exception {
		given(claude.enabled()).willReturn(false);
		given(claude.model()).willReturn("claude-sonnet-5");
		mvc.perform(get("/api/assist"))
			.andExpect(jsonPath("$.enabled").value(false))
			.andExpect(jsonPath("$.model").value("claude-sonnet-5"));
	}

	@Test
	void suggestsLevelsWithFiveByDefault() throws Exception {
		given(claude.callTool(anyString(), any(), any()))
			.willReturn(json.readTree("{\"levels\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}"));
		mvc.perform(post("/api/assist/skill-levels").contentType(MediaType.APPLICATION_JSON).content(body(0, null, null)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.levels.length()").value(5));
		verifyNoInteractions(typeSafe);
	}

	@Test
	void suggestsAQuestionARequirementAndANewSkill() throws Exception {
		given(claude.callTool(anyString(), any(), any())).willReturn(
				json.readTree("{\"question\":\"How deep, based on `resume`?\"}"),
				json.readTree("{\"requirement\":\"Has shipped Python services\"}"),
				json.readTree("{\"id\":\"on_call\",\"question\":\"How much on-call, based on `resume`?\","
						+ "\"levels\":[\"none\",\"some\",\"led it\"],\"weight\":0.1}"));
		mvc.perform(post("/api/assist/skill-question").contentType(MediaType.APPLICATION_JSON).content(body(1, null, null)))
			.andExpect(jsonPath("$.question").value("How deep, based on `resume`?"))
			.andExpect(jsonPath("$.warnings.length()").value(0));
		mvc.perform(post("/api/assist/must-have").contentType(MediaType.APPLICATION_JSON).content(body(0, "tighter", null)))
			.andExpect(jsonPath("$.requirement").value("Has shipped Python services"));
		mvc.perform(post("/api/assist/skill").contentType(MediaType.APPLICATION_JSON).content(body(null, "on-call", 3)))
			.andExpect(jsonPath("$.id").value("on_call"))
			.andExpect(jsonPath("$.levels.length()").value(3))
			.andExpect(jsonPath("$.weight").value(0.1));
	}

	@Test
	void draftsTheSummaryFromADescription() throws Exception {
		given(claude.callTool(anyString(), any(), any())).willReturn(json.readTree("{\"summary\":\"Leads backend.\"}"));
		mvc.perform(post("/api/assist/summary").contentType(MediaType.APPLICATION_JSON).content(body(null, "backend", null)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary").value("Leads backend."));

		JobSpec blank = new JobSpec(job.id(), job.title(), job.targetLevel(), "", job.mustHaves(), job.skills(),
				job.thresholds());
		mvc.perform(post("/api/assist/summary").contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(new AssistController.Request(blank, null, null, null))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("Describe the job in a few words first."));
	}

	@Test
	void badRequestsAre400AndAMissingKeyIs503() throws Exception {
		mvc.perform(post("/api/assist/skill-levels").contentType(MediaType.APPLICATION_JSON).content(body(0, null, 1)))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/assist/skill-question").contentType(MediaType.APPLICATION_JSON).content(body(null, null, null)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("Say which item to suggest for ('index')."));

		given(claude.callTool(anyString(), any(), any())).willThrow(new AssistUnavailableException());
		mvc.perform(post("/api/assist/skill-question").contentType(MediaType.APPLICATION_JSON).content(body(0, null, null)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ANTHROPIC_API_KEY")));
	}

}
