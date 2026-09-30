package com.example.resumescreening.job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.resumescreening.config.ScreeningProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Everything here runs against a temp jobs folder, never the checked-in specs. */
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

	private JobSpecRepository loadWith(String id, String yaml) throws IOException {
		Files.writeString(jobs.resolve(id + ".yaml"), yaml);
		return load();
	}

	@Test
	void readsTargetLevel() throws IOException {
		assertThat(loadWith("test_role", SPEC).get("test_role").targetLevel()).isEqualTo("Vice President");
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

	@Test
	void validateListsEveryProblem() {
		JobSpec bad = new JobSpec("ok_id", " ", null, "s", List.of(new JobSpec.MustHave("Bad-Id", "")),
				List.of(new JobSpec.Skill("depth", 0, "", List.of("only one"))),
				new JobSpec.Thresholds(0.2, 0.8, 0.45));
		assertThat(JobSpecRepository.validate(bad)).containsExactly("title is required", "target_level is required",
				"must_have id Bad-Id may only use lowercase letters, digits and '_'",
				"must_have Bad-Id needs a requirement", "skill depth needs a positive weight",
				"skill depth needs a question", "skill depth needs 2 to 10 levels",
				"thresholds need 0 <= must_have_fail <= must_have_pass <= 1");
	}

	@Test
	void createsANewSpecFile() throws IOException {
		JobSpecRepository repo = loadWith("test_role", SPEC);
		JobSpec copy = repo.get("test_role").withId("new_role");

		VersionedJobSpec created = repo.create(copy);

		assertThat(jobs.resolve("new_role.yaml")).exists();
		assertThat(repo.get("new_role")).isEqualTo(copy);
		assertThat(created.version()).hasSize(64);
		assertThatThrownBy(() -> repo.create(copy)).isInstanceOf(JobSpecConflictException.class);
	}

	@Test
	void rejectsAnInvalidSpecWithEveryError() throws IOException {
		JobSpecRepository repo = loadWith("test_role", SPEC);
		JobSpec noTitle = new JobSpec("x", "", "", "s", List.of(), List.of(), null);
		assertThatThrownBy(() -> repo.create(noTitle)).isInstanceOfSatisfying(InvalidJobSpecException.class,
				ex -> assertThat(ex.errors()).contains("title is required", "target_level is required",
						"at least one skill is required", "thresholds is required"));
		assertThat(jobs.resolve("x.yaml")).doesNotExist();
	}

	@Test
	void savesWhenTheVersionMatchesAndRefusesWhenTheFileChanged() throws IOException {
		JobSpecRepository repo = loadWith("test_role", SPEC);
		String version = repo.versioned("test_role").version();
		JobSpec edited = new JobSpec(null, "Edited Role", "Director", "New summary.", List.of(),
				repo.get("test_role").skills(), repo.get("test_role").thresholds());

		VersionedJobSpec saved = repo.update("test_role", edited, version);
		assertThat(saved.job().title()).isEqualTo("Edited Role");
		assertThat(saved.version()).isNotEqualTo(version);

		// Someone edits the file in the IDE; a save based on the older version must not overwrite it.
		Files.writeString(jobs.resolve("test_role.yaml"), SPEC.replace("Test Role", "Hand edit"));
		assertThatThrownBy(() -> repo.update("test_role", edited, saved.version()))
			.isInstanceOf(JobSpecConflictException.class)
			.hasMessageContaining("changed on disk");
		assertThat(jobs.resolve("test_role.yaml")).content().contains("Hand edit");
	}

	@Test
	void reloadPicksUpHandEditsAndKeepsTheOldVersionWhenTheFileIsInvalid() throws IOException {
		JobSpecRepository repo = loadWith("test_role", SPEC);
		Path file = jobs.resolve("test_role.yaml");

		Files.writeString(file, SPEC.replace("Test Role", "Hand edit"));
		assertThat(repo.reload("test_role").job().title()).isEqualTo("Hand edit");

		Files.writeString(file, SPEC.replace("title: Test Role\n", ""));
		assertThatThrownBy(() -> repo.reload("test_role")).isInstanceOf(InvalidJobSpecException.class);
		assertThat(repo.get("test_role").title()).isEqualTo("Hand edit");

		Files.writeString(file, "title: [unclosed");
		assertThatThrownBy(() -> repo.reload("test_role")).isInstanceOfSatisfying(InvalidJobSpecException.class,
				ex -> assertThat(ex.errors().getFirst()).startsWith("the file is not valid YAML"));
	}

	@Test
	void reloadAllAddsNewFilesDropsRemovedOnesAndReportsInvalidOnes() throws IOException {
		JobSpecRepository repo = loadWith("test_role", SPEC);
		Files.writeString(jobs.resolve("second.yaml"), SPEC);
		Files.writeString(jobs.resolve("broken.yaml"), SPEC.replace("summary: Build things.\n", ""));

		JobSpecRepository.ReloadResult first = repo.reloadAll();
		assertThat(first.loaded()).containsExactly("second", "test_role");
		assertThat(first.errors()).containsOnlyKeys("broken");

		Files.delete(jobs.resolve("second.yaml"));
		assertThat(repo.reloadAll().removed()).containsExactly("second");
		assertThat(repo.all()).extracting(JobSpec::id).containsExactly("test_role");
	}

	@Test
	void savedYamlLoadsBackToTheSameSpec() throws IOException {
		// Round-trip every checked-in spec through the writer, in a temp folder.
		JobSpecRepository real = new JobSpecRepository(new ScreeningProperties(Path.of("jobs"), Path.of("resumes")));
		for (JobSpec spec : real.all()) {
			Files.writeString(jobs.resolve(spec.id() + ".yaml"), real.toYaml(spec));
		}
		JobSpecRepository copy = load();
		for (JobSpec spec : real.all()) {
			JobSpec back = copy.get(spec.id());
			assertThat(back).usingRecursiveComparison().ignoringFields("summary").isEqualTo(spec);
			assertThat(back.summary().strip()).isEqualTo(spec.summary().strip());
		}
		assertThat(real.toYaml(real.all().getFirst())).startsWith("# Job spec for the screener.")
			.contains("\nmust_haves:\n", "\nskills:\n", "# Each skill becomes one Score.");
	}

}
