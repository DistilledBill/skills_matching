# Web front end for the resume screening service

## Context

Today the service is used through curl: 4 REST endpoints (`GET /api/jobs`, `POST /api/screenings/{jobId}` uploads, `POST /api/screenings/{jobId}/folder?path=`, `POST .../preview`) with JSON or CSV output. The user wants a web application that exposes all of that, plus these extras: a folder picker, what-if tuning, a resume viewer, a job spec editor, and **resume management**. Resume management means uploading `.pdf`, `.txt` and `.md` resumes, viewing and editing their content, and saving them into a chosen folder. At the app's centre is a rich ranked-results view.

**Chosen approach:** a React + TypeScript SPA (Vite) in `frontend/`. Maven builds it into Spring Boot's static resources, so there is one app, one port (`localhost:8080`), one jar, and no CORS. The existing REST API stays unchanged. The new backend endpoints are additive.

## Screens

1. **Jobs** (`/`): a card per spec showing title, summary, must-haves, skills with weights, and thresholds. Actions: Screen, Edit, New job.
2. **Screen** (`/jobs/:id/screen`): pick the source.
   - **Folder:** a dropdown from the new folder endpoint, defaulting to `<id>_candidates`.
   - **Upload:** drag and drop `.txt`, `.md` or `.pdf` files.
   - Buttons: **Preview what Jev sees**, which opens the preview panel below for any resume in the selection and costs nothing, and **Run screening**. A summary line shows how many of the selected resumes already have a cached answer, so you know how many calls to Jev a run will make.
   - Errors in the API's standard JSON format (RFC 9457 `ProblemDetail`) show inline. A 503 means there's no API key and a 502 means TypeSafe failed; each gets a clear message.
3. **Results** (`/jobs/:id/results`): the main view.
   - **Ranked table:** rank, candidate, status pill (meets / review / missing), composite as a bar, one mini bar per skill (faded when confidence is low), must-have probabilities, and reasons.
   - **Controls:** filter by status, sort by any column, and a **Download CSV** button (`format=csv`, fetched as a file).
   - **Row click:** opens the **resume viewer** side panel. Its **Resume** tab shows the resume **with contact details** (the original text, `redacted=false`) alongside that candidate's scores. A **What was sent to Jev** tab shows the preview panel for that candidate, which is redacted because that is what Jev receives.
   - **What-if panel:** sliders for each skill weight plus `must_have_pass`, `must_have_fail` and `min_confidence`. The ranking recomputes instantly in the browser and arrows show rank changes. **Reset** restores the spec's values; **Save to spec** opens the editor pre-filled. After a save, the next screening re-ranks from the cache for free, because weights and thresholds aren't part of the request.
4. **Spec editor** (`/jobs/:id/edit`, `/jobs/new`): a form for title, target level, summary, must-haves (add, remove, reorder), and skills (weight, question, levels list), plus thresholds. Details, including cost warnings and protection for hand edits, are in [Phase 4](#phase-4-authoring-the-spec-editor).
   - It checks the same rules as `JobSpecRepository.validate` on the client, and the server re-checks on save.
   - It shows the running weight total and whether each skill counts as heavy (0.2 or more).
   - A read-only YAML preview updates as you type.
   - Creating a job also creates `resumes/<id>_candidates/`.
5. **Resumes** (`/resumes`, `/resumes/:folder`): manage the resume library.
   - **Folders:** a list of the folders under `resumes/` with file counts, and a **New folder** button. Opening a folder lists its resumes; each one can be opened in the editor below, edited and saved again.
   - **Upload and review:** drag and drop `.pdf`, `.txt` or `.md` files. Each file opens as a card in a review area with:
     - a file name field, pre-filled from the upload's name and cleaned to lowercase letters, digits, `_` and `-`
     - a **"What Jev will see"** toggle that shows the redacted text (emails, phones and profile links replaced) without changing what is saved
     - a **Preview for job ▾** button that opens the preview panel for this resume against any job, using the text as currently edited, before it is saved
     - a warning when the text is empty, or when line 2 isn't a contact line
   - **How each format is viewed and edited:**

     | Format | View | Edit | Saved as |
     | --- | --- | --- | --- |
     | `.txt` | Text box | Edit in place | `<name>.txt` |
     | `.md` | Text box, with a rendered Markdown preview beside it | Edit in place | `<name>.md` |
     | `.pdf` | The PDF shown in the browser, next to the text PDFBox extracted from it (the text Jev reads) | Not editable as a PDF. **Edit as text** turns the card into a text box holding the extracted text, and you choose `.txt` or `.md` | Unedited: the original `<name>.pdf`, unchanged. Edited: `<name>.txt` or `<name>.md`, and the PDF is not saved |

     A card can also switch between `.txt` and `.md` before saving.
   - **Save:** choose a target folder, or create one, then **Save** or **Save all**.
     - A clash with an existing resume asks before overwriting. The check is on the base name regardless of extension, because `candidate_a.pdf` and `candidate_a.txt` would both be screened as `candidate_a`.
     - After saving, a **Screen this folder** button opens the Screen page with that folder selected.
   - **Editing saved resumes:** opening a resume from a folder works the same way, preview included. A `.txt` or `.md` file opens in the text box. A `.pdf` opens in the viewer, with **Edit as text** to replace it with a text version.
   - **Protected folder:** `senior_backend_engineer_candidates` shows a warning before any save, because the tests expect exactly the 10 files it holds now.

### Preview panel: what gets sent to Jev

A shared component, used on the Screen page, in the Results side panel and on each Resumes card. It shows the exact request for one resume and one job, and never calls Jev, so it works without an API key. It has two tabs:

- **Readable:**
  - **Model:** for example `jev-latest`.
  - **State:** the job title and summary, and the resume as Jev receives it. Each redaction (`[email]`, `[phone]`, `[link]`) is highlighted so you can see what was removed.
  - **Must-haves:** one card per must-have (`must_<id>`), showing the requirement text, the fixed question and the true/false criteria.
  - **Skills:** one card per skill (`skill_<id>`), showing the question and its numbered levels. Where it helps, each card notes that the weight and thresholds are never sent to Jev.
  - **Size:** the request size in characters and an estimate of input tokens, labelled as an estimate.
- **Raw JSON:** the exact request body, pretty-printed, with a **Copy** button.
- **Cache status badge:**
  - **Cached** means this exact request already has an answer, so screening it is free.
  - **Not cached** means screening it will call Jev.

## Backend changes (additive)

- **Preview a chosen resume:** add an optional `name` parameter to `POST /api/screenings/{jobId}/preview`, choosing a resume in the folder given by `path`. Without it the endpoint behaves as it does now, taking the first resume, so the existing test still passes.
- **Preview unsaved text:** new `POST /api/screenings/{jobId}/preview-text` with JSON `{name, text}`. It builds the same request from text that hasn't been saved yet, which is what the review cards and the editor use. It calls `ScreeningService.request` with a `ResumeDocument` made from the text, so redaction and question building run exactly as in a real screening.
- **Cache status:** both preview endpoints add an `X-Answer-Cached: true|false` response header, worked out with `AnswerCache.key` and `AnswerCache.get`. The JSON body stays the unchanged `SystemOneRequest`.
- **Batch cache check:** new `POST /api/screenings/{jobId}/cache-status?path=` returns `{total, cached}` for a folder, so the Screen page can show how many calls a run will make.

- **Resume folders:** `GET /api/resume-folders` returns `[{path, fileCount}]` for the subfolders of `resumes/`.
- **List a folder:** `GET /api/resume-folders/{folder}` returns `[{fileName, name, format, size}]`, where `format` is `pdf`, `txt` or `md`.
- **Read a resume:** `GET /api/resume-folders/{folder}/resumes/{fileName}?redacted=true|false` returns `{fileName, format, text}`. For a PDF, `text` is the extracted text. Both values are supported. `redacted=false` gives the original text with contact details, and serves the resume viewer and the resume editor. `redacted=true` gives the text as Jev sees it. When the parameter is left out it defaults to `true`, so contact details are only returned when asked for.
- **Get the original file:** `GET /api/resume-folders/{folder}/files/{fileName}` returns the file as stored, with its content type (`application/pdf`, `text/plain` or `text/markdown`). The PDF viewer uses this.
- **Extract text from uploads:** `POST /api/resumes/text?redacted=true|false` (multipart) returns `[{fileName, suggestedName, format, text}]` for the upload review cards and for the viewer on upload screenings (which calls it with `redacted=false`). Same `redacted` rules and default as the read endpoint.
- **Create a folder:** `POST /api/resume-folders` with `{name}`. The name must match `^[a-z0-9_]+$`, and it returns 409 if the folder already exists.
- **Save a resume:** `PUT /api/resume-folders/{folder}/resumes/{fileName}`, where `fileName` is `<name>.pdf`, `<name>.txt` or `<name>.md`.
  - **Body:** for `.txt` and `.md`, UTF-8 text (`text/plain` or `text/markdown`). For `.pdf`, the original bytes (`application/pdf`). A PDF is checked by opening it with PDFBox, and one that fails to open is rejected.
  - **Rules:**
    - The name must match `^[a-z0-9_-]+$`, and the extension must be `pdf`, `txt` or `md`.
    - Size limit: 10 MB, matching the existing upload limit.
    - Empty text is rejected, and so is a PDF with no extractable text, since the screener would see nothing.
  - **Clashes:** it returns 409 if any file in the folder already has that base name, in any format, unless `?overwrite=true` is passed. Overwriting removes the other-format file of the same name, so a PDF replaced by an edited `.txt` doesn't leave both behind.
  - **Writing:** a temp file, then a move into place, as `AnswerCache.put` does.
- **Code:** all of these live in a new `web/ResumeController`. They reuse `ResumeLoader.resolveInsideRoot` (the same guard against paths outside `resumes/`), `ResumeLoader.load` (text extraction, including PDFs) and `Redactor.redact`.
  - Add `listFolders()`, `listFolder(folder)`, `readOne(folder, fileName)` and `rawFile(folder, fileName)` to `ResumeLoader`. Put the writing methods, `createFolder(name)` and `save(folder, fileName, bytes, overwrite)`, in a new `ResumeLibrary` class next to it. `ResumeLoader.SUPPORTED` stays the single list of allowed formats.
  - Saved resumes are stored with contact details intact, exactly as uploaded. Redaction still happens only when a resume is sent to Jev, as it does today.
- **Saving job specs:** `POST /api/jobs` creates a spec and `PUT /api/jobs/{id}` replaces one, in a new `web/JobController`. `JobSpecRepository` changes to support this:
  - A job id may only use letters, digits, `_` and `-` (the rule `JobSpecRepository` already enforces since phase 2.1), which also blocks paths outside `jobs/`.
  - `validate` returns a list of error messages that the API returns as 400 responses, via a new `InvalidJobSpecException` handled in `ApiExceptionHandler`. Loading at startup still fails fast.
  - Save writes to a temp file and then moves it into place, the same pattern as `AnswerCache.put`. It writes snake_case YAML with the standard header comment, then swaps in the new spec.
  - The job map becomes safe for concurrent reads and writes (a `ConcurrentSkipListMap`).
  - Hand-written YAML comments beyond the standard header are lost on save. The editor will warn about this.
- **Serving the app:** `web/SpaConfig` forwards any path that isn't under `/api` and has no file extension to `index.html`, so client-side page URLs work on reload.

## Front end

- **Stack:**
  - `frontend/` with Vite, React and TypeScript
  - React Router for page URLs
  - TanStack Query for API calls
  - plain CSS with light and dark themes; no component library, so the build stays small
- **`src/policy.ts`:** a TypeScript copy of `ScreeningPolicy` for what-if tuning. It covers the status rules, the `HEAVY_WEIGHT = 0.2` cutoff, the composite formula and ranking by status then composite. A comment points back to the Java source it copies.
- **API types:** hand-written TypeScript types matching `JobSpec`, `ScreeningReport`, `CandidateResult` and `ProblemDetail` (the JSON uses camelCase, for example `mustHaves`).
- **Development:** `npm run dev` on port 5173, with a proxy that sends `/api` to 8080.
- **Build:** `frontend-maven-plugin` installs its own copy of Node and runs `npm ci` and `npm run build` during `generate-resources`, writing the built app to `target/classes/static`. A `-Dskip.frontend` flag keeps `./mvnw test` fast for backend-only work.
- **Also:** add `frontend/node_modules/` and `frontend/dist/` to `.gitignore`, and update `CLAUDE.md` and the README with the new commands.

## Phases

1. **Scaffold and core flow:** Maven and Vite wiring, the SPA forwarding, the folder endpoint, the preview panel with its endpoints and cache status, and the Jobs, Screen, Results and CSV screens. **Done** (commit `143499e`).
1.1. **Clearer scores and weights:** see [Phase 1.1](#phase-11-clearer-scores-and-weights) below. Front end only. **Done** (commit `e6ca13c`).
2. **Analysis:** what-if tuning (`policy.ts`), the resume viewer and its read endpoints. See [Phase 2](#phase-2-analysis-what-if-tuning-and-resume-viewer) below. **Done** (commit `e6ca13c`).
2.1. **Weight total indicator, per-job cache and Clear cache:** see [Phase 2.1](#phase-21-weight-total-indicator-per-job-cache-and-clear-cache) below. **Done** (commit `e6ca13c`).
2.2. **Rename competencies to skills:** see [Phase 2.2](#phase-22-rename-competencies-to-skills) below. **Done** (commit `e6ca13c`).
2.3. **Required `target_level` as context:** see [Phase 2.3](#phase-23-required-target_level-sent-as-context) below. **Done** (commit `eb18d86`).
3. **Resume management:** the Resumes screen (upload, review, edit and save), plus the create-folder and save-resume endpoints.
4. **Authoring:** the spec editor, the save endpoints and the repository changes. See [Phase 4](#phase-4-authoring-the-spec-editor) below. **Done** (commit `7d01e67`).
4.1. **Claude suggestions in the editor:** suggest a skill's question, a skill's levels, a must-have's requirement, or a whole new skill. See [Phase 4.1](#phase-41-claude-suggestions-in-the-spec-editor) below. **Done** (commit `7d01e67`), including the manual check with a real key and the fix so suggestions improve existing text instead of repeating it.
4.2. **Claude-drafted job overview:** describe the job in a few words and press **Draft job overview** to get a suggested summary. See [Phase 4.2](#phase-42-claude-drafted-job-overview) below. **Done**, including the manual check with a real key (2 calls).
4.3. **Must-have names on the Jobs and Results pages:** a subtitle above each must-have's requirement, and one Results column per must-have. See [Phase 4.3](#phase-43-must-have-names-on-the-jobs-and-results-pages) below. **Done**, including reasons that use the same names.
4.4. **Candidate panel docked beside the results, with a splitter:** see [Phase 4.4](#phase-44-candidate-panel-docked-beside-the-results) below. **Done**.
4.5. **Screening workflow diagram (docs):** an animated page of the screening classes, with Jev as the only source of judgments, fitting one screen on laptops and desktops. See [Phase 4.5](#phase-45-screening-workflow-diagram-docs) below. **Done**.

## Phase 1.1: clearer scores and weights

### Why

Feedback on the phase 1 app:

- **Results table:** each bar has the score as a number to its right, so the bar and the number say the same thing. Confidence appears only in a hover tooltip, and the note under the table ("Faded bars have confidence below 0.45") doesn't match what you see.
- **Jobs cards:** each skill gets its own horizontal bar. That doesn't show how the weights share the total.

This phase touches the front end only. No API, Java or policy changes.

### Results page: score inside the bar, confidence beside it

- **`Bar` component** (`frontend/src/components/Bar.tsx`):
  - New props: `value` (the score), plus an optional `confidence` and `lowConfidence`. The `title` and `faded` props are removed.
  - The score (2 decimals) is centred inside the bar. The track grows from 56×6px to about 72×18px so the number is readable.
  - The fill uses a new soft colour token (`--score-fill`, light and dark) so `--fg` text stays readable over both the filled and the empty part. It is not the solid accent.
  - When `confidence` is given, it replaces the number to the right of the bar.
  - Low confidence (below `min_confidence`) shows the confidence number in amber, using the existing `--warn` / `--warn-soft` chip style. The bar is no longer faded, because a faded bar with text inside it is hard to read.
- **Results table** (`frontend/src/pages/ResultsPage.tsx`):
  - **Skill columns:** `<Bar value={score} confidence={conf} lowConfidence={conf < minConfidence} />`. The hover tooltip is removed.
  - Each skill header gets a small muted second line, "score · conf.", so it's clear what the two numbers are.
  - **Composite column:** the score is centred in the bar, with nothing beside it, because the composite has no confidence.
  - **Note under the table:** rewritten to match what's on screen: "Score (0–1) is inside each bar; the number beside it is Jev's confidence. Confidence below 0.45 is shown in amber. Select a row for details and the exact request sent to Jev."
- **Candidate side panel, Skills list:** uses the same `Bar` with confidence, replacing the separate "confidence 0.xx" text, so both views read the same way.
- **Other tooltips stay:** only the tooltip on the score bars is removed. The skill header still shows its question on hover, and must-have chips still show their requirement. Say if those should go too.

### Jobs cards: one stacked weight bar

- **New `WeightStack` component** (`frontend/src/components/WeightStack.tsx`): a single vertical bar, about 20px wide, stretched to the height of the skill list.
  - One coloured segment per skill, stacked top to bottom in list order.
  - Segment height is `weight / total weight`, so the segments always fill exactly 100%. The spec doesn't require the weights to add up to 1, and this is the same share the composite uses.
  - `role="img"` with an `aria-label` such as "Weights: python depth 30%, …", and no hover tooltip.
- **Skill list** (`frontend/src/pages/JobsPage.tsx`): stays where it is, in the same order with the same names and weights. Each row's horizontal bar is replaced by a small colour swatch matching its segment, and the stacked bar sits beside the list. Layout: a two-column grid, with the list on the left and the bar on the right.
- **Colours:** 8 categorical tokens `--seg-1` … `--seg-8` in `styles.css`, with dark-mode variants. They are chosen to be distinguishable from each other and from the status colours. Every spec has 5 skills today; past 8, the colours repeat.
- **Clean-up:** the now-unused `.weight-track` and `.weight-fill` CSS is removed.

### Tests and verification

- **Vitest:**
  - `Bar` shows the score inside the bar, shows the confidence beside it, marks low confidence and has no `title`.
  - `WeightStack` segment heights add up to 100% for weights that don't sum to 1 (for example 2 and 1 give 66.7% and 33.3%), and it has the aria label.
- **`./mvnw test`:** all 46 Java tests and the front-end tests pass.
- **In the browser:** jar on a spare port, screening only fully cached folders, so no API calls. Check:
  - The results table shows scores in the bars and confidences beside them.
  - Any cell with confidence below 0.45 is amber. The cached answers will show whether there are any. If there are none, a Vitest case covers the styling.
  - There are no hover tooltips on the bars.
  - The job cards show a stacked bar whose segment heights match the weights.
  - It works at 390px and in dark mode.
- **Docs:** update the `CLAUDE.md` "Web app" section only if something there changes. The README doesn't describe the bars, so it needs no change.
- **On approval:** copy this plan to `.plans/web-frontend-plan.md`, so both copies match.

## Phase 2: analysis (what-if tuning and resume viewer)

### Why

- **What-if tuning:** you can see how the ranking would change under different weights and thresholds before editing a spec. It needs no API calls, because weights and thresholds are never sent to Jev.
- **Resume viewer:** from the results you can read a candidate's resume, with contact details, next to their scores.

### Backend: read endpoints (`web/ResumeController`, `screening/ResumeLoader`)

- **`GET /api/resume-folders/{folder}/resumes/{fileName}?redacted=true|false`:** returns `{fileName, name, format, text}`.
  - For a PDF, `text` is the text PDFBox extracts, the same text screening uses.
  - Both values are supported. `false` returns the original text, contact details included. `true` returns the text run through `Redactor.redact`. Left out, it defaults to `true`.
- **`GET /api/resume-folders/{folder}/files/{fileName}`:** returns the stored file, byte for byte.
  - Content type is `application/pdf`, `text/plain` or `text/markdown`, sent as `Content-Disposition: inline` so a PDF opens in the browser.
  - The viewer links to it as "Open original file".
- **`POST /api/resumes/text?redacted=true|false`** (multipart `files`): returns `[{fileName, name, format, text}]`, using `ResumeLoader.fromUploads`, which already extracts PDF text. Same `redacted` rules and default.
- **`ResumeLoader.readOne(folder, fileName)` and `rawFile(folder, fileName)`:**
  - The folder goes through the existing `resolveInsideRoot`.
  - `fileName` must be a bare file name (no `/`, `\` or `..`) with a supported extension, a regular file, and have a real path inside the folder, which rejects symlinks pointing out.
  - Text extraction reuses `load`.
- **New `ResumeNotFoundException` → 404** in `ApiExceptionHandler`. A bad name or path stays a 400, via `InvalidResumeException`.
- **New record `ResumeContent(fileName, name, format, text)`.** Phase 3 adds `suggestedName` to it for the upload review cards.

### Front end: resume viewer

- The Results side panel gets three tabs: **Scores**, **Resume** (new) and **What was sent to Jev**.
- **Resume tab:** the original text, with contact details, in the existing `.resume-text` style.
  - **Folder runs:** it finds the file name from the folder listing (`api.folder`, already cached by TanStack Query), then calls the read endpoint with `redacted=false`. A PDF also gets an "Open original file" link.
  - **Upload runs:** it posts just that candidate's `File`, still held in memory from the run, to `/api/resumes/text?redacted=false`.
  - A one-line note says: "Shown with contact details. Jev receives this without them; see What was sent to Jev."
- **`api.ts`:** `resume(folder, fileName, redacted)`, `resumeFileUrl(folder, fileName)` and `resumeTexts(files, redacted)`, plus the `ResumeContent` type.

### Front end: what-if tuning

- **`src/policy.ts`:** a TypeScript copy of `ScreeningPolicy.decide` and `CandidateResult.RANKING`, with a comment pointing to the Java source. It works from the per-candidate data the report already carries (must-have probabilities, normalized scores, confidences), given a set of weights and thresholds.
  - **Status:** a must-have below `mustHaveFail` → missing. Otherwise, a must-have below `mustHavePass`, or a skill with weight ≥ 0.2 and confidence below `minConfidence` → review.
  - **The 0.2 test uses the what-if weight**, because Java compares the raw weight.
  - **Composite:** Σ weight/total × score.
  - **Reasons:** the same text formats as Java.
  - **Ranking:** status, then composite descending, then name to break ties. Java keeps file order on ties, and folder files are sorted by name, so this matches for folder runs.
- **What-if panel** on the Results page: a collapsible section above the table, closed by default.
  - One slider per skill weight (0–1, step 0.01), showing the running total and a "heavy" marker at 0.2 or more.
  - Sliders for `must_have_pass`, `must_have_fail` and `min_confidence` (0–1, step 0.01). `must_have_fail` can't go above `must_have_pass`.
  - **Reset** restores the spec's values.
  - **Save to spec** is left out until phase 4, when the spec editor exists.
- **While the values differ from the spec:**
  - The table, status filter counts, sorting, weight headers, low-confidence amber and must-have chip colours all use the what-if values.
  - Each row shows its rank change against the spec ranking, for example ▲2 or ▼1.
  - A banner says "What-if values: this ranking isn't saved. Download CSV uses the spec's values."
- **State:** held in the Results page, and cleared on reset or when a new screening runs. Nothing is saved.

### Tests

- **Java** (`WebAppApiTest` or a new `ResumeApiTest`; reads only, no temp writes needed):
  - Reading a resume with `redacted=false` contains its `@example.com` email and 555 phone number. With `redacted=true` it contains `[email]` and `[phone]` instead. With the parameter left out, the result is the same as `true`.
  - A PDF read returns extracted text. It uses a PDF built in a temp resumes folder, the same way `ResumeLoaderTest` builds one.
  - The file endpoint returns the exact bytes and content type.
  - Uploads in both modes.
  - Rejected: `..`, a `/` in the name, an unsupported extension, and a folder outside `resumes/` → 400. A missing file → 404.
- **Vitest:**
  - `policy.test.ts` covers the same cases as `ScreeningPolicyTest`: strong meets with composite 1, weighted composite, fail → missing, between → review, missing not downgraded, heavy low-confidence → review, light ignored, and ranking order.
  - **Parity check:** a saved real `ScreeningReport` JSON for the 10 backend candidates (scores only, no contact details) run through `policy.ts` with the spec's own values reproduces every status, composite (to 1e-9), reason and rank the server produced.
  - A component test: moving a weight slider re-orders the table and shows rank arrows.

### Verification (no paid API calls)

- `./mvnw test`: all Java and front-end tests pass.
- **Browser check without spending money:** run the jar with `--typesafe.cache-dir=<the scratchpad cache backup>` and `--typesafe.api-key=` (blank). It reads the answers saved before the cache was cleared. Your cleared `.cache/` isn't touched, and any cache miss fails with 503 rather than calling Jev. Then check:
  - Screening the backend folder gives the known ranking.
  - Capture that report as the Vitest parity fixture.
  - The what-if sliders re-rank the table, with arrows, and Reset restores the ranking.
  - The Resume tab shows the contact details. A PDF, if present, gets the "Open original file" link.
  - The Resume tab works for an upload run.
  - The page works at 390px wide, in dark mode, with no console errors.
- **Docs:** README API table (3 new endpoints) and the `CLAUDE.md` Web app section.

## Phase 2.1: weight total indicator, per-job cache, and Clear cache

### Why

- **Weight total:** in what-if tuning it's easy to leave the weights not adding up to 1. The ranking still works, because the composite uses each weight's share, but you asked for a clear prompt to rebalance.
- **Clear cache:** you want to clear every cached answer for one job from the Screen page. Today's flat cache (`.cache/<hash>.json`) can't tell which job an answer belongs to, so the cache moves to one folder per job.

### What-if weight total (`WhatIfPanel`)

- The Total line compares the total, rounded to 2 decimals, with 1.00.
  - **Exactly 1.00:** plain, as now.
  - **Below 1:** red, with **▲** and the shortfall, for example `Total 0.80 ▲ 0.20`, with screen-reader text "0.20 below 1".
  - **Above 1:** red, with **▼** and the excess, for example `Total 1.90 ▼ 0.90`.
- The red and the arrow go away as soon as the total is back to 1.00.
- The ranking maths doesn't change.

### Per-job cache (`AnswerCache`, `TypeSafeClient`, `ScreeningService`)

- **Layout:** answers are stored as `.cache/<jobId>/<sha256>.json`. The key is the same hash of the full request as today. The job id must match `^[a-z0-9_]+$` and resolve inside the cache folder.
- **API changes:**
  - `AnswerCache`: `get(jobId, key)`, `put(jobId, key, json)`, `count(jobId)` and `clear(jobId)`. `clear` deletes that job's folder contents and returns how many answers it removed.
  - `TypeSafeClient.evaluate(jobId, request)`.
  - `ScreeningService.isCached(jobId, request)`.
  - The cache-status endpoint and the preview's `X-Answer-Cached` header look in the job's folder.
- **One-time migration** (`CacheMigration`, run when the app starts, a no-op once there are no top-level `*.json` files):
  - For each job, it rebuilds the request for every resume in `resumes/<jobId>_candidates/` with the current spec. A matching top-level file is moved into `.cache/<jobId>/`.
  - **Every top-level `*.json` file left after that is deleted, as you asked.** That covers answers from uploads, other folders, or older spec wording.
  - The log reports how many files were moved per job and how many were deleted.
- **Tests never touch the real `.cache/`:** a test `application.properties` points `typesafe.cache-dir` at `target/test-cache`, so the migration can't run on your cache during `./mvnw test`.

### Clear cache (Screen page)

- **`GET /api/screenings/{jobId}/cache`** returns `{count}` for the job.
- **`DELETE /api/screenings/{jobId}/cache`** returns `{removed}`. An unknown job gives 404.
- **The button:** "Clear cache for this job (N answers)" sits next to the cache line, and is disabled when N is 0. Clicking it opens an inline confirmation:
  > Delete N cached answers for <job title>? Every answer for this job is removed, from any folder or upload. The next screening will call Jev for each resume and cost money.

  It has **Delete** and **Cancel** buttons. After deleting, the cache line, the count and any open preview's Cached badge refresh.

### Tests and verification

- **Java:**
  - `AnswerCache` get, put, count and clear per job; an invalid job id is rejected; clearing one job leaves the others alone.
  - `TypeSafeClientTest` updated for `evaluate(jobId, …)`.
  - Migration: a matching flat file is moved into its job's folder, and a flat file that matches nothing is deleted.
  - Endpoints: count, delete, 404 for an unknown job.
  - Cache-status and preview read the job's folder.
- **Vitest:**
  - The weight total is plain at 1.00, and shows ▲ below 1 and ▼ above 1.
  - The Clear cache confirmation calls DELETE only after **Delete** is clicked.
- **Your real cache:**
  - Back up `.cache/` to the scratchpad first.
  - Start the new jar once so the migration runs on it, and report the moved and deleted counts.
  - Check the Screen page cache lines for each folder.
- **Clear cache in the browser:** tested only on a scratch copy of the cache, never your real one.
- **Docs:** the `CLAUDE.md` cache section, and the README API table.

## Phase 2.2: rename "competencies" to "skills"

### Why

You want the term "skills" everywhere instead of "competencies", including the question ids sent to Jev (option B).

### What changes

1. **Job specs** (`jobs/*.yaml`, all 4): the YAML key `competencies:` becomes `skills:`, and the comments change to match. Your uncommitted edits in `senior_backend_engineer.yaml` and `senior_hr_product_owner.yaml` are kept.
2. **Question ids sent to Jev:** `comp_<id>` becomes `skill_<id>` (`QuestionBuilder.skillId`).
3. **Java:**
   - `JobSpec.Competency` becomes `JobSpec.Skill`, and `competencies()` becomes `skills()`.
   - `QuestionBuilder.competencyId` becomes `skillId`.
   - Local names such as `comp` and `compIds` in `ScreeningPolicy`, `CsvWriter` and `CandidateResult` change.
   - Javadoc and the validation messages in `JobSpecRepository` change, for example "at least one skill is required" and "skill X needs a positive weight".
4. **REST API:** in `GET /api/jobs`, the JSON field `competencies` becomes `skills`. This is a breaking change for outside scripts.
5. **Web app:**
   - `api.ts` types (`Skill`, `JobSpec.skills`).
   - `policy.ts`, `JobsPage`, `ResultsPage` (the `comp:` sort keys, the `comp-col` class), `PreviewPanel` ("Skills (5): scored on levels"), `WhatIfPanel` ("Skill weights"), `WeightStack`, and `styles.css`.
6. **Tests:**
   - Java: `TestAnswers`, `ScreeningPolicyTest` (for example `lowConfidenceOnHeavySkillGoesToReview`), `ScreeningApiTest`, `TypeSafeClientTest` (`comp_depth`), `WebAppApiTest`, and the preview assertions that count question cards.
   - Vitest tests.
   - The 3 parity fixtures: rename the `competencies` key in each saved spec. The scores and the expected results stay the same, because they don't depend on the question ids.
7. **Docs:**
   - `job-spec-guide.{md,html}` and `jev-resume-screening.{md,html}`, including the HTML diagrams and example request JSON (`skill_python_depth`). Each md/html pair stays in sync, and code lines stay at 70 characters or fewer.
   - `README.md`, `CLAUDE.md`, and this plan.
   - The recorded A–D numbers in the docs stay as they are, with a note that they were recorded when the ids were `comp_*`.
8. **Not changed:** `.claude/skills/typesafe-ai/` (TypeSafe's skill), `node_modules`, and build output.

### Cost and the cache

- Every request changes, because the question ids are part of what's sent. So none of the 40 cached answers will match any more. The next screening of each sample folder calls Jev: **about 40 paid calls** across the 4 jobs. You chose this.
- The old answers are left in each job's folder, where they are never used again. **After the rename, I copy `.cache/` to the scratchpad, then clear all 4 job caches** so no dead entries remain. This costs nothing extra, because those entries could never be used again anyway.
- I won't run any real screening. You decide when to spend the 40 calls.

### Verification (no paid calls)

- `./mvnw test`: all Java and front-end tests pass.
- `git grep -i -E "competenc|comp_|comp-col"` finds nothing outside `.claude/skills/`. The only exception is the docs note about the old `comp_*` ids.
- The app starts with the renamed specs.
- **In the browser**, using a scratch cache folder with no API key:
  - The Jobs cards say "Skills".
  - The preview shows `skill_*` ids and **Not cached**.
  - The Screen page says "0 already cached, 10 will call Jev".
  - The what-if panel says "Skill weights".

## Phase 2.3: required `target_level`, sent as context

### Why

You added `target_level` after `title` in every spec: Vice President for backend and Technical Product Owner, Executive Director for HR and Application Designer. It's required, and for now it only gives Jev context in the state; no question names it.

### Changes

- **`JobSpec`:** a new `targetLevel` field, mapped from `target_level`. `JobSpecRepository.validate` requires it ("target_level is required"), so a spec without it stops the app at startup.
- **State:** `ScreeningService` sends `job: {title, target_level, summary}`.
- **Web app:**
  - The `JobSpec` and `SystemOneRequest` types.
  - A "Target level" line on each Jobs card.
  - The preview's State section shows the level.
- **Tests:**
  - Validation rejects a spec without `target_level`.
  - The preview request carries `job.target_level`.
  - The front-end fixtures and types include it.
- **Docs:**
  - `job-spec-guide.{md,html}`: the field and the state shape.
  - `jev-resume-screening.{md,html}`: the state shape.
  - `CLAUDE.md` and the README where the state is described.

### Cost and the cache

- The state is part of every request, so every request changes.
- The 20 answers cached after the rename (10 backend, 10 HR) are backed up to the scratchpad and wiped at your request.
- The next screening of each folder is about 10 paid calls.

## Phase 4: authoring (the spec editor)

### Why

Specs are written by hand in YAML today. The editor lets you create and change them in the app, with the same validation, and shows before you save what a change will cost. The first draft of this phase predates `target_level`, per-job caching and your habit of editing specs in the IDE, so this section folds those in.

### Editor (`/jobs/:id/edit`, `/jobs/new`)

- **Entry points:** **Edit** and **New job** buttons on the Jobs page, and **Save to spec** in the What-if panel. Save to spec opens the editor with the slider weights and thresholds filled in.
- **Fields:**
  - Job id: only on new jobs, and it can't be changed afterwards. It's suggested from the title in lowercase with `_`, and allows letters, digits, `_` and `-`.
  - Title, **target level** (required) and summary.
  - Must-haves: add, remove, reorder, any number. The director spec has 3.
  - Skills: id, weight, question and levels (2–10). Add, remove, reorder.
  - Thresholds.
- **Live checks:** the same rules as `JobSpecRepository.validate`, shown beside each field. The server checks again on save.
- **Live summary:**
  - The weight total, with the red ▲/▼ indicator from phase 2.1 when it isn't 1.00.
  - A "heavy" marker on each skill weighted 0.2 or more.
  - A read-only YAML preview.
- **Cost of the change**, updated as you type and shown again in the save confirmation:
  - **Free:** only weights or thresholds changed. The next screening re-ranks from the cache.
  - **Paid:** anything sent to Jev changed: title, target level, summary, a must-have's requirement, a skill's question or levels, or adding or removing a must-have or skill. The cache key covers the whole request, so every resume is asked again. The message names the fields that changed and estimates the cost, for example "the next screening of `<id>_candidates` makes about 10 calls to Jev".
- **Comments:** a note that hand-written YAML comments, other than the standard header, are dropped on save.
- **New jobs** also get a `resumes/<id>_candidates/` folder.

### Protecting hand edits in the IDE

- **The problem:** the app reads specs only at startup, and you also edit them in the IDE. Saving from the editor could overwrite changes made on disk since the app loaded the file.
- **The fix:** each spec is loaded with a version, a SHA-256 of the file's bytes, returned as `version` by `GET /api/jobs`.
  - `PUT /api/jobs/{id}` must send that version back. If the file on disk no longer matches it, the save is refused with **409** "changed on disk since it was loaded".
  - On a 409, the editor offers **Load the version on disk**, after a confirmation because it replaces the form's contents.
- **Reload without a restart:** `POST /api/jobs/{id}/reload` re-reads one spec from disk, and `POST /api/jobs/reload` re-reads all of them.
  - They validate first. An invalid file gives a 400 with the errors and keeps the version already loaded, so a typo in the IDE can't take the job out of the app.
  - The Jobs page gets a **Reload specs from disk** button, so IDE edits show up without restarting.

### Old cached answers after a paid change

- After a save that changes anything sent to Jev, every answer in the job's cache folder is out of date. The success message offers **Clear N old cached answers**, using `DELETE /api/screenings/{jobId}/cache` from phase 2.1.
- Declining keeps them, which is useful if you might undo the change. A free save makes no offer.

### Backend

- **`JobController`:**
  - `POST /api/jobs` returns 201. An id that already exists gives 409.
  - `PUT /api/jobs/{id}` takes a version and returns 200, or 409 on a version mismatch.
  - `POST /api/jobs/{id}/reload` and `POST /api/jobs/reload`.
  - `GET /api/jobs` now includes `version`.
- **`JobSpecRepository`:**
  - `validate` returns a list of error messages. At startup any error still stops the app; through the API they come back as a 400 via a new `InvalidJobSpecException`.
  - The job map becomes a `ConcurrentSkipListMap`, and each job stores its version.
  - Save writes snake_case YAML with the standard header comment to a temp file, checks the version, moves the file into place, then updates the map.
  - A job id must be letters, digits, `_` and `-` (already enforced), which also blocks paths outside `jobs/`.
- **Not included:** deleting specs, renaming a job id, and authentication. The editor writes files on the server, so add authentication before exposing the app beyond localhost.

### Tests and verification

- **Java** (all against a **temp `jobs/` folder**, never the checked-in specs):
  - create, update, and invalid spec → 400 with every error listed
  - a duplicate id on create → 409
  - a stale version → 409
  - `target_level` is required
  - reload picks up a hand edit; reloading an invalid file keeps the old version
  - the saved YAML loads back to an equal spec
- **Vitest:**
  - The cost classifier: weights and thresholds only is free; title, target level, summary, question, levels, or adding or removing a must-have or skill is paid.
  - The field checks match the server's rules.
  - The weight total indicator.
- **In the browser, with no API key and scratch copies of `jobs/`, `resumes/` and the cache:**
  - Create a job, and check its resume folder appears.
  - Edit its weights: the change is free, and the What-if panel's Save to spec opens it pre-filled.
  - Edit its summary: the save is paid, and the clear-cache offer appears.
  - Change the file on disk, then save from the editor: 409, then Load the version on disk.
  - Reload specs from disk.
  - Check 390px width and dark mode.

## Phase 4.1: Claude suggestions in the spec editor

### Why

Writing good questions and levels is the hard part of a spec. Each must stand on its own, point at `` `resume` ``, and describe concrete situations from least to most. Buttons in the editor ask a Claude model for a draft that follows those rules. You review it, then accept, edit or discard it. Nothing is saved until you press Save.

### What you get in the editor

- **Suggest question** (on a skill): drafts the skill's question from its id or name, an optional short hint, and the job's context.
- **Suggest levels** (on a skill): drafts the ordered levels for the skill's current question. A count picker (2–10, default 5) sits next to the button.
- **Suggest requirement** (on a must-have): drafts or tightens the requirement text so it reads well in the fixed Noul question: "Does `resume` show evidence that the candidate meets `requirement`?".
- **New skill from a description:** a one-line box, for example "experience running on-call for payment systems". It drafts a whole skill: id, question, levels (using the count picker) and a suggested weight.
- **Every suggestion appears in a review box** with **Accept**, **Try again** and **Discard**.
  - Accept fills in the field or fields. It never overwrites silently.
  - A new skill's suggested weight is added as-is, so the red ▲/▼ total shows whether to rebalance.
- **Context sent:** the editor's current, unsaved draft (title, target level, summary, must-haves, other skills), so a suggestion fits the job and doesn't overlap existing items.
- **Without `ANTHROPIC_API_KEY`:** the buttons are disabled with "Set ANTHROPIC_API_KEY to enable suggestions". The rest of the editor works as normal.
- **Cost note beside the buttons:** "Uses Claude (paid, about one small call per press)". Accepting a changed question or levels makes the save paid for Jev too, and phase 4's cost preview already covers that.

### Backend

- **Configuration** (`application.yml`, new `anthropic` section):
  - `api-key: ${ANTHROPIC_API_KEY:}`, read from the environment or the git-ignored `.env`, like the TypeSafe key.
  - `model: claude-sonnet-5`, `base-url: https://api.anthropic.com`, `timeout: 60s`, `max-tokens: 2000`, and `max-attempts: 3`.
  - Bound by a new `AnthropicProperties` record.
- **`assist/ClaudeClient`:** calls `POST /v1/messages` with the same `RestClient` style as `TypeSafeClient`. No new dependency.
  - Headers: `x-api-key` and `anthropic-version: 2023-06-01`.
  - Each suggestion type is a **forced tool call** with a JSON schema: `tool_choice` set to that tool. The answer is always typed JSON, for example `{question}`, `{levels: [...]}`, `{requirement}` or `{id, question, levels, weight}`.
  - Retries 429, 529 and 5xx with backoff. A missing key throws `AssistUnavailableException` (503). An upstream failure throws `AssistException` (502), carrying the API's own error message.
- **`assist/SpecAssistant`:** builds the prompts and checks the results.
  - **System prompt:** the spec-writing rules, kept in `src/main/resources/assist/spec-writing-rules.md`, drawn from section 7 of the job spec guide ("Writing a good spec") and `QuestionBuilder`'s fixed must-have question. The rules:
    - questions stand alone and refer to `` `resume` `` (and `` `job.summary` `` or `` `job.target_level` `` only when the question is about them)
    - one narrow judgment per skill
    - levels are ordered least to most, each a concrete situation that stands on its own
    - requirements have a clear boundary and are provable from a resume
    - ids are snake_case
  - **User prompt:** the draft spec, plus the target item and any hint or requested count.
  - **Checks, using the same rules as `JobSpecRepository.validate`:**
    - the text is not blank
    - the level count is exactly the one requested
    - a new id is snake_case and not already used
    - the weight is between 0 and 1

    The checks also warn when a question doesn't reference `` `resume` ``. A suggestion that fails a check is retried once, then reported as an error.
  - **Improve, don't copy** (added after the manual check, where Claude handed back existing levels word for word): when the item already has text, the prompt includes the current version and asks for an improved one.
    - Each tool has an optional `note` field for "already as good as it can be". The note is shown in the review box as "Claude: …".
    - A suggestion that repeats the current question or requirement comes back with a warning, for example "This is the current question, unchanged.".
    - For levels, the warning counts the ones that kept their wording, for example "2 of 5 levels keep their current wording.".
- **`web/AssistController`:**
  - `GET /api/assist` returns `{enabled, model}`, which the UI uses to enable or disable the buttons.
  - `POST /api/assist/skill-question`, `POST /api/assist/skill-levels` (with `count`), `POST /api/assist/must-have` and `POST /api/assist/skill`. Each takes `{draft, index, hint?, count?}`, where `index` is the 0-based skill or must-have (unused for a new skill, whose description goes in `hint`). A missing `index` gives 400. `count` defaults to 5.
- **Privacy:** only spec text is sent to Anthropic, never resumes or candidate data. The app logs each call's token usage without the prompt text.

### Tests and verification

- **Java** (`ClaudeClientTest` with `MockRestServiceServer`, like `TypeSafeClientTest`; `AssistApiTest` with a mocked client):
  - **The request:** model `claude-sonnet-5`, a forced `tool_choice`, the system prompt containing the rules, and the draft included.
  - **Parsing:** the tool-use output is parsed into typed results.
  - **Retries:** 429 and 529 are retried.
  - **Missing key:** 503, and `GET /api/assist` returns `enabled: false`.
  - **Bad suggestions:** a wrong level count or a duplicate id is retried once, then returns 502 with a clear message.
  - **Nothing leaks:** no resume text in any request.
- **Vitest:**
  - The buttons are disabled when assist is off.
  - A suggestion changes nothing until **Accept**.
  - The level-count picker sends `count`.
  - Accepting a new skill appends it and updates the weight total.
- **Manual check with your `ANTHROPIC_API_KEY`:** about 5 small paid calls, run only with your OK.
  - Suggest a question, levels (3 and 5) and a requirement for the Director of Engineering spec in a scratch copy of `jobs/`.
  - Draft one new skill from a description.
  - Check that each passes validation and reads well.
  - **Done.** All calls passed validation. New skills and requirements were good, but existing levels came back unchanged, which led to the improve-don't-copy fix above. After the fix, levels were genuinely revised; a question for a skill that already had a well-written one could still come back more generic, which is what Discard is for.
- **Docs:**
  - README: `ANTHROPIC_API_KEY`, and what the buttons do.
  - `CLAUDE.md`: the assist flow, and the note that the rules file must stay in sync with section 7 of the guide.
  - Job spec guide (md and html): a short "Suggestions" note.

## Phase 4.2: Claude-drafted job overview

### Why

The summary is the job overview Jev reads with every resume, and `domain_relevance`-style questions compare against it (`` `job.summary` ``). Writing a good one from scratch is slow. You describe the job in a few words and Claude drafts the overview in the same style as the payments spec.

### What you get in the editor

- Under the **Summary** field, a titled panel, **"✨ Optional: describe the job and let AI draft the summary (job overview)"**, set apart with its own border so it's clearly a separate, optional step. (Changed after review: a bare one-line box under Summary didn't say what it was for.)
  - A visible label, **Describe the job in a few words**, on a 2-line text area, with an example placeholder: "MD of product for our payments platform, owns pay-in, routing and settlement, leads directors, heavy PCI".
  - A hint under it: Claude uses the description and the rest of the spec to draft a summary for you to review, and nothing changes until you accept it. When there's already a summary, the description can be left blank and Claude improves the current one.
  - The **✨ Draft job overview** button sits inside the panel.
- **Context sent:** your description plus the unsaved draft (title, target level, current summary, must-haves, skills), so the overview fits the rest of the spec. Never resumes.
- **Format:** like `md_prod_payments.yaml`: an opening paragraph of 2–4 sentences on the role's scope and purpose, then a "Core Responsibilities" line and 4–6 bullets (`• Label: one or two sentences`). About 150–300 words, plain text, no Markdown.
- **Review box** as in phase 4.1: **Accept** replaces the Summary field, **Try again** asks for another draft (edit the description to steer it), **Discard** closes it.
- **When a summary already exists:** the description is optional, and Claude is asked to improve the current summary, not repeat it. The same `note` field and "This is the current summary, unchanged." warning apply.
- **When the summary is empty:** the button is disabled until there is a description.
- **Cost:** one small paid Claude call per press. Accepting changes the summary, which is sent to Jev with every resume, so the next save is paid and the next screening asks every resume again. Phase 4's cost note already says so.

### Backend

- **`POST /api/assist/summary`** with `{draft, hint}`, where `hint` holds the description (as for a new skill). 400 when both the description and the current summary are blank.
- **`SpecAssistant.jobSummary(draft, description)`:** a forced `suggest_summary` tool returning `{summary, note?}`.
  - **Checks (retried once):** the summary isn't blank and is at most 400 words, since it's sent with every resume.
  - **Warnings:** unchanged from the current summary, and Claude's note.
- **Rules:** a "Writing the job summary" section in `spec-writing-rules.md`: the format above; describe scope, responsibilities and the kind of experience the role needs; stay consistent with the title, target level and must-haves; job-related only (nothing about age, gender, nationality or "culture fit"); no salary, benefits, location logistics or equal-opportunity boilerplate, because Jev can't use them; no marketing language. The same guidance goes into section 7 of the job spec guide (md and html).

### Tests and verification

- **Java:** `SpecAssistantTest`: the prompt includes the description and the current summary with the improve instruction; an over-long or blank summary is retried and then reported; the unchanged warning and the note. `AssistApiTest`: the endpoint, and 400 when there's nothing to go on.
- **Vitest:** the button is disabled with no description and no summary; accepting fills the Summary field; nothing changes before Accept; the cost note turns paid after Accept.
- **Manual check with your key:** about 2 small paid calls, run only with your OK. Draft an overview for a new job from a one-line description, and improve the existing payments summary, in a scratch copy of `jobs/`.
  - **Done.** Both passed first time (about 6 s each, 216 and 218 words, no warnings). The new-job draft was good as it stood. The improved payments summary was a real rewrite, but one sentence read as a requirement and it reused wording from the must-haves and skill levels, so review before accepting.
- **Docs:** README (the endpoint and the button), `CLAUDE.md` (assist section), and the job spec guide.

## Phase 4.3: must-have names on the Jobs and Results pages

### Why

Must-haves show only their requirement sentence on the Jobs page, and on the Results page they share one column of unlabelled chips (the requirement appears only on hover). You can't tell at a glance which chip is which. Front end only, except the reasons change below.

### Changes

- **Jobs page, each card:** a short bold subtitle above each requirement, made from the must-have's id with `humanize` (for example **Product owner professional**), the same way skills are named.
- **Results table:** the single "Must-haves" column becomes **one column per must-have**, in the same place (after the skill columns).
  - **Header:** the must-have's name, with a small "probability met" line under it, like the skill headers' "score · conf.". The requirement sentence moves to the header's hover text, as skill headers show their question.
  - **Cell:** the same coloured chip with the probability, banded by the current (what-if or spec) thresholds. The chips lose their own hover text.
  - **Sorting:** clicking a header sorts by that must-have's probability (highest first, then toggles), like the skill columns.
- **Candidate side panel:** the Must-haves list gets the same subtitle above each requirement as the Jobs page.
- **Reasons use the same names** (added after review): `not shown: Product owner professional (0.03)` instead of `not shown: product_owner_professional (0.03)`, and likewise `unclear:` and `uncertain:`. This one is not front-end only: `ScreeningPolicy` (Java, a new `label(id)` helper matching the web app's `humanize`) and its copy in `policy.ts` change together, so the API's `reasons` and the CSV change too (a breaking change for scripts that parse ids out of reasons). The three parity fixtures' recorded reasons are rewritten to the new wording; their scores are untouched. Docs: the reason examples in both guides (md and html).

### Tests and verification

- **Vitest:** the job card shows each must-have's name above its requirement; the Results table has one header per must-have with "probability met"; sorting by a must-have orders the rows by its probability; the side panel shows the names.
- **In the browser,** on the cached backend results with no API key (no paid calls): desktop, and dark mode at 390px with no sideways page scroll.

## Phase 4.4: candidate panel docked beside the results

### Why

The candidate panel opens over the results table, covering it. You want it docked on the right, beside the table, with a vertical splitter to resize the two. Front end only.

### Layout

- **With a candidate selected,** the results area splits into two panes. Left: the status filters, the table and the note under it. Right: the candidate panel (Scores, Resume, What was sent to Jev). The title, buttons, summary line and What-if panel stay full width above both. No overlay or shadow; nothing is covered.
- **Full width while split:** the page drops its usual 1,280px limit, so the table isn't squeezed. It returns to normal when the panel closes.
- **Window-height panes:** while split, the table pane is as tall as the window and scrolls on its own both ways, so its sideways scrollbar is always on screen and the column headers stay pinned. The panel is also window height, stays in view, and scrolls on its own.
- **Closing:** **Close** or Escape removes the split; the results view goes back to its normal width and height.
- **Switching:** clicking another row shows that candidate; the open tab stays.
- **Narrow screens** (900px or less): no room for two panes, so the panel opens full screen over the table, as now.

### The splitter

- A thin vertical bar with a grip. Drag it to resize; the table keeps at least 360px and the panel at least 320px, also when the window is resized.
- Default: the panel takes about 40% of the width. Double-clicking the bar resets it.
- The last width is remembered in this browser (`localStorage`, a convenience only; the page works without it).
- Keyboard and screen readers: the bar is focusable (`role="separator"`, vertical, with its current width); Left and Right arrows move it 24px.

### Tests and verification

- **Vitest:** selecting a row opens the split with the separator; arrow keys change the width; double-click resets it; a remembered width is used; Escape and Close remove the split.
- **In the browser,** on cached results with no API key (no paid calls): drag the splitter and check the limits; scroll the table sideways and down with the panel open; close and check the table returns to normal; 390px dark mode falls back to the full-screen panel with no sideways page scroll.

## Phase 4.5: screening workflow diagram (docs)

### Why

A visual explanation of how the Java classes screen resumes against a job spec, and of Jev's role: it is the only source of judgments, and the code only prepares what it reads, calls it, caches its answers and applies the spec afterwards.

### The page (built)

- `docs/screening-workflow.html`, a standalone page (no .md pair, no build step), linked from the README and `CLAUDE.md`.
- The screening classes by package, with arrows for calls and data, in seven steps: startup, request, load, redact and build the request, Jev judges, decide, rank and respond.
- Jev is the largest node, outside a dashed "Java app | TypeSafe API" line, labelled "the only source of judgments", with what it reads, answers and never sees, and a live status (not involved, judging, answered earlier, unavailable). Its answers keep Jev's colour until `ScreeningPolicy` applies the spec.
- Controls: play/pause, back, next, restart, speed, and scenarios: all cached, cache miss (with a 429 retry) and error (503s → `TypeSafeException` → 502). Selecting a class shows its file, role, methods and relation to Jev.
- Light and dark themes; reduced motion shows each step without movement; print gives the diagram plus the seven steps written out.
- Facts were checked against the code by an independent agent; its 8 wording fixes are in.

### One screen without vertical scrolling

- **Laptop and desktop windows (about 1100 × 700 and larger):** the page fills the window and never scrolls vertically.
  - One top row: title, playback controls, speed, scenario, and step buttons 1–7 (replacing the full steps list). The intro moves into an "About" toggle.
  - The diagram fills the remaining space and scales to fit; a right sidebar (about 340px) holds the step caption with its Jev line, and the selected class's details below (that panel scrolls on its own if needed).
  - The legend is one line along the bottom.
  - Text inside the diagram is enlarged where the boxes allow, to stay readable when scaled down.
  - A **Zoom** button switches to the full-width scrolling view for small laptop screens, and back with **Fit to screen**.
- **Phones and small windows:** the current scrolling layout. **Print:** unchanged.
- **Checks:** screenshots at 1920×1080, 1440×900 and 1280×720 with no vertical page scroll and no text overflowing its box; Zoom; phone width in dark mode; print.

## Verification

- **Backend:** `./mvnw test`. The existing 38 tests must still pass. New tests:
  - **Folder listing:** it lists folders, and paths outside `resumes/` are rejected.
  - **Resume text:** redacted text comes back from a folder file and from an upload.
  - **Resume saving:**
    - Create a folder, then save a `.txt`, a `.md` and a `.pdf` resume. Each reads back unchanged, and the PDF's bytes are identical to the original.
    - Saving `candidate_x.txt` when `candidate_x.pdf` exists returns 409. With `overwrite=true`, the `.txt` replaces the `.pdf`.
    - Rejected: bad folder or file names, unsupported extensions, paths outside `resumes/`, empty text, and a corrupt PDF.
    - A PDF upload comes back as extracted text.
    - These tests write to a **temp `resumes/` folder**.
  - **Preview:**
    - `name` selects the right resume, and leaving it out still returns the first one.
    - `preview-text` produces the same request as saving that text to a file and previewing it.
    - The resume in the request is redacted.
    - `X-Answer-Cached` is `true` after a mocked screening of that resume and `false` before.
    - `cache-status` counts correctly.
    - Neither endpoint calls TypeSafe (`verifyNoInteractions(client)`).
  - **Spec saving:** see [Phase 4](#phase-4-authoring-the-spec-editor). These tests write to a **temp `jobs/` folder**, never the checked-in specs, because the existing tests depend on `senior_backend_engineer.yaml`.
- **Front end:** `npm test` (Vitest).
  - `policy.ts` tests reproduce the cases in `ScreeningPolicyTest`, plus a parity check against a real server report (see phase 2).
  - Component tests cover status pills and error messages.
- **End to end:** `./mvnw spring-boot:run`, then open `localhost:8080` and check:
  - A folder screening of `senior_backend_engineer_candidates` works. Its answers are already cached, so it costs nothing.
  - The what-if sliders re-rank the results.
  - The resume viewer opens for a candidate.
  - CSV downloads.
  - With no API key set, preview `candidate_c` from the Screen page. Check that:
    - the redactions are highlighted
    - 2 must-have cards and 5 skill cards appear
    - Raw JSON copies
    - the badge reads **Cached**
  - Edit a resume's text in a review card, then preview it: the change shows before saving.
  - A new spec can be created and screened.
  - Upload one `.pdf`, one `.txt` and one `.md` resume:
    - Edit a line in the `.txt` and the `.md`.
    - Keep the PDF as it is, then save all three to a new folder.
    - Re-open each one and check the edits were kept and the PDF displays.
    - Screen the folder.
  - Open the saved PDF, choose **Edit as text**, save it as `.txt`, and check the PDF is gone and the new text screens.
  - The app works at 390px phone width and in dark mode.

## Out of scope (for now)

Authentication (the spec editor and resume saving write files on the server, so add auth before exposing this beyond localhost), editing a PDF's layout (a PDF is kept as uploaded or converted to text), deleting specs or resumes, and screening history or persistence.

## Decision needed before phase 3: real resumes and git

`resumes/` is committed to git and pushed to GitHub. Real candidate resumes saved through the UI would contain personal data, including names and contact details, and would be picked up by the next `git add`. Recommendation: keep the five sample folders tracked, and ignore every other folder under `resumes/` (a `resumes/*` rule plus `!` exceptions for the sample folders). Alternatively, keep real resumes outside the repo by pointing `screening.resumes-dir` somewhere else.
