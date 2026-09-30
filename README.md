# Resume screening with TypeSafe

A Spring Boot REST API that ranks resumes against a job spec using [TypeSafe](https://docs.typesafe.ai) judgments, with weights, gates, and human-review rules kept in code.

**Docs:** [How Jev works and what the scores mean](docs/jev-resume-screening.md) ([HTML version](docs/jev-resume-screening.html)) · [Job spec guide: structure, thresholds and validation](docs/job-spec-guide.md) ([HTML version](docs/job-spec-guide.html)).

## Run

Requires JDK 21+.

Put your API key (from https://console.typesafe.ai) in a git-ignored `.env` file in the project root:

```sh
cp .env.example .env                 # then set TYPESAFE_API_KEY=... in .env
./mvnw spring-boot:run
```

You can also `export TYPESAFE_API_KEY=...`. An exported variable takes priority over `.env`. `.env` is read from the working directory, so start the app from the project root.

The app starts without a key. Listing jobs and previewing requests still work, and screening returns 503 until a key is set.

## Web app

`./mvnw spring-boot:run` also builds and serves the web app at http://localhost:8080. From there you can pick a job, screen a resume folder or uploaded files, see the ranked results, download the CSV, and preview exactly what each resume sends to Jev. On the results page, **What-if tuning** re-ranks the candidates in the browser under other weights and thresholds (nothing is saved, and no API call is made), and selecting a candidate shows their resume with contact details next to their scores. The preview never calls Jev, so it's free and works without an API key. The Screen page shows how many resumes already have a cached answer before you run a screening.

The app lives in `frontend/` (React, TypeScript and Vite). The Maven build downloads its own Node into `frontend/.node`, so you don't need Node installed. To work on the UI with live reload, run the Spring app, then in another terminal:

```sh
cd frontend && npm install && npm run dev   # http://localhost:5173, proxies /api to :8080 (needs Node 20.11+)
```

Add `-Dskip.frontend` to any Maven command to skip building and testing the web app.

## API

| Method | Path | Does |
| --- | --- | --- |
| GET | `/api/jobs` | List the loaded job specs |
| POST | `/api/screenings/{jobId}` | Screen uploaded resumes (multipart `files`: .txt, .md, .pdf) |
| POST | `/api/screenings/{jobId}/folder?path=` | Screen a folder inside `resumes/`, normally the job's own folder such as `senior_backend_engineer_candidates` (a blank `path` means `resumes/` itself, which holds no resumes) |
| POST | `/api/screenings/{jobId}/preview` | Show the TypeSafe request for one resume (uploaded `files` or `?path=`; `name` picks the resume, otherwise the first) without calling the API. The `X-Answer-Cached` header says whether screening it would be free |
| POST | `/api/screenings/{jobId}/preview-text` | The same, for resume text that isn't saved yet (JSON `{name, text}`) |
| POST | `/api/screenings/{jobId}/cache-status` | `{total, cached}` for uploaded `files` or `?path=`: how many resumes would be answered from the cache |
| GET | `/api/resume-folders` | Folders under `resumes/` with their resume counts |
| GET | `/api/resume-folders/{folder}` | The resume files in one folder |
| GET | `/api/resume-folders/{folder}/resumes/{fileName}?redacted=` | One resume's text (PDFs extracted). `redacted=false` includes contact details; `true`, the default, is the text as Jev receives it |
| GET | `/api/resume-folders/{folder}/files/{fileName}` | The stored file, byte for byte (a PDF opens in the browser) |
| GET | `/api/screenings/{jobId}/cache` | `{count}`: how many answers are cached for the job |
| DELETE | `/api/screenings/{jobId}/cache` | Delete every cached answer for the job (`{removed}`); the next screening calls Jev again |
| POST | `/api/resumes/text?redacted=` | The text of uploaded resumes (multipart `files`), with the same `redacted` rules |

Add `?format=csv` to either screening endpoint to get a CSV instead of JSON.

```sh
curl -X POST 'localhost:8080/api/screenings/senior_backend_engineer/folder?path=senior_backend_engineer_candidates'
curl -F files=@resumes/senior_backend_engineer_candidates/candidate_a.txt \
     -F files=@resumes/senior_backend_engineer_candidates/candidate_b.txt \
     'localhost:8080/api/screenings/senior_backend_engineer?format=csv'
curl -X POST 'localhost:8080/api/screenings/senior_backend_engineer/preview?path=senior_backend_engineer_candidates'
```

Resumes are kept in one subfolder of `resumes/` per job spec, named `<job id>_candidates` (for example `resumes/senior_backend_engineer_candidates/`). Folder screening reads only the files directly inside the folder you name. It does not look in subfolders.

Included roles:

| Job id | Title | Sample resumes |
| --- | --- | --- |
| `director_of_engineering` | Director of Engineering | 10 in `resumes/director_of_engineering_candidates/` |
| `senior_backend_engineer` | Senior Backend Engineer | 10 in `resumes/senior_backend_engineer_candidates/` |
| `senior_hr_product_owner` | Senior Human Resources Product Owner | 10 in `resumes/senior_hr_product_owner_candidates/` |
| `technical_product_owner` | Technical Product Owner | 10 in `resumes/technical_product_owner_candidates/` |
| `senior_software_application_designer` | Senior Software Application Designer | 10 in `resumes/senior_software_application_designer_candidates/` |

Errors are returned as RFC 9457 problem details: 404 for an unknown job, 400 for a bad file or a folder outside `resumes/`, 503 when there is no API key, and 502 when TypeSafe fails.

## How it works

For each resume, one request asks every question in parallel:

| Question | Primitive | Used for |
| --- | --- | --- |
| One per `must_haves` entry | Noul: does the resume show evidence of this requirement? | Gate: `meets` / `review` / `missing` |
| One per `skills` entry | Score on the levels you write | Weighted composite that sets the rank |

The code then:
- removes emails, phone numbers and profile links before sending the resume ([Redactor](src/main/java/com/example/resumescreening/screening/Redactor.java))
- sets a candidate to `missing` if any must-have falls below `must_have_fail`. Values between the two thresholds go to `review`
- sends the candidate to `review` if a skill weighted 0.2 or more has a Score confidence below `min_confidence`
- ranks by status, then by the weighted composite ([ScreeningPolicy](src/main/java/com/example/resumescreening/screening/ScreeningPolicy.java))

TypeSafe has no Java SDK, so [TypeSafeClient](src/main/java/com/example/resumescreening/typesafe/TypeSafeClient.java) calls `POST /v1/systemone` directly. It retries 429, 500, 502, 503, 504 and 529 responses and connection errors with exponential backoff, and caps concurrent requests at `typesafe.max-concurrent`.

Raw answers are cached in `.cache/<job id>/`, keyed by the full request. The Screen page's **Clear cache for this job** button deletes one job's answers. Changing a `weight` or a threshold and restarting re-ranks with no API calls. Changing a question or a level re-asks only the affected requests. Settings are in [application.yml](src/main/resources/application.yml).

## Adding a role

Copy any spec in `jobs/` to `jobs/<id>.yaml`, put its resumes in `resumes/<id>_candidates/`, and restart; specs are loaded and validated at startup. The [job spec guide](docs/job-spec-guide.md) explains every field, the thresholds and the validation rules. Keep every criterion job-related, and write Score levels as concrete situations that make sense on their own. Before you trust a ranking, check it against a set of resumes your recruiters have already judged, and tune the thresholds on that set. The output is meant to decide which resumes a person reads first. It should not make the hiring decision.

## Test

```sh
./mvnw test
```
