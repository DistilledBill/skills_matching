package com.example.resumescreening.screening;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.example.resumescreening.job.JobSpec;

/** Ranked results with every raw probability and confidence, one row per candidate. */
public final class CsvWriter {

	private CsvWriter() {
	}

	public static String write(JobSpec job, List<CandidateResult> results) {
		List<String> mustIds = job.mustHaves().stream().map(JobSpec.MustHave::id).toList();
		List<String> compIds = job.competencies().stream().map(JobSpec.Competency::id).toList();

		List<String> header = new ArrayList<>(List.of("rank", "candidate", "status", "composite", "reasons"));
		mustIds.forEach(id -> header.add("must_" + id));
		header.addAll(compIds);
		compIds.forEach(id -> header.add(id + "_confidence"));

		StringBuilder csv = new StringBuilder(row(header));
		for (CandidateResult r : results) {
			List<String> cells = new ArrayList<>(List.of(String.valueOf(r.rank()), r.name(), r.status().json(),
					num(r.composite()), String.join("; ", r.reasons())));
			mustIds.forEach(id -> cells.add(num(r.mustHaves().get(id))));
			compIds.forEach(id -> cells.add(num(r.scores().get(id))));
			compIds.forEach(id -> cells.add(num(r.confidences().get(id))));
			csv.append(row(cells));
		}
		return csv.toString();
	}

	private static String row(List<String> cells) {
		return cells.stream().map(CsvWriter::escape).collect(Collectors.joining(",", "", "\r\n"));
	}

	private static String escape(String cell) {
		if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
			return "\"" + cell.replace("\"", "\"\"") + "\"";
		}
		return cell;
	}

	private static String num(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

}
