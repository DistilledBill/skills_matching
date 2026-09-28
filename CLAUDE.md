# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot 4 REST API (Java 21, Maven) that ranks resumes against a YAML job spec using TypeSafe's Jev model (System One API). Jev only answers typed questions about a resume. All policy (weights, thresholds, status, ranking) lives in Java.

## Commands

```sh
./mvnw test                                   # all tests (no API key needed; TypeSafe is mocked)
./mvnw test -Dtest=ScreeningPolicyTest        # one class
./mvnw test -Dtest='ScreeningPolicyTest#compositeIsWeightedAverageOfNormalizedScores'  # one method
./mvnw spring-boot:run                        # run on :8080; start from the project root
./mvnw package                                # build the jar
```

No linter is configured. Java sources are indented with tabs.

The API key comes from `TYPESAFE_API_KEY`, either exported or in a git-ignored `.env` in the project root (`spring.config.import: optional:file:.env[.properties]`). The app starts without a key: `/api/jobs` and `/preview` still work, and screening returns 503.

## Architecture

Request flow, one TypeSafe request per resume:

`ScreeningController` → `ResumeLoader` (.txt/.md/.pdf, PDFBox) → `Redactor` (emails, phones, GitHub/LinkedIn links) → `ScreeningService` (builds state `{job: {title, summary}, resume}` plus questions from `QuestionBuilder`, then fans out on virtual threads) → `TypeSafeClient` (`POST /v1/systemone`; retries 429/500/502/503/504/529 and connection errors with exponential backoff and jitter; a semaphore caps in-flight requests at `typesafe.max-concurrent`) → `AnswerCache` → `ScreeningPolicy.decide` → sort by `CandidateResult.RANKING` → JSON, or CSV via `CsvWriter`.

Key design points that span several files:

- **The job spec is the only input to the model.** `jobs/<id>.yaml` (the file name is the job id) is loaded and validated at startup by `JobSpecRepository`. **An invalid spec stops the app from starting.** Each `must_haves` entry becomes a Noul with id `must_<id>`, using a fixed question and criteria in `QuestionBuilder`. Each `competencies` entry becomes a Score with id `comp_<id>`. Question ids are never sent to the model, so question text must stand on its own.
- **Policy is in `ScreeningPolicy` only.** Status: any must-have below `must_have_fail` → MISSING. Otherwise, any must-have below `must_have_pass`, or any competency with weight ≥ `HEAVY_WEIGHT` (0.2, hard-coded) and confidence < `min_confidence` → REVIEW. Otherwise → MEETS. Composite = Σ(weight/totalWeight × score/(levels−1)). Ranking is by status first, then composite.
- **Cache semantics.** `AnswerCache` stores the raw response in `.cache/<sha256>.json`, keyed on the canonical (key-sorted) JSON of the full request: model, state and questions. Weights and thresholds are not in the request, so changing them re-ranks for free. Changing `title` or `summary` re-asks every question for that job, because both are in the state of every request.
- **Resume folders.** Resumes live in `resumes/<job id>_candidates/`. `/folder?path=` is resolved inside `resumes/`, rejects paths outside it (`..`, absolute paths, symlinks), and is **not recursive**. A blank `path` points at `resumes/` itself, which has no files, so it returns 400.

## Tests depend on real repo files

Tests read the checked-in sample data rather than fixtures, so editing the samples breaks tests:

- `TestAnswers.sampleJob()` loads `jobs/senior_backend_engineer.yaml`. `ScreeningPolicyTest` hard-codes its weights (for example python_depth 0.30, domain_relevance 0.10). Experiment on a copy of the spec, not the original.
- `ResumeLoaderTest` expects exactly `candidate_a`…`candidate_j` in `resumes/senior_backend_engineer_candidates/`.
- `ScreeningApiTest.screensFolderAndRanks` expects 10 candidates. It picks mock answers by matching resume text ("Senior Java engineer" → candidate_c, "Full-stack developer" → candidate_b), so no other resume in that folder may contain those phrases.
- `RedactorTest` expects line 2 of backend candidates A–D to be the contact line. All sample resumes follow this format: line 1 `Candidate X`, line 2 fake contact details (`@example.com`, 555 phone numbers).

## Docs

- `docs/jev-resume-screening.{md,html}` explains Jev and the scoring; `docs/job-spec-guide.{md,html}` explains the job spec format. Keep each .md and .html pair in sync.
- The HTML versions are print-ready. Keep code-block lines at 70 characters or fewer so nothing is cut off when printed or saved to PDF. `pre` wraps as a fallback and never scrolls, and `@media print` rules hide the table of contents and fit tables and SVGs to the page.
- The real numbers in the docs (for example candidate_c's 0.03, candidate_a's composite 0.906) come from a recorded run on 2026-09-24 of backend candidates A–D. Don't change them without re-running the screener.
