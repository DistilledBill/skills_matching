package com.example.resumescreening.screening;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '\'', value = {
			"candidate_a | '[email] | [phone] | [link]'",
			"candidate_b | '[email]'",
			"candidate_c | '[email] | [phone]'",
			"candidate_d | '[email]'" })
	void redactsContactLineOfSampleResumes(String resume, String expectedLine) throws IOException {
		String text = Files.readString(Path.of("resumes", "senior_backend_engineer_candidates", resume + ".txt"));

		String contactLine = Redactor.redact(text).lines().toList().get(1);

		assertThat(contactLine).isEqualTo(expectedLine);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"Call (555) 201-3344 today | Call [phone] today",
			"Call +1 555 990 1122 today | Call [phone] today",
			"see https://www.linkedin.com/in/someone for more | see [link] for more",
			"Cut p99 latency from 900ms to 180ms | Cut p99 latency from 900ms to 180ms",
			"2015–2017 at DataPoint | 2015–2017 at DataPoint" })
	void redactsOnlyContactDetails(String input, String expected) {
		assertThat(Redactor.redact(input)).isEqualTo(expected);
	}

}
