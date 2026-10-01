package com.example.resumescreening.assist;

import java.util.List;
import java.util.Map;

import com.example.resumescreening.TestAnswers;
import com.example.resumescreening.job.JobSpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The prompts and the rule checks, with the Claude client mocked. */
class SpecAssistantTest {

	private final JsonMapper json = JsonMapper.builder().build();

	private final ClaudeClient claude = mock(ClaudeClient.class);

	private final SpecAssistant assistant = new SpecAssistant(claude, json);

	private final JobSpec job = TestAnswers.sampleJob();

	private JsonNode input(String value) {
		return json.readTree(value);
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> sentMessages(int call) {
		ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
		verify(claude, times(call)).callTool(anyString(), messages.capture(), any());
		return messages.getAllValues().get(call - 1);
	}

	@Test
	void levelsUseTheRequestedCountAndTheRulesAsTheSystemPrompt() {
		when(claude.callTool(anyString(), any(), any())).thenReturn(input("{\"levels\":[\"a\",\"b\",\"c\"]}"));

		assertThat(assistant.skillLevels(job, 0, 3).levels()).containsExactly("a", "b", "c");

		ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<ClaudeClient.Tool> tool = ArgumentCaptor.forClass(ClaudeClient.Tool.class);
		verify(claude).callTool(system.capture(), any(), tool.capture());
		assertThat(system.getValue()).contains("Levels run from least to most", "Does `resume` show evidence");
		assertThat(tool.getValue().name()).isEqualTo("suggest_levels");
		String prompt = (String) sentMessages(1).getFirst().get("content");
		assertThat(prompt).contains("exactly 3 levels", job.skills().getFirst().question(),
				"\"targetLevel\":\"Vice President\"");
	}

	@Test
	void retriesOnceWithTheProblemsWhenASuggestionBreaksTheRules() {
		when(claude.callTool(anyString(), any(), any())).thenReturn(input("{\"levels\":[\"a\",\"b\"]}"),
				input("{\"levels\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}"));

		assertThat(assistant.skillLevels(job, 0, 5).levels()).hasSize(5);

		List<Map<String, Object>> retry = sentMessages(2);
		assertThat(retry).hasSize(3);
		assertThat((String) retry.get(1).get("content")).contains("\"levels\":[\"a\",\"b\"]");
		assertThat((String) retry.get(2).get("content")).contains("there must be exactly 5 levels, not 2");
	}

	@Test
	void givesUpAfterTheRetryWithAClearMessage() {
		when(claude.callTool(anyString(), any(), any())).thenReturn(input("{\"levels\":[\"same\",\"same\"]}"));
		assertThatThrownBy(() -> assistant.skillLevels(job, 0, 2)).isInstanceOf(AssistException.class)
			.hasMessageContaining("two levels are the same");
		verify(claude, times(2)).callTool(anyString(), any(), any());
	}

	@Test
	void aNewSkillMustHaveAFreshSnakeCaseIdAndASensibleWeight() {
		when(claude.callTool(anyString(), any(), any())).thenReturn(
				input("{\"id\":\"python_depth\",\"question\":\"Q?\",\"levels\":[\"a\",\"b\"],\"weight\":2}"),
				input("{\"id\":\"on_call\",\"question\":\"How much on-call, based on `resume`?\","
						+ "\"levels\":[\"none\",\"led it\"],\"weight\":0.1}"));

		SpecAssistant.SkillSuggestion skill = assistant.newSkill(job, "running on-call for payments", 2);

		assertThat(skill.id()).isEqualTo("on_call");
		assertThat(skill.weight()).isEqualTo(0.1);
		assertThat(skill.warnings()).isEmpty();
		assertThat((String) sentMessages(2).get(2).get("content")).contains("already used by another skill",
				"the weight must be above 0 and at most 1");
	}

	@Test
	void warnsWhenAQuestionDoesNotNameTheResume() {
		when(claude.callTool(anyString(), any(), any())).thenReturn(input("{\"question\":\"How deep is it?\"}"));
		assertThat(assistant.skillQuestion(job, 1, "focus on Kafka").warnings()).singleElement()
			.asString().contains("doesn't name `resume`");
		assertThat((String) sentMessages(1).getFirst().get("content")).contains("The recruiter adds: focus on Kafka");
	}

	@Test
	void aRequirementMustBeAStatement() {
		when(claude.callTool(anyString(), any(), any())).thenReturn(input("{\"requirement\":\"Has Python?\"}"),
				input("{\"requirement\":\"Has used Python in a paid role\"}"));
		assertThat(assistant.mustHaveRequirement(job, 0, null).requirement()).isEqualTo("Has used Python in a paid role");
	}

	@Test
	void rejectsBadRequestsWithoutCallingClaude() {
		assertThatThrownBy(() -> assistant.skillLevels(job, 0, 11)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> assistant.skillLevels(job, 9, 5)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("no skill #10");
		JobSpec noQuestion = new JobSpec("x", "t", "l", "s", List.of(),
				List.of(new JobSpec.Skill("a", 1, "", List.of())), job.thresholds());
		assertThatThrownBy(() -> assistant.skillLevels(noQuestion, 0, 5)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("question first");
		assertThatThrownBy(() -> assistant.newSkill(job, " ", 5)).isInstanceOf(IllegalArgumentException.class);
		verify(claude, never()).callTool(anyString(), any(), any());
	}


	@Test
	@SuppressWarnings("unchecked")
	void asksToImproveExistingLevelsAndKeepsTheNoteOptional() {
		when(claude.callTool(anyString(), any(), any())).thenReturn(input("{\"levels\":[\"a\",\"b\",\"c\"]}"));
		assistant.skillLevels(job, 0, 3);

		String prompt = (String) sentMessages(1).getFirst().get("content");
		assertThat(prompt).contains("Its current levels are:\n0. " + job.skills().getFirst().levels().getFirst(),
				"Write an improved set", "Don't hand back the current levels");
		ArgumentCaptor<ClaudeClient.Tool> tool = ArgumentCaptor.forClass(ClaudeClient.Tool.class);
		verify(claude).callTool(anyString(), any(), tool.capture());
		assertThat((List<String>) tool.getValue().inputSchema().get("required")).containsExactly("levels");
		assertThat((Map<String, Object>) tool.getValue().inputSchema().get("properties")).containsKey("note");
	}

	@Test
	void flagsLevelsHandedBackUnchangedAndPassesOnClaudesNote() throws Exception {
		List<String> current = job.skills().get(1).levels();
		when(claude.callTool(anyString(), any(), any())).thenReturn(json.valueToTree(
				Map.of("levels", current, "note", "These levels already have clear, concrete boundaries.")));

		SpecAssistant.LevelsSuggestion same = assistant.skillLevels(job, 1, current.size());

		assertThat(same.warnings()).containsExactly("These are the current levels, unchanged.",
				"Claude: These levels already have clear, concrete boundaries.");
	}

	@Test
	void countsLevelsThatKeepTheirWording() {
		List<String> current = job.skills().get(1).levels();
		List<String> mixed = List.of(current.get(0), "New level one", "New level two", current.get(3).toUpperCase(),
				"New top level");
		when(claude.callTool(anyString(), any(), any())).thenReturn(json.valueToTree(Map.of("levels", mixed)));

		assertThat(assistant.skillLevels(job, 1, 5).warnings()).containsExactly("2 of 5 levels keep their current wording.");

		List<String> oneKept = List.of(current.get(0), "New one", "New two", "New three", "New four");
		when(claude.callTool(anyString(), any(), any())).thenReturn(json.valueToTree(Map.of("levels", oneKept)));
		assertThat(assistant.skillLevels(job, 1, 5).warnings()).containsExactly("1 of 5 levels keeps its current wording.");
	}

	@Test
	void flagsAQuestionOrRequirementHandedBackUnchanged() {
		String question = job.skills().get(3).question();
		String requirement = job.mustHaves().getFirst().requirement();
		when(claude.callTool(anyString(), any(), any())).thenReturn(
				json.valueToTree(Map.of("question", "  " + question.toUpperCase() + " ")),
				json.valueToTree(Map.of("requirement", requirement)));

		assertThat(assistant.skillQuestion(job, 3, null).warnings()).contains("This is the current question, unchanged.");
		assertThat(assistant.mustHaveRequirement(job, 0, null).warnings())
			.containsExactly("This is the current requirement, unchanged.");
		assertThat((String) sentMessages(2).getFirst().get("content")).contains("Its current requirement is: " + requirement,
				"Write an improved version");
	}

	@Test
	void draftsASummaryFromADescriptionWithTheSpecAsContext() {
		JobSpec blank = new JobSpec(job.id(), job.title(), job.targetLevel(), "", job.mustHaves(), job.skills(),
				job.thresholds());
		when(claude.callTool(anyString(), any(), any())).thenReturn(input("{\"summary\":\"Leads the platform.\"}"));

		SpecAssistant.SummarySuggestion summary = assistant.jobSummary(blank, "backend lead for payments");

		assertThat(summary.summary()).isEqualTo("Leads the platform.");
		assertThat(summary.warnings()).isEmpty();
		ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<ClaudeClient.Tool> tool = ArgumentCaptor.forClass(ClaudeClient.Tool.class);
		verify(claude).callTool(system.capture(), any(), tool.capture());
		assertThat(system.getValue()).contains("Writing the job summary", "Core Responsibilities");
		assertThat(tool.getValue().name()).isEqualTo("suggest_summary");
		assertThat((String) sentMessages(1).getFirst().get("content")).contains(
				"The recruiter describes the job as: backend lead for payments", "\"title\":\"" + job.title() + "\"")
			.doesNotContain("Its current summary");
	}

	@Test
	void improvesTheCurrentSummaryAndFlagsOneHandedBack() {
		when(claude.callTool(anyString(), any(), any())).thenReturn(
				json.valueToTree(Map.of("summary", job.summary(), "note", "It already covers the role.")));

		assertThat(assistant.jobSummary(job, null).warnings()).containsExactly("This is the current summary, unchanged.",
				"Claude: It already covers the role.");
		assertThat((String) sentMessages(1).getFirst().get("content")).contains("Its current summary is: ",
				"Write an improved version").doesNotContain("The recruiter describes");
	}

	@Test
	void aSummaryMustBeNonBlankAndShortEnoughToSendWithEveryResume() {
		String tooLong = "word ".repeat(SpecAssistant.MAX_SUMMARY_WORDS + 1);
		when(claude.callTool(anyString(), any(), any())).thenReturn(json.valueToTree(Map.of("summary", tooLong)),
				input("{\"summary\":\"  \"}"));

		assertThatThrownBy(() -> assistant.jobSummary(job, "shorter")).isInstanceOf(AssistException.class)
			.hasMessageContaining("the summary is empty");
		assertThat((String) sentMessages(2).get(2).get("content")).contains("the summary is 401 words; keep it to 400");

		JobSpec blank = new JobSpec("x", "t", "l", " ", List.of(), job.skills(), job.thresholds());
		assertThatThrownBy(() -> assistant.jobSummary(blank, " ")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Describe the job in a few words first.");
	}

}
