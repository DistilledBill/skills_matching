package com.example.resumescreening.screening;

import java.util.List;

/** Ranked results for one job; candidates are sorted by {@link CandidateResult#RANKING}. */
public record ScreeningReport(String jobId, String title, List<CandidateResult> candidates) {
}
