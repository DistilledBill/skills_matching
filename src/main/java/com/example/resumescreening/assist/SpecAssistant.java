package com.example.resumescreening.assist;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.example.resumescreening.job.JobSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Drafts parts of a job spec with Claude: a skill's question, a skill's levels, a must-have's requirement, or
 * a whole new skill. Claude gets the spec-writing rules and the editor's unsaved draft (never resumes). Each
 * suggestion is checked against the same rules the spec validator uses; one that fails is retried once with
 * the problems spelled out.
 */
@Service
public class SpecAssistant {

	static final int MIN_LEVELS = 2;

	static final int MAX_LEVELS = 10;

	private static final String ID_PATTERN = "[a-z0-9_]+";

	/** Optional tool field where Claude explains why it kept the current text. */
	private static final String NOTE = "note";

	private static final Map<String, Object> NOTE_SCHEMA = Map.of("type", "string", "description",
			"Only if you could not improve on the current text: one sentence on why.");

	private final ClaudeClient claude;

	private final JsonMapper mapper;

	private final String rules;

	public record QuestionSuggestion(String question, List<String> warnings) {
	}

	public record LevelsSuggestion(List<String> levels, List<String> warnings) {
	}

	public record RequirementSuggestion(String requirement, List<String> warnings) {
	}

	public record SkillSuggestion(String id, String question, List<String> levels, double weight,
			List<String> warnings) {
	}

	public SpecAssistant(ClaudeClient claude, JsonMapper mapper) {
		this.claude = claude;
		this.mapper = mapper;
		try {
			this.rules = new ClassPathResource("assist/spec-writing-rules.md").getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public boolean enabled() {
		return claude.enabled();
	}

	public String model() {
		return claude.model();
	}

	public QuestionSuggestion skillQuestion(JobSpec draft, int index, String hint) {
		JobSpec.Skill skill = skill(draft, index);
		String current = skill.question();
		String task = "Write the question for skill #" + (index + 1) + describe(skill.id(), "id")
				+ ". It should ask how much or how deep the candidate's experience is, based on `resume`."
				+ improveOn("question", current) + hintLine(hint);
		return ask(draft, task, tool("suggest_question", "The skill's question.",
				Map.of("question", Map.of("type", "string"), NOTE, NOTE_SCHEMA)), input -> {
					String question = input.path("question").asString("").strip();
					List<String> problems = new ArrayList<>();
					if (question.isEmpty()) {
						problems.add("the question is empty");
					}
					List<String> warnings = new ArrayList<>(questionWarnings(question));
					warnings.addAll(unchanged(input, same(question, current), "This is the current question, unchanged."));
					return new Checked<>(new QuestionSuggestion(question, warnings), problems);
				});
	}

	public LevelsSuggestion skillLevels(JobSpec draft, int index, int count) {
		JobSpec.Skill skill = skill(draft, index);
		requireCount(count);
		if (!StringUtils.hasText(skill.question())) {
			throw new IllegalArgumentException("Write or suggest the skill's question first; levels answer it.");
		}
		List<String> current = skill.levels() == null ? List.of()
				: skill.levels().stream().filter(StringUtils::hasText).toList();
		String task = "Write exactly " + count + " levels, least to most, for skill #" + (index + 1)
				+ describe(skill.id(), "id") + " whose question is: " + skill.question()
				+ (current.isEmpty() ? ""
						: "\n\nIts current levels are:\n" + numbered(current) + "\nWrite an improved set: sharper, "
								+ "non-overlapping boundaries between levels, each a concrete situation, pitched at the "
								+ "role's target level. Don't hand back the current levels; keep a level's wording only "
								+ "where it can't be improved. If the current set is already as good as it can be, "
								+ "say why in `note`.");
		return ask(draft, task, tool("suggest_levels", "The skill's levels, from least to most.",
				Map.of("levels", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", count,
						"maxItems", count), NOTE, NOTE_SCHEMA)),
				input -> {
					List<String> levels = strings(input.path("levels"));
					long kept = levels.stream().filter(l -> current.stream().anyMatch(c -> same(l, c))).count();
					List<String> warnings = new ArrayList<>(unchanged(input,
							!current.isEmpty() && kept == levels.size(), "These are the current levels, unchanged."));
					if (kept > 0 && kept < levels.size()) {
						warnings.add(kept == 1 ? "1 of " + levels.size() + " levels keeps its current wording."
								: kept + " of " + levels.size() + " levels keep their current wording.");
					}
					return new Checked<>(new LevelsSuggestion(levels, warnings), levelProblems(levels, count));
				});
	}

	public RequirementSuggestion mustHaveRequirement(JobSpec draft, int index, String hint) {
		JobSpec.MustHave mustHave = mustHave(draft, index);
		String current = mustHave.requirement();
		String task = "Write the requirement for must-have #" + (index + 1) + describe(mustHave.id(), "id")
				+ ". It must be a clear yes/no gate provable from a resume, written as a statement."
				+ improveOn("requirement", current) + hintLine(hint);
		return ask(draft, task, tool("suggest_requirement", "The must-have's requirement.",
				Map.of("requirement", Map.of("type", "string"), NOTE, NOTE_SCHEMA)), input -> {
					String requirement = input.path("requirement").asString("").strip();
					List<String> problems = new ArrayList<>();
					if (requirement.isEmpty()) {
						problems.add("the requirement is empty");
					}
					if (requirement.endsWith("?")) {
						problems.add("the requirement must be a statement, not a question");
					}
					return new Checked<>(new RequirementSuggestion(requirement,
							unchanged(input, same(requirement, current), "This is the current requirement, unchanged.")),
							problems);
				});
	}

	public SkillSuggestion newSkill(JobSpec draft, String description, int count) {
		requireCount(count);
		if (!StringUtils.hasText(description)) {
			throw new IllegalArgumentException("Describe the skill in a few words first.");
		}
		String task = "Draft one new skill for this spec, about: " + description.strip() + ". Give it a snake_case id "
				+ "not used by any existing skill, a question based on `resume`, exactly " + count
				+ " levels from least to most, and a weight between 0.05 and 0.5 that reflects how much it "
				+ "should count next to the existing skills.";
		Map<String, Object> props = Map.of("id", Map.of("type", "string"), "question", Map.of("type", "string"),
				"levels", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", count, "maxItems", count),
				"weight", Map.of("type", "number"));
		return ask(draft, task, tool("suggest_skill", "A complete new skill.", props), input -> {
			String id = input.path("id").asString("").strip();
			String question = input.path("question").asString("").strip();
			List<String> levels = strings(input.path("levels"));
			double weight = input.path("weight").asDouble(0);
			List<String> problems = new ArrayList<>(levelProblems(levels, count));
			if (!id.matches(ID_PATTERN)) {
				problems.add("the id must use lowercase letters, digits and '_'");
			}
			if (skills(draft).stream().anyMatch(s -> id.equals(s.id()))) {
				problems.add("the id " + id + " is already used by another skill");
			}
			if (question.isEmpty()) {
				problems.add("the question is empty");
			}
			if (!(weight > 0 && weight <= 1)) {
				problems.add("the weight must be above 0 and at most 1");
			}
			return new Checked<>(new SkillSuggestion(id, question, levels, weight, questionWarnings(question)),
					problems);
		});
	}

	/** A suggestion plus the problems that would make the spec invalid. */
	private record Checked<T>(T value, List<String> problems) {
	}

	private <T> T ask(JobSpec draft, String task, ClaudeClient.Tool tool, Function<JsonNode, Checked<T>> check) {
		String prompt = "The job spec so far, as JSON (it may be incomplete):\n" + mapper.writeValueAsString(draft)
				+ "\n\n" + task;
		List<Map<String, Object>> messages = new ArrayList<>(List.of(Map.of("role", "user", "content", prompt)));
		JsonNode first = claude.callTool(rules, messages, tool);
		Checked<T> result = check.apply(first);
		if (result.problems().isEmpty()) {
			return result.value();
		}
		// One retry, showing Claude its suggestion and exactly what was wrong with it.
		messages.add(Map.of("role", "assistant", "content", "My suggestion: " + first));
		messages.add(Map.of("role", "user", "content", "That suggestion can't be used: "
				+ String.join("; ", result.problems()) + ". Please try again, following the rules."));
		result = check.apply(claude.callTool(rules, messages, tool));
		if (!result.problems().isEmpty()) {
			throw new AssistException("Claude's suggestion didn't pass the spec checks: "
					+ String.join("; ", result.problems()) + ". Try again, or write it yourself.");
		}
		return result.value();
	}

	/** Every property is required except the optional {@code note}. */
	private static ClaudeClient.Tool tool(String name, String description, Map<String, Object> properties) {
		List<String> required = properties.keySet().stream().filter(k -> !k.equals(NOTE)).sorted().toList();
		return new ClaudeClient.Tool(name, description,
				Map.of("type", "object", "properties", properties, "required", required));
	}

	/** The instruction for a field that already has text: improve it, don't copy it. */
	private static String improveOn(String field, String current) {
		if (!StringUtils.hasText(current)) {
			return "";
		}
		return "\n\nIts current " + field + " is: " + current.strip() + "\nWrite an improved version: clearer, more "
				+ "specific, and closer to the rules, keeping any detail that makes it better. Don't hand back the "
				+ "current " + field + ". If it is already as good as it can be, return it unchanged and say why in "
				+ "`note`.";
	}

	/**
	 * Warnings for a suggestion that repeats the current text, plus Claude's own {@code note} when it gave one,
	 * so the editor never presents a copy of what's already there as something new.
	 */
	private static List<String> unchanged(JsonNode input, boolean isUnchanged, String message) {
		List<String> warnings = new ArrayList<>();
		if (isUnchanged) {
			warnings.add(message);
		}
		String note = input.path(NOTE).asString("").strip();
		if (!note.isEmpty()) {
			warnings.add("Claude: " + note);
		}
		return warnings;
	}

	/** The same text, ignoring case and spacing. */
	private static boolean same(String a, String b) {
		return a != null && b != null && normalize(a).equals(normalize(b));
	}

	private static String normalize(String text) {
		return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private static String numbered(List<String> levels) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < levels.size(); i++) {
			out.append(i).append(". ").append(levels.get(i)).append('\n');
		}
		return out.toString();
	}

	private static List<String> levelProblems(List<String> levels, int count) {
		List<String> problems = new ArrayList<>();
		if (levels.size() != count) {
			problems.add("there must be exactly " + count + " levels, not " + levels.size());
		}
		if (levels.stream().anyMatch(l -> !StringUtils.hasText(l))) {
			problems.add("a level is empty");
		}
		if (new HashSet<>(levels).size() != levels.size()) {
			problems.add("two levels are the same");
		}
		return problems;
	}

	/** Advice rather than errors: the spec is still valid without them. */
	private static List<String> questionWarnings(String question) {
		return question.contains("`resume`") ? List.of()
				: List.of("The question doesn't name `resume`, so Jev may not know what to judge.");
	}

	private static List<String> strings(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(n -> values.add(n.asString("").strip()));
		return values;
	}

	private static void requireCount(int count) {
		if (count < MIN_LEVELS || count > MAX_LEVELS) {
			throw new IllegalArgumentException("A skill needs " + MIN_LEVELS + " to " + MAX_LEVELS + " levels.");
		}
	}

	private static JobSpec.Skill skill(JobSpec draft, int index) {
		List<JobSpec.Skill> skills = skills(draft);
		if (index < 0 || index >= skills.size()) {
			throw new IllegalArgumentException("There is no skill #" + (index + 1) + " in the draft.");
		}
		return skills.get(index);
	}

	private static List<JobSpec.Skill> skills(JobSpec draft) {
		return draft == null || draft.skills() == null ? List.of() : draft.skills();
	}

	private static JobSpec.MustHave mustHave(JobSpec draft, int index) {
		List<JobSpec.MustHave> mustHaves = draft == null || draft.mustHaves() == null ? List.of() : draft.mustHaves();
		if (index < 0 || index >= mustHaves.size()) {
			throw new IllegalArgumentException("There is no must-have #" + (index + 1) + " in the draft.");
		}
		return mustHaves.get(index);
	}

	private static String describe(String id, String label) {
		return StringUtils.hasText(id) ? " (" + label + " " + id + ")" : "";
	}

	private static String hintLine(String hint) {
		return StringUtils.hasText(hint) ? " The recruiter adds: " + hint.strip() : "";
	}

}
