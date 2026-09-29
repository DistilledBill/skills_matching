# Web front end for the resume screening service

## Context

Today the service is used through curl: 4 REST endpoints (`GET /api/jobs`, `POST /api/screenings/{jobId}` uploads, `POST /api/screenings/{jobId}/folder?path=`, `POST .../preview`) with JSON or CSV output. The user wants a web application that exposes all of that, plus these extras: a folder picker, what-if tuning, a resume viewer, a job spec editor, and **resume management**. Resume management means uploading `.pdf`, `.txt` and `.md` resumes, viewing and editing their content, and saving them into a chosen folder. At the app's centre is a rich ranked-results view.

**Chosen approach:** a React + TypeScript SPA (Vite) in `frontend/`. Maven builds it into Spring Boot's static resources, so there is one app, one port (`localhost:8080`), one jar, and no CORS. The existing REST API stays unchanged. The new backend endpoints are additive.

## Screens

1. **Jobs** (`/`): a card per spec showing title, summary, must-haves, competencies with weights, and thresholds. Actions: Screen, Edit, New job.
2. **Screen** (`/jobs/:id/screen`): pick the source.
   - **Folder:** a dropdown from the new folder endpoint, defaulting to `<id>_candidates`.
   - **Upload:** drag and drop `.txt`, `.md` or `.pdf` files.
   - Buttons: **Preview what Jev sees**, which opens the preview panel below for any resume in the selection and costs nothing, and **Run screening**. A summary line shows how many of the selected resumes already have a cached answer, so you know how many calls to Jev a run will make.
   - Errors in the API's standard JSON format (RFC 9457 `ProblemDetail`) show inline. A 503 means there's no API key and a 502 means TypeSafe failed; each gets a clear message.
3. **Results** (`/jobs/:id/results`): the main view.
   - **Ranked table:** rank, candidate, status pill (meets / review / missing), composite as a bar, one mini bar per competency (faded when confidence is low), must-have probabilities, and reasons.
   - **Controls:** filter by status, sort by any column, and a **Download CSV** button (`format=csv`, fetched as a file).
   - **Row click:** opens the **resume viewer** side panel, with the redacted text next to that candidate's scores. A **What was sent to Jev** tab shows the preview panel for that candidate.
   - **What-if panel:** sliders for each competency weight plus `must_have_pass`, `must_have_fail` and `min_confidence`. The ranking recomputes instantly in the browser and arrows show rank changes. **Reset** restores the spec's values; **Save to spec** opens the editor pre-filled. After a save, the next screening re-ranks from the cache for free, because weights and thresholds aren't part of the request.
4. **Spec editor** (`/jobs/:id/edit`, `/jobs/new`): a form for title, summary, must-haves (add, remove, reorder), and competencies (weight, question, levels list), plus thresholds.
   - It checks the same rules as `JobSpecRepository.validate` on the client, and the server re-checks on save.
   - It shows the running weight total and whether each competency counts as heavy (0.2 or more).
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
  - **Competencies:** one card per competency (`comp_<id>`), showing the question and its numbered levels. Where it helps, each card notes that the weight and thresholds are never sent to Jev.
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
- **Read a resume:** `GET /api/resume-folders/{folder}/resumes/{fileName}?redacted=true|false` returns `{fileName, format, text}`. For a PDF, `text` is the extracted text. `redacted=true` serves the resume viewer, and `redacted=false` gives the original text for editing.
- **Get the original file:** `GET /api/resume-folders/{folder}/files/{fileName}` returns the file as stored, with its content type (`application/pdf`, `text/plain` or `text/markdown`). The PDF viewer uses this.
- **Extract text from uploads:** `POST /api/resumes/text?redacted=true|false` (multipart) returns `[{fileName, suggestedName, format, text}]` for the upload review cards and for the viewer on upload screenings.
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
  - A job id must match `^[a-z0-9_]+$`, which also blocks paths outside `jobs/`.
  - `validate` returns a list of error messages that the API returns as 400 responses, via a new `InvalidJobSpecException` handled in `ApiExceptionHandler`. Loading at startup still fails fast.
  - Save writes to a temp file and then moves it into place, the same pattern as `AnswerCache.put`. It writes snake_case YAML with the standard header comment, then swaps in the new spec.
  - The job map becomes safe for concurrent reads and writes (a `ConcurrentSkipListMap`).
  - Hand-written YAML comments beyond the standard header are lost on save. The editor will warn about this.
- **Serving the app:** a small `SpaForwardController` forwards any path that isn't under `/api` and has no file extension to `index.html`, so client-side page URLs work on reload.

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

1. **Scaffold and core flow:** Maven and Vite wiring, the SPA forwarding, the folder endpoint, the preview panel with its endpoints and cache status, and the Jobs, Screen, Results and CSV screens.
2. **Analysis:** what-if tuning (`policy.ts`), the resume viewer and its read endpoints.
3. **Resume management:** the Resumes screen (upload, review, edit and save), plus the create-folder and save-resume endpoints.
4. **Authoring:** the spec editor, the save endpoints and the repository changes.

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
  - **Spec saving:** create, update and invalid-spec cases return 400 with the error messages. These tests write to a **temp `jobs/` folder**, never the checked-in specs, because the existing tests depend on `senior_backend_engineer.yaml`.
- **Front end:** `npm test` (Vitest).
  - `policy.ts` tests reproduce the cases in `ScreeningPolicyTest` and the recorded A–D results from the docs: composites 0.906 / 0.706 / 0.368 / 0.649, and candidate_c `missing`.
  - Component tests cover status pills and error messages.
- **End to end:** `./mvnw spring-boot:run`, then open `localhost:8080` and check:
  - A folder screening of `senior_backend_engineer_candidates` works. Its answers are already cached, so it costs nothing.
  - The what-if sliders re-rank the results.
  - The resume viewer opens for a candidate.
  - CSV downloads.
  - With no API key set, preview `candidate_c` from the Screen page. Check that:
    - the redactions are highlighted
    - 2 must-have cards and 5 competency cards appear
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

`resumes/` is committed to git and pushed to GitHub. Real candidate resumes saved through the UI would contain personal data, including names and contact details, and would be picked up by the next `git add`. Recommendation: keep the four sample folders tracked, and ignore every other folder under `resumes/` (a `resumes/*` rule plus `!` exceptions for the sample folders). Alternatively, keep real resumes outside the repo by pointing `screening.resumes-dir` somewhere else.
