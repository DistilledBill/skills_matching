# Job spec guide

What a job spec is, what each part of it does, and how the screener turns it into a ranked shortlist.

*Co-designed and implemented with Claude and Bill Stamatakis*

> **Who this is for.** Anyone writing or tuning a job spec: a hiring manager, a recruiter, or an engineer adding a role. You don't need to read the Java code. For how the Jev model itself works, see [Jev and the resume screener](jev-resume-screening.md). Real numbers in this guide come from that document's run of `jobs/senior_backend_engineer.yaml` on `candidate_a` to `candidate_d`.

**Contents**

1. [What a job spec is for](#1-what-a-job-spec-is-for)
2. [The whole file at a glance](#2-the-whole-file-at-a-glance)
3. [Section by section](#3-section-by-section)
4. [How a spec becomes a request](#4-how-a-spec-becomes-a-request)
5. [Validation rules](#5-validation-rules)
6. [What each change costs](#6-what-each-change-costs)
7. [Writing a good spec](#7-writing-a-good-spec)

---

## 1. What a job spec is for

A job spec is one YAML file in [`jobs/`](../jobs/) that describes a role. It is the only place the screener learns what to look for.

The spec splits the work in two:

| Part of the spec | Who uses it | What it does |
| --- | --- | --- |
| `title`, `target_level`, `summary`, `must_haves`, `skills` (the requirement text, questions and levels) | **Jev**, the model | Decides **what the resume shows**. This is the judgment. |
| `weight` on each skill, and `thresholds` | **The code**, in [`ScreeningPolicy`](../src/main/java/com/example/resumescreening/screening/ScreeningPolicy.java) | Decides **what that means for this job**: status, composite and rank. This is the policy. |

Jev never sees the weights or thresholds. That keeps the policy readable, testable, and cheap to change.

Conventions:

- **The file name is the job id.** `jobs/technical_product_owner.yaml` is screened at `/api/screenings/technical_product_owner`.
- **Resumes for the job go in `resumes/<job id>_candidates/`**, for example `resumes/technical_product_owner_candidates/`.
- **Specs are loaded and validated when the app starts.** A spec with an error stops the app from starting, and the error message names the file and the problem.

---

## 2. The whole file at a glance

[`jobs/senior_backend_engineer.yaml`](../jobs/senior_backend_engineer.yaml), shortened, with what each part becomes:

```yaml
# Sent to Jev with every resume
title: Senior Backend Engineer

# Sent to Jev with every resume, as context. No question
# names `job.target_level` yet
target_level: Vice President

# Sent to Jev with every resume. Only questions that
# name `job.summary` use it
summary: >
  Build and operate the Python services behind our
  payments platform: ...

# Each must-have becomes a yes/no question (Noul)
must_haves:
  # Question id: must_python_professional
  - id: python_professional
    requirement: Has used Python in a paid engineering role
      (not only coursework or hobby projects)
  - id: backend_services
    requirement: Has built or maintained server-side services
      or APIs that ran in production

# Each skill becomes a graded question (Score)
skills:
  # Question id: skill_python_depth
  - id: python_depth
    # Used by the code only, never sent to Jev
    weight: 0.30
    question: How deep is the candidate's professional
      Python experience, based on `resume`?
    # Least to most; Jev picks where the resume sits
    levels:
      - No Python experience mentioned
      - Python mentioned, but no detail about what was built
      - Used Python on some projects, with a few specifics
      - Python was the primary language across multiple
        roles or projects
      - Deep Python expertise, such as performance tuning,
        library internals, or authoring widely used packages

  # ... distributed_systems 0.25, data_stores 0.15,
  # technical_leadership 0.20 ...

  - id: domain_relevance
    weight: 0.10
    question: How relevant is the candidate's industry
      experience to `job.summary`?
    levels:
      - Unrelated industry with no transferable domain exposure
      - Adjacent domain, such as general e-commerce or B2B SaaS
      - Fintech, payments, or other regulated financial systems

# Used by the code only, never sent to Jev
thresholds:
  # At or above: requirement met
  must_have_pass: 0.80
  # Below: requirement not shown; between: human review
  must_have_fail: 0.20
  # A Score below this on a skill weighted 0.2 or
  # more goes to review
  min_confidence: 0.45
```

A shortened excerpt, reformatted to fit the page. Long values are wrapped over several lines, which YAML reads as the same text. The real file is [`jobs/senior_backend_engineer.yaml`](../jobs/senior_backend_engineer.yaml).

---

## 3. Section by section

### 3.1 `title`, `target_level` and `summary`

All three go into the **state**, the content Jev reads, together with the resume ([`ScreeningService`](../src/main/java/com/example/resumescreening/screening/ScreeningService.java)):

```
state = {
  job:    { title, target_level, summary },
  resume: "<resume text, contact details removed>"
}
```

Every question sees the whole state, but a question only **points at** the parts it names in backticks. In all six included specs, the only question that names `` `job.summary` `` is `domain_relevance`, weighted 0.10. So:

- **The summary affects at most 10% of the composite**, through one industry-relevance question.
- **The summary never affects the must-haves**, so it can't change whether a candidate is `meets`, `review` or `missing`.
- **Editing the summary re-asks every question.** Answers are cached by the full request, and the summary is part of every request. See [section 6](#6-what-each-change-costs).

`target_level` is the seniority the role is hired at, for example `Vice President` or `Executive Director`. It is required. For now it is **context only**: no question names `` `job.target_level` ``, so Jev can see it but no answer is asked about it. To use it, add a skill whose question names it, for example "How closely does the seniority shown in `` `resume` `` match `` `job.target_level` ``?". Like the summary, editing it re-asks every question for the job.

Write the summary as a description of the role and its setting (industry, product, stack, what the target level means here): an opening paragraph of 2–4 sentences on the role's scope, then a "Core Responsibilities" line with 4–6 bullets, about 150–300 words in all ([`md_prod_payments`](../jobs/md_prod_payments.yaml) is an example). It is context, not a list of requirements. Put requirements in `must_haves` and `skills`, where each one gets its own answer. Leave out salary, benefits, location and equal-opportunity text: Jev can't use it, and it lengthens every request.

### 3.2 `must_haves`: the gate

Each must-have becomes one **Noul**, a yes/no question. The question and answer definitions are the same for every must-have and are fixed in [`QuestionBuilder`](../src/main/java/com/example/resumescreening/screening/QuestionBuilder.java). Only your `requirement` text changes:

```
requirement: <your text>
question:    Does `resume` show evidence that the candidate
             meets `requirement`?
true:        The resume describes experience that satisfies
             the requirement
false:       The resume does not mention it, or what it
             describes falls short of the requirement
```

Jev answers with **the probability that the answer is yes**, from 0 to 1. This is not a grade: 0.5 means "can't tell", not "half qualified". The thresholds in [section 3.4](#34-thresholds-turning-numbers-into-a-status) turn that probability into met, review or missing.

| Field | Rules |
| --- | --- |
| `id` | snake_case, unique in the file. Becomes the question id `must_<id>` and appears in the output and in reasons such as `not shown: python_professional (0.03)`. |
| `requirement` | Required. One condition with a clear yes/no boundary. |

Writing requirements:

- **Make the boundary explicit.** "Has used Python in a paid engineering role (not only coursework or hobby projects)" says exactly what counts and what doesn't.
- **Keep it to things a resume can show.** "Is a fast learner" isn't checkable; "Has owned a product backlog in a paid role" is.
- **Keep the list short.** A candidate who fails any single must-have ranks last, however strong they are elsewhere. The included specs use two each, except `director_of_engineering`, which adds a third for people management. An empty list (`must_haves: []`) is allowed and means there is no gate.

### 3.3 `skills`: the ranking

Each skill becomes one **Score**: Jev reads your levels and returns a probability for each level. `score` is the probability-weighted level, so it can land between levels (for example 3.63 on a 0–4 scale).

| Field | Rules |
| --- | --- |
| `id` | snake_case, unique in the file. Becomes the question id `skill_<id>`. |
| `weight` | A positive number. It is used only by the code. |
| `question` | Required. It names what it looks at in backticks: `` `resume` ``, or `` `job.summary` `` for the domain question. |
| `levels` | 2 to 10 entries, from least to most. |

**`weight`** is relative. The composite divides each weight by the total, so weights don't have to add up to 1, although the included specs do add up to 1.00 so each weight reads as a share. A weight of **0.20 or more** also makes a skill "heavy", which matters for `min_confidence` (see 3.4).

**`question`** must make sense on its own. The id is never sent to Jev, so `skill_python_depth` tells the model nothing. The question has to say what to judge.

**`levels`** are the answer scale. Each level should describe **one concrete situation that makes sense on its own**, such as "Owned the design of a multi-service system handling significant traffic or data volume". Avoid a word like "good", and avoid "more than the previous level". Vague or overlapping levels show up as low confidence.

How scores become one number, computed in code:

```
normalized = score / (number of levels − 1)     → 0 to 1
composite  = Σ (weight / total weight × normalized)
```

Real example, candidate_a:

| Skill | Weight | Normalized | Contribution |
| --- | --- | --- | --- |
| python_depth | 0.30 | 0.9075 (3.63 / 4) | 0.272 |
| distributed_systems | 0.25 | 0.935 | 0.234 |
| data_stores | 0.15 | 1.000 (3.0 / 3) | 0.150 |
| technical_leadership | 0.20 | 0.750 | 0.150 |
| domain_relevance | 0.10 | 1.000 | 0.100 |
| **Composite** | 1.00 | | **0.906** |

### 3.4 `thresholds`: turning numbers into a status

The thresholds decide each candidate's status: `meets`, `review` or `missing`. Jev never sees them.

**`must_have_fail` and `must_have_pass`** split each must-have's probability into three bands:

```
0.0 ──────── 0.20 ──────────────────── 0.80 ──────── 1.0
   not shown  │         unclear         │    met
   → missing  │        → review         │
```

| Must-have probability | Result | Reason written to the output |
| --- | --- | --- |
| at or above `must_have_pass` (0.80) | Requirement met | none |
| from `must_have_fail` (0.20) up to, but not including, 0.80 | Candidate goes to `review` | `unclear: <id> (0.xx)` |
| below `must_have_fail` (0.20) | Candidate is `missing` | `not shown: <id> (0.xx)` |

Real examples: candidate_c's `python_professional` came back **0.03**, so it's `missing` (Java and Kotlin only). candidate_b's came back **0.93**, so it's met, even though its only Python was "scripts for nightly catalog imports".

**`min_confidence`** applies to skills. Each Score comes with a `confidence` from 0 to 1 that measures how concentrated Jev's probabilities are across the levels: 1.0 means all on one level, and lower means spread out. If a **heavy** skill (weight 0.20 or more) comes back with confidence **below** `min_confidence`, the candidate goes to `review` with the reason `uncertain: <id> (conf 0.xx)`. Lighter skills are exempt, because an uncertain score there barely moves the composite. The 0.20 cutoff is fixed in code (`HEAVY_WEIGHT` in [`ScreeningPolicy`](../src/main/java/com/example/resumescreening/screening/ScreeningPolicy.java)), not set in the YAML.

Real example: candidate_d's `python_depth` spread its probability across levels 2, 3 and 4, with confidence **0.57**. That is above 0.45, so it did not trigger review.

**How the rules combine.** The worst result wins:

1. Any must-have below `must_have_fail` → **missing**
2. Otherwise, any must-have in the unclear band, or any heavy skill below `min_confidence` → **review**
3. Otherwise → **meets**

Candidates are then **ranked by status first** (`meets`, then `review`, then `missing`) and by composite within each status. A `missing` candidate ranks last even with a high composite. candidate_c had a composite of 0.649, which would have placed third on skills alone, but ranked fourth.

**Tuning.** Changing thresholds costs no API calls (see section 6), so they are cheap to adjust:

| Change | Effect |
| --- | --- |
| Narrow the unclear band (for example 0.35 / 0.65) | Fewer reviews; more borderline candidates decided automatically |
| Widen it (for example 0.10 / 0.90) | More reviews; fewer automatic decisions |
| Raise `min_confidence` | More uncertain scores go to a person |
| Lower `min_confidence` | Fewer reviews; more trust in spread-out scores |

The values in the included specs (0.80 / 0.20 / 0.45) are starting points. Tune them on resumes your recruiters have already judged (see section 7).

---

## 4. How a spec becomes a request

For each resume, the screener sends **one request** containing the state and every question from the spec. The included specs produce seven questions each (eight for `director_of_engineering`, which has three must-haves):

| From the spec | Becomes | Count |
| --- | --- | --- |
| `title`, `target_level`, `summary` + the resume (contact details removed) | `state` | 1 |
| each `must_haves` entry | Noul `must_<id>` | 2 |
| each `skills` entry | Score `skill_<id>` | 5 |

Jev answers all the questions in parallel. No answer is visible to another question, so each question has to stand on its own.

To see the exact request for a resume without calling Jev (no API key needed):

```sh
JOB=senior_backend_engineer
curl -X POST \
  "localhost:8080/api/screenings/$JOB/preview?path=${JOB}_candidates"
```

---

## 5. Validation rules

Checked by [`JobSpecRepository`](../src/main/java/com/example/resumescreening/job/JobSpecRepository.java) at startup, on every save from the editor, and on every reload from disk. At startup any failure stops the app with `Invalid job spec <file>: <problems>`. A save or reload is refused with every problem listed, and the version already loaded stays in use. The editor runs the same checks as you type.

| Rule | Error message |
| --- | --- |
| the file name (the job id) uses only letters, digits, `_` and `-` | `the file name (the job id) may only use letters, digits, '_' and '-'` |
| `title` has text | `title is required` |
| `target_level` has text | `target_level is required` |
| `summary` has text | `summary is required` |
| `must_haves` is present (an empty list is fine) | `must_haves is required (may be empty)` |
| at least one skill | `at least one skill is required` |
| `thresholds` is present | `thresholds is required` |
| every must-have and skill has an id | `every must_have needs an id`, `every skill needs an id` |
| ids use lowercase letters, digits and `_` | `must_have id <id> may only use lowercase letters, digits and '_'` (same for skills) |
| must-have ids are unique | `duplicate must_have id <id>` |
| every must-have has a `requirement` | `must_have <id> needs a requirement` |
| skill ids are unique | `duplicate skill id <id>` |
| every `weight` is above 0 | `skill <id> needs a positive weight` |
| every skill has a `question` | `skill <id> needs a question` |
| 2 to 10 `levels` | `skill <id> needs 2 to 10 levels` |
| no level is empty | `skill <id> has an empty level` |
| `0 ≤ must_have_fail ≤ must_have_pass ≤ 1` | `thresholds need 0 <= must_have_fail <= must_have_pass <= 1` |
| `0 ≤ min_confidence ≤ 1` | `min_confidence must be between 0 and 1` |

YAML keys are snake_case (`must_haves`, `must_have_pass`). The file must end in `.yaml` or `.yml`.

---

## 6. What each change costs

Jev's raw answers are cached in `.cache/`, keyed by the full request: model, state and questions. Anything that isn't in the request can change for free.

| Change | In the request? | API calls after restart |
| --- | --- | --- |
| `weight`, any `thresholds` value | No | **None.** Re-ranked from cache |
| A skill's `question` or `levels`, or a must-have's `requirement` | Yes | Re-asks the affected requests |
| `title`, `target_level` or `summary` | Yes, in the state of **every** request | Re-asks **everything** for that job |
| New spec | n/a | One request per resume screened |
| Model version (`typesafe.model`) | Yes | Re-asks everything |

Because every question for a resume travels in one request, changing any question re-asks the whole request for each resume screened.

---

## 7. Writing a good spec

- **Start from an existing spec.** Use **New job** in the web app, or copy the closest spec in [`jobs/`](../jobs/) to `jobs/<id>.yaml` and press **Reload specs from disk**. Put resumes in `resumes/<id>_candidates/`.
- **Let the editor tell you the cost.** It says whether a change is free (weights, thresholds, reordering) or paid (anything sent to Jev, which re-asks every resume), and it refuses to overwrite a file you changed on disk since opening it.
- **Use suggestions as drafts.** With `ANTHROPIC_API_KEY` set, the editor's ✨ buttons ask Claude to draft the job overview (from a few words describing the job), a question, levels, a requirement or a whole skill, following these rules. Read every draft before accepting it: you are responsible for what Jev is asked.
- **One idea per question.** If a question or requirement needs "and", consider splitting it.
- **Use must-haves for true gates only.** Anything that is a matter of degree belongs in a skill.
- **Write levels as concrete situations**, least to most, each readable on its own.
- **Keep every criterion job-related.** Contact details are removed before Jev sees a resume, but names, schools and dates are not. Check outcomes for bias before relying on them.
- **Calibrate on your own data.** Run 20–50 resumes your recruiters have already judged, and adjust `must_have_pass`, `must_have_fail` and `min_confidence` until the statuses match what the recruiters would decide.
- **Use the output to decide which resumes a person reads first**, not who gets hired.

### Included specs

| Job id | Title | Resumes |
| --- | --- | --- |
| [`director_of_engineering`](../jobs/director_of_engineering.yaml) | Director of Engineering | [`resumes/director_of_engineering_candidates/`](../resumes/director_of_engineering_candidates/) |
| [`md_prod_payments`](../jobs/md_prod_payments.yaml) | Managing Director of Product for the Payments Platform | [`resumes/md_prod_payments_candidates/`](../resumes/md_prod_payments_candidates/) |
| [`senior_backend_engineer`](../jobs/senior_backend_engineer.yaml) | Senior Backend Engineer | [`resumes/senior_backend_engineer_candidates/`](../resumes/senior_backend_engineer_candidates/) |
| [`senior_hr_product_owner`](../jobs/senior_hr_product_owner.yaml) | Senior Human Resources Product Owner | [`resumes/senior_hr_product_owner_candidates/`](../resumes/senior_hr_product_owner_candidates/) |
| [`technical_product_owner`](../jobs/technical_product_owner.yaml) | Technical Product Owner | [`resumes/technical_product_owner_candidates/`](../resumes/technical_product_owner_candidates/) |
| [`senior_software_application_designer`](../jobs/senior_software_application_designer.yaml) | Senior Software Application Designer | [`resumes/senior_software_application_designer_candidates/`](../resumes/senior_software_application_designer_candidates/) |

All six have five skills with weights that add up to 1.00, a `domain_relevance` skill at 0.10 that scores against `` `job.summary` ``, and the same thresholds. Five have two must-haves; `director_of_engineering` has three (it adds `people_management`) and weights leadership most heavily (`technical_leadership` 0.40).
