package com.example.resumescreening.web;

import com.example.resumescreening.assist.SpecAssistant;
import com.example.resumescreening.job.JobSpec;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Claude suggestions for the spec editor. Each call sends the editor's unsaved draft, never resumes, and costs
 * one small Claude API call (two if the first answer breaks the spec rules).
 */
@RestController
@RequestMapping("/api/assist")
public class AssistController {

	private static final int DEFAULT_LEVELS = 5;

	private final SpecAssistant assistant;

	public AssistController(SpecAssistant assistant) {
		this.assistant = assistant;
	}

	/** Whether suggestions are available (an Anthropic API key is set), and which model writes them. */
	@GetMapping
	public Status status() {
		return new Status(assistant.enabled(), assistant.model());
	}

	@PostMapping("/summary")
	public SpecAssistant.SummarySuggestion summary(@RequestBody Request body) {
		return assistant.jobSummary(body.draft(), body.hint());
	}

	@PostMapping("/skill-question")
	public SpecAssistant.QuestionSuggestion skillQuestion(@RequestBody Request body) {
		return assistant.skillQuestion(body.draft(), body.item(), body.hint());
	}

	@PostMapping("/skill-levels")
	public SpecAssistant.LevelsSuggestion skillLevels(@RequestBody Request body) {
		return assistant.skillLevels(body.draft(), body.item(), body.levels());
	}

	@PostMapping("/must-have")
	public SpecAssistant.RequirementSuggestion mustHave(@RequestBody Request body) {
		return assistant.mustHaveRequirement(body.draft(), body.item(), body.hint());
	}

	@PostMapping("/skill")
	public SpecAssistant.SkillSuggestion skill(@RequestBody Request body) {
		return assistant.newSkill(body.draft(), body.hint(), body.levels());
	}

	public record Status(boolean enabled, String model) {
	}

	/**
	 * @param draft the editor's unsaved spec
	 * @param index which skill or must-have (0-based); unused for a new skill or the summary
	 * @param hint  optional guidance; for a new skill or the summary, the description
	 * @param count how many levels to write; 5 when left out
	 */
	public record Request(JobSpec draft, Integer index, String hint, Integer count) {

		int levels() {
			return count == null ? DEFAULT_LEVELS : count;
		}

		int item() {
			if (index == null) {
				throw new IllegalArgumentException("Say which item to suggest for ('index').");
			}
			return index;
		}

	}

}
