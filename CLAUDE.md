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
./mvnw package                                # build the jar (includes the web app)
./mvnw test -Dskip.frontend                   # backend only: skips npm ci/build/test
cd frontend && npm run dev                    # UI with live reload on :5173, proxies /api to :8080
cd frontend && npm test                       # Vitest; npx tsc --noEmit to type-check
```

No linter is configured. Java sources are indented with tabs.

The API key comes from `TYPESAFE_API_KEY`, either exported or in a git-ignored `.env` in the project root (`spring.config.import: optional:file:.env[.properties]`). The app starts without a key: `/api/jobs` and `/preview` still work, and screening returns 503. `ANTHROPIC_API_KEY` (same places) turns on Claude suggestions in the spec editor; without it `/api/assist/*` returns 503 and the ✨ buttons are disabled.

## Architecture

Request flow, one TypeSafe request per resume:

`ScreeningController` → `ResumeLoader` (.txt/.md/.pdf, PDFBox) → `Redactor` (emails, phones, GitHub/LinkedIn links) → `ScreeningService` (builds state `{job: {title, target_level, summary}, resume}` plus questions from `QuestionBuilder`, then fans out on virtual threads) → `TypeSafeClient` (`POST /v1/systemone`; retries 429/500/502/503/504/529 and connection errors with exponential backoff and jitter; a semaphore caps in-flight requests at `typesafe.max-concurrent`) → `AnswerCache` → `ScreeningPolicy.decide` → sort by `CandidateResult.RANKING` → JSON, or CSV via `CsvWriter`.

Key design points that span several files:

- **The job spec is the only input to the model.** `jobs/<id>.yaml` (the file name is the job id) is loaded and validated at startup by `JobSpecRepository`. **An invalid spec stops the app from starting.** Each `must_haves` entry becomes a Noul with id `must_<id>`, using a fixed question and criteria in `QuestionBuilder`. Each `skills` entry becomes a Score with id `skill_<id>`. Question ids are never sent to the model, so question text must stand on its own. `target_level` is required and is sent in the state as context only; no question names it yet.
- **Policy is in `ScreeningPolicy` only.** Status: any must-have below `must_have_fail` → MISSING. Otherwise, any must-have below `must_have_pass`, or any skill with weight ≥ `HEAVY_WEIGHT` (0.2, hard-coded) and confidence < `min_confidence` → REVIEW. Otherwise → MEETS. Composite = Σ(weight/totalWeight × score/(levels−1)). Ranking is by status first, then composite.
- **Cache semantics.** `AnswerCache` stores the raw response in `.cache/<jobId>/<sha256>.json`, keyed on the canonical (key-sorted) JSON of the full request: model, state and questions. Weights and thresholds are not in the request, so changing them re-ranks for free. Changing `title`, `target_level` or `summary` re-asks every question for that job, because all three are in the state of every request.
- **Per-job cache folders.** `TypeSafeClient.evaluate(jobId, request)` and `ScreeningService.isCached(job, request)` take the job so its answers land in its own folder; `DELETE /api/screenings/{jobId}/cache` clears one job. Job ids (spec file names) are limited to letters, digits, `_` and `-` for this reason. On startup `CacheMigration` moves any answers still in the old flat layout (`.cache/<sha256>.json`) into their job's folder, matching them against each job's `<jobId>_candidates` resumes, and **deletes** the ones that match nothing.
- **Tests never touch `.cache/`:** `src/test/resources/application.properties` points `typesafe.cache-dir` at `target/test-cache`, so the startup migration can't run on the real cache during `./mvnw test`.
- **Resume folders.** Resumes live in `resumes/<job id>_candidates/`. `/folder?path=` is resolved inside `resumes/`, rejects paths outside it (`..`, absolute paths, symlinks), and is **not recursive**. A blank `path` points at `resumes/` itself, which has no files, so it returns 400.

## Web app (`frontend/`)

- React 19 + TypeScript + Vite 6, React Router, TanStack Query, plain CSS (light and dark themes via `prefers-color-scheme`). The tooling is pinned to versions that run on Node 20.11. Vite 7 and later need Node 20.12 or newer.
- `frontend-maven-plugin` (in `pom.xml`) downloads Node into `frontend/.node`, then runs `npm ci` and `npm run build` in `generate-resources`, and `npm test` in `test`. Vite writes straight into `target/classes/static`. The plugin's npm cache is `frontend/.npm-cache`, because this machine's `~/.npm` has root-owned files.
- `web/SpaConfig` serves `static/` and returns `index.html` for page URLs such as `/jobs/x/results`, so reloads work. It never rewrites `/api/**` or paths that look like files, so those still 404.
- `src/api.ts` holds hand-written types that mirror the Java records (JSON is camelCase) and one function per endpoint. Keep them in step when a record changes.
- Screening results are kept in memory per browser tab (`src/results.tsx`), because uploaded `File`s can't survive a reload. Re-running is free thanks to the answer cache.
- `src/policy.ts` is a TypeScript copy of `ScreeningPolicy.decide` and `CandidateResult.RANKING`, used for what-if tuning. **Change both together.** `policy.test.ts` checks the copy against real reports saved in `src/fixtures/*.report.json` (each holds the job spec it was run with, so later spec edits don't break it). Reason numbers use `format2`, which rounds like Java's `%.2f`, not like `toFixed`.
- Resume text endpoints default to `redacted=true`. The viewer asks for `redacted=false` to show contact details; Jev only ever receives redacted text.
- Screen-reader-only text (`.visually-hidden`) is absolutely positioned, so a scrolling container that holds it needs `position: relative`, or the page scrolls sideways on phones (see `.table-wrap`).
- The plan, including the remaining phase (3, resume management), is in `.plans/web-frontend-plan.md`.

## Job spec editing and Claude suggestions

- `JobSpecRepository` keeps each spec with its file and a **version** (SHA-256 of the file's bytes). `update` refuses (409) when the file on disk no longer matches the version the editor loaded, so hand edits in the IDE are never overwritten. `reload`/`reloadAll` re-read from disk and keep the loaded version of an invalid file. `validate` returns every error; at startup any error still stops the app.
- `toYaml` writes the standard header, snake_case keys in the usual order, and the section comments. Other hand-written comments are lost on save.
- `frontend/src/spec.ts` mirrors `validate` message for message, and `changeCost` decides free vs paid: anything in the request (title, target_level, summary, requirements, questions, levels, ids) is paid; weights, thresholds and reordering are free, because questions are keyed by id and the cache key sorts keys.
- `assist/ClaudeClient` calls the Messages API with one forced tool (`tool_choice`), so answers are typed JSON; it retries 429/529/5xx like `TypeSafeClient`. `assist/SpecAssistant` sends the draft spec (never resumes), checks each suggestion (level count, unique snake_case id, weight range, non-empty text, a summary of at most 400 words) and retries once with the problems spelled out. When the item already has text, the prompt gives the current version and asks for an improvement, not a copy; the tools have an optional `note` for "already as good as it can be", and a suggestion that repeats the current text (fully, or some of its levels) comes back with a warning. Its system prompt is `src/main/resources/assist/spec-writing-rules.md`: **keep it in step with sections 3 and 7 of `docs/job-spec-guide.md`.**
- Tests never call Claude: `ClaudeClientTest` uses `MockRestServiceServer`, the others mock `ClaudeClient`. For a manual end-to-end check without cost, point `anthropic.base-url` at a local stub.

## Tests depend on real repo files

Tests read the checked-in sample data rather than fixtures, so editing the samples breaks tests:

- `TestAnswers.sampleJob()` loads `jobs/senior_backend_engineer.yaml`. `ScreeningPolicyTest` hard-codes its weights (for example python_depth 0.30, domain_relevance 0.10). Experiment on a copy of the spec, not the original.
- `ResumeLoaderTest` expects exactly `candidate_a`…`candidate_j` in `resumes/senior_backend_engineer_candidates/`.
- `ScreeningApiTest.previewBuildsRequestWithoutCallingTypeSafe` expects the backend spec's `target_level` to be `Vice President`.
- `ScreeningApiTest.screensFolderAndRanks` expects 10 candidates. It picks mock answers by matching resume text ("Senior Java engineer" → candidate_c, "Full-stack developer" → candidate_b), so no other resume in that folder may contain those phrases.
- `WebAppApiTest` points `typesafe.cache-dir` at a temp directory, so cache-status tests never read the real `.cache/`. `src/test/resources/static/index.html` exists only so the page-URL fallback can be tested.
- `ResumeApiTest` reads `candidate_a.txt` from the backend folder and expects its contact line (`a.candidate@example.com`, `(555) 201-3344`).
- `RedactorTest` expects line 2 of backend candidates A–D to be the contact line. All sample resumes follow this format: line 1 `Candidate X`, line 2 fake contact details (`@example.com`, 555 phone numbers).

## Docs

- `docs/jev-resume-screening.{md,html}` explains Jev and the scoring; `docs/job-spec-guide.{md,html}` explains the job spec format. Keep each .md and .html pair in sync.
- `docs/screening-workflow.html` is a standalone animated diagram of the screening classes (no .md pair). Its facts (class names, methods, call flow, settings, step captions) live in the `NODES`, `EDGES` and `SCENARIOS` constants in its script: update them when those classes or `application.yml` defaults change.
- The HTML versions are print-ready. Keep code-block lines at 70 characters or fewer so nothing is cut off when printed or saved to PDF. `pre` wraps as a fallback and never scrolls, and `@media print` rules hide the table of contents and fit tables and SVGs to the page.
- The real numbers in the docs (for example candidate_c's 0.03, candidate_a's composite 0.906) come from a recorded run on 2026-09-24 of backend candidates A–D. Don't change them without re-running the screener.
