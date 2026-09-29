# Jev and the resume screener

How TypeSafe's Jev model works, what its numbers mean, and how this project turns them into a ranked shortlist.

*Co-designed and implemented with Claude and Bill Stamatakis*

> **Where the numbers come from.** Every example below is real output from `jev-1.13.0` (reached through the `jev-latest` alias) on the four original sample resumes (`candidate_a` to `candidate_d`) in [`resumes/senior_backend_engineer_candidates/`](../resumes/senior_backend_engineer_candidates/), screened against [`jobs/senior_backend_engineer.yaml`](../jobs/senior_backend_engineer.yaml) on 2026-09-24. If you rerun it you may see small differences, especially after the alias moves to a newer model. The skill questions in that run had ids `comp_*`; they have since been renamed `skill_*`. Ids are not sent to the model, so the answers should not depend on them, but the cache treats the renamed requests as new.

**Contents**

1. [What Jev is](#1-what-jev-is)
2. [The building blocks](#2-the-building-blocks)
3. [What the numbers mean](#3-what-the-numbers-mean)
4. [How this project applies Jev](#4-how-this-project-applies-jev)
5. [Reading a result](#5-reading-a-result)
6. [Tuning and limits](#6-tuning-and-limits)
7. [Glossary](#7-glossary)

---

## 1. What Jev is

Jev is TypeSafe's flagship model and its first **System One** model. The name comes from Kahneman's *Thinking, Fast and Slow*: System 1 is the fast, intuitive kind of thinking. Like an LLM, Jev reads natural language. Unlike an LLM, **it never writes text**. You give it some content and a set of questions whose possible answers you define. It returns a typed answer and probabilities for each question.

| | Chat LLM | Reasoning model | Jev (System One) |
| --- | --- | --- | --- |
| Output | Free-form text | Text after a chain of reasoning | Typed answers with probabilities |
| Trained for | Responses people prefer (RLHF) | Verifiable answers such as math (RLVR) | **Calibrated decisions** (RLCD) |
| Explains itself | Yes, in prose | Yes, at length | No. Your code gives the reasons |
| Speed and cost | Moderate | Slow and expensive | Fast and cheap; many questions per call |
| Good at | Writing, conversation | Multi-step problems | Quick, focused judgments that code consumes |

What matters for this project:

- **Calibrated.** Jev is trained so its probabilities match reality across many predictions: of all the answers it gives 0.8, about 80% should turn out true. That holds for groups of answers, not for any single one.
- **Constrained.** An answer is always one of the options or levels you supplied, so there is never prose to parse.
- **Stateless and shared.** Everyone uses the same model weights. Jev is not fine-tuned per customer and is not trained on your requests. You adapt it to your domain only through what you send: the content, the question wording, and the answer definitions.
- **Text only.** Input is a string, JSON object, or array of text. PDFs must be converted to text first, which this project does with PDFBox.
- **Limits** (jev-1.13): 64k tokens per request, of which the content plus the longest single question must fit in 32k. English works best. Pricing is per input token ($0.042 per million), and output tokens are free. One resume in this project costs about 1,200 input tokens.
- **Model names.** `jev-latest` is an alias that currently points to `jev-1.13.0`. Every response reports which model actually answered.

Sources: [System One](https://docs.typesafe.ai/concepts/system-one), [AI primer](https://docs.typesafe.ai/introduction/machine-learning-primer), [Models](https://docs.typesafe.ai/models).

---

## 2. The building blocks

Every call is `POST https://api.typesafe.ai/v1/systemone` with three fields:

```json
{
  "model": "jev-latest",
  "state": { "...the content to judge..." },
  "questions": {
    "my_id": {
      "type": "noul | score | choice",
      "instructions": "...",
      "criteria": "..."
    }
  }
}
```

- **State** is the content to judge, as a string or structured JSON. Here it is `{ "job": {title, summary}, "resume": "<redacted text>" }`. Questions can point at parts of the state with backticked paths such as `` `resume` `` or `` `job.summary` ``.
- **Question** is one judgment about the state:
  - The **id** (such as `skill_python_depth`) is only for your code. It is **not sent to the model**, so the instructions must be complete on their own.
  - **`instructions`** is the question itself. It can be a string, or an object that holds the question plus data it refers to.
  - **`criteria`** defines the possible answers.
- **Primitives** are the three question types:

| Type | Asks | Returns | Used here for |
| --- | --- | --- | --- |
| **Noul** | Is this true? | `noul`: probability of yes, 0 to 1 | Must-have requirements |
| **Score** | Where on this scale? | `score`, `probabilities`, `legend`, `confidence` | Graded skills |
| **Choice** | Which one of these options? | `choice`, `probabilities`, `confidence` | Not used in this project |

- **Parallel and independent.** Every question in a request sees the same state and is answered in parallel. No answer is visible to another question. Adding questions barely changes latency, so this project asks all seven in **one request per resume**.

Source: [Primitives](https://docs.typesafe.ai/primitives).

---

## 3. What the numbers mean

This section covers the numbers Jev returns and the numbers this project computes from them. Keeping the two apart is the most important idea in this document.

### 3.1 Noul: `noul` is a probability, not a grade

A Noul answer is one number: **the probability that the answer is yes.**

| `noul` | Meaning |
| --- | --- |
| near 1 | Confident yes |
| near 0.5 | **Can't tell**: yes and no are about equally likely |
| near 0 | Confident no |

A value of 0.5 does **not** mean "medium". Asking "Is this candidate strong in Python?" and reading 0.5 as "moderately strong" is a common mistake. A Noul is only for a condition with a clear yes/no boundary. To measure *how much*, use a Score.

Nouls have no separate `confidence`, because the single number already says how sure the model is.

Real answers for the must-have "Has used Python in a paid engineering role (not only coursework or hobby projects)":

| Resume | `noul` | Reading |
| --- | --- | --- |
| candidate_a (9 years of Python in payments) | **0.99** | Clear yes |
| candidate_d (Django REST in health tech) | **0.99** | Clear yes |
| candidate_b (full-stack; "Python scripts for nightly catalog imports") | **0.93** | Yes, a little less sure |
| candidate_c (Java and Kotlin only) | **0.03** | Clear no |

### 3.2 Score: `score` is a position on levels you wrote

You give an ordered list of levels. Jev returns a **probability for each level**, and `score` is their weighted average:

```
score = Σ (level index × probability of that level)
```

So `score` **can land between levels**. Real answer for candidate_a on *"How deep is the candidate's professional Python experience?"*:

| Level | Description | Probability |
| --- | --- | --- |
| 0 | No Python experience mentioned | 0.00 |
| 1 | Python mentioned, but no detail about what was built | 0.00 |
| 2 | Used Python on some projects, with a few specifics | 0.00 |
| 3 | Python was the primary language across multiple roles or projects | **0.37** |
| 4 | Deep Python expertise, such as performance tuning, library internals… | **0.63** |

`score = 3 × 0.37 + 4 × 0.63 = 3.63`. The model thinks this candidate is between "primary language" and "deep expertise", leaning towards deep. `legend` in the response repeats the level text by index, and `probabilities` holds the column above.

### 3.3 `confidence`: how concentrated the probabilities are

Score and Choice answers also carry **`confidence`, from 0 to 1**. TypeSafe computes it from the shape of the probabilities: all probability on one level gives 1.0, and the more evenly it is spread, the lower it goes.

Compare two real `python_depth` answers:

| Resume | Probabilities (levels 0–4) | `score` | `confidence` |
| --- | --- | --- | --- |
| candidate_b | 0, 0.01, **0.99**, 0, 0 | 1.99 | **0.99** |
| candidate_d | 0, 0, 0.28, **0.48**, 0.24 | 2.97 | **0.57** |

Jev is sure candidate_b is at level 2. For candidate_d it spreads its answer across levels 2, 3 and 4. The resume says Python and Go, owns a Django service, and also mentions "some Python for data jobs", so the evidence really does sit between levels.

What confidence **is not**:

- It is not the probability that the answer is correct.
- It is not permission to act. The same confidence can be fine for a cheap decision and too low for an expensive one.
- Low confidence usually means the levels overlap or blur several things together, or the resume doesn't say enough. It is a signal to send the case to a person, or to reword the levels.

Source: [Confidence](https://docs.typesafe.ai/confidence), [Score](https://docs.typesafe.ai/primitives/score), [Noul](https://docs.typesafe.ai/primitives/noul).

### 3.4 Numbers this project computes (not Jev)

Everything below is plain Java in [`ScreeningPolicy.java`](../src/main/java/com/example/resumescreening/screening/ScreeningPolicy.java). Jev never sees the weights, thresholds or statuses.

**Normalized score** (the `scores` field in the output). This puts every skill on a 0 to 1 scale, whatever its number of levels:

```
normalized = score / (number of levels − 1)
```

candidate_a's python_depth is 3.63 / 4 = **0.9075**. data_stores has 4 levels, so a 3.0 there becomes 3.0 / 3 = **1.0**.

**Composite** (the `composite` field). A weighted average of the normalized scores, using the weights from the job YAML:

```
composite = Σ (weight / total weight × normalized)
```

candidate_a:

| Skill | Weight | Normalized | Contribution |
| --- | --- | --- | --- |
| python_depth | 0.30 | 0.9075 | 0.272 |
| distributed_systems | 0.25 | 0.935 | 0.234 |
| data_stores | 0.15 | 1.000 | 0.150 |
| technical_leadership | 0.20 | 0.750 | 0.150 |
| domain_relevance | 0.10 | 1.000 | 0.100 |
| **Composite** | 1.00 | | **0.906** |

**Status**, using the thresholds in the job YAML:

| Status | Rule |
| --- | --- |
| `missing` | Any must-have `noul` is **below `must_have_fail` (0.20)** |
| `review` | Otherwise, if any must-have `noul` is **between 0.20 and `must_have_pass` (0.80)**, or any skill weighted **0.2 or more** has `confidence` **below `min_confidence` (0.45)** |
| `meets` | Otherwise |

**Rank.** Candidates are sorted by status first (`meets`, then `review`, then `missing`) and by composite within each status. A `missing` candidate therefore ranks last even with a high composite.

**Reasons** (the `reasons` field) are strings **written by the code** to say which rule fired, such as `not shown: python_professional (0.03)`. They are not an explanation from Jev, which never explains itself.

---

## 4. How this project applies Jev

### 4.1 The flow

```
upload or folder ─► ResumeLoader ─► Redactor ─► state {job, resume}
                   (.txt/.md/.pdf)  (email, phone,        │
                                     profile links)       ▼
                                           one request, 7 questions ─► Jev
                                                                        │
     JSON / CSV ◄── rank ◄── ScreeningPolicy ◄── 2 nouls + 5 scores ◄───┘
                           (weights, thresholds,     (cached in .cache/)
                            status, reasons)
```

1. **Load.** [`ResumeLoader`](../src/main/java/com/example/resumescreening/screening/ResumeLoader.java) reads uploads or a folder under `resumes/` and extracts text from PDFs.
2. **Redact.** [`Redactor`](../src/main/java/com/example/resumescreening/screening/Redactor.java) replaces emails, phone numbers and GitHub/LinkedIn links with `[email]`, `[phone]` and `[link]`. They carry no job-related signal.
3. **Build the request.** [`ScreeningService`](../src/main/java/com/example/resumescreening/screening/ScreeningService.java) builds the state, and [`QuestionBuilder`](../src/main/java/com/example/resumescreening/screening/QuestionBuilder.java) turns the job YAML into questions.
4. **Ask Jev.** [`TypeSafeClient`](../src/main/java/com/example/resumescreening/typesafe/TypeSafeClient.java) sends one request per resume, several resumes at once. It retries 429, 500, 502, 503, 504 and 529 responses and connection errors with exponential backoff, and caps how many requests are in flight.
5. **Decide.** [`ScreeningPolicy`](../src/main/java/com/example/resumescreening/screening/ScreeningPolicy.java) applies the rules in section 3.4.
6. **Return.** [`ScreeningController`](../src/main/java/com/example/resumescreening/web/ScreeningController.java) returns the ranked list as JSON or CSV.

### 4.2 The seven questions

All of them come from [`jobs/senior_backend_engineer.yaml`](../jobs/senior_backend_engineer.yaml). Adding a role means writing a new YAML file, not Java. The project also includes specs for a Senior HR Product Owner, a Technical Product Owner and a Senior Software Application Designer. They have the same shape (two must-haves and five skills, so seven questions), and this document uses the backend spec as its worked example. The [job spec guide](job-spec-guide.md) explains every field of a spec.

| Id | Type | Weight | Why this type |
| --- | --- | --- | --- |
| `must_python_professional` | Noul | gate | A requirement is met or not, with a clear boundary ("paid role, not coursework") |
| `must_backend_services` | Noul | gate | Same |
| `skill_python_depth` | Score, 5 levels | 0.30 | Depth is a matter of degree, so each level describes a concrete situation |
| `skill_distributed_systems` | Score, 5 levels | 0.25 | Same |
| `skill_data_stores` | Score, 4 levels | 0.15 | Same |
| `skill_technical_leadership` | Score, 5 levels | 0.20 | Same |
| `skill_domain_relevance` | Score, 3 levels | 0.10 | Same |

Each must-have Noul uses structured instructions. The requirement text goes in its own field and the fixed question refers to it by name:

```json
"must_python_professional": {
  "type": "noul",
  "instructions": {
    "requirement": "Has used Python in a paid engineering role
      (not only coursework or hobby projects)",
    "question": "Does `resume` show evidence that the candidate
      meets `requirement`?"
  },
  "criteria": {
    "true": "The resume describes experience that satisfies
      the requirement",
    "false": "The resume does not mention it, or what it
      describes falls short of the requirement"
  }
}
```

Long strings are wrapped here to fit the page. In the real request each one is a single line.

To see the exact request for any resume without calling Jev: `POST /api/screenings/senior_backend_engineer/preview?path=senior_backend_engineer_candidates`.

### 4.3 Design choices

- **One narrow question per aspect.** A single "rate this candidate 1–10" question would mix five judgments into one opaque number. Separate questions keep each answer interpretable and let you reweight them.
- **Policy in code, judgment in Jev.** Jev answers "what does this resume show?" The code decides what that means for this job. Anyone can read the rules, test them, and change them without touching a prompt.
- **One request per resume.** The seven questions share the state, so they go together. This is cheaper and faster than seven calls, and gives the same answers.
- **Cache.** [`AnswerCache`](../src/main/java/com/example/resumescreening/typesafe/AnswerCache.java) stores each raw response under a hash of the full request. Weights and thresholds are not in the request, so changing them and restarting **re-ranks with no API calls**. Changing a question's wording or levels changes the hash, so those resumes are asked again.

---

## 5. Reading a result

The real ranked output for `candidate_a` to `candidate_d` (`POST /api/screenings/senior_backend_engineer/folder?path=senior_backend_engineer_candidates`, key CSV columns only; the full CSV also has each must-have and each confidence). The folder now holds more sample resumes, so a rerun returns more rows:

| Rank | Candidate | Status | Composite | py depth | dist sys | data stores | leadership | domain |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | candidate_a | meets | 0.906 | 0.91 | 0.94 | 1.00 | 0.75 | 1.00 |
| 2 | candidate_d | meets | 0.706 | 0.74 | 0.75 | 1.00 | 0.53 | 0.42 |
| 3 | candidate_b | meets | 0.368 | 0.50 | 0.25 | 0.37 | 0.25 | 0.50 |
| 4 | candidate_c | **missing** | 0.649 | 0.00 | 0.96 | 0.73 | 1.00 | 1.00 |

Here is candidate_c from the JSON output, annotated (long decimals shortened):

```jsonc
{
  "rank": 4,
  "name": "candidate_c",
  "status": "missing",              // a must-have fell below 0.20
  "composite": 0.649125,            // 3rd on skills alone...
  "reasons": [                      // ...but the gate comes first
    "not shown: python_professional (0.03)"
  ],
  "mustHaves": {
    "python_professional": 0.03,    // 97% "no": no Python on it
    "backend_services": 0.96        // clearly built prod services
  },
  "scores": {                       // normalized 0 to 1
    "python_depth": 0.0025,         // raw 0.01 of 4: no Python
    "distributed_systems": 0.9575,  // raw 3.83: 2M msg/sec platform
    "data_stores": 0.7267,          // raw 2.18 of 3: designed schemas
    "technical_leadership": 1.0,    // raw 4.0: direction for 3 teams
    "domain_relevance": 1.0         // raw 2.0: clearing is fintech
  },
  "confidences": {
    "python_depth": 1.0, "distributed_systems": 0.86,
    "data_stores": 0.82, "technical_leadership": 1.0,
    "domain_relevance": 1.0
  }
}
```

What the four results show:

- **candidate_a vs candidate_d.** Both meet every requirement. candidate_a scores higher on every weighted skill. candidate_d's lower `domain_relevance` (0.42, health tech) costs little, because that skill is weighted only 0.10.
- **candidate_c.** A very strong engineer with no Python. The composite alone would put them above candidate_b, but the must-have gate puts them last and says why. A recruiter might still decide to talk to them. The output makes that trade-off visible instead of hiding it inside one number.
- **candidate_b.** Must-haves at 0.93 and 0.86 are above the 0.80 pass line, so the status is `meets`, but the composite is low. Python "scripts for nightly catalog imports" counted as paid Python work. If your team wouldn't count that, tighten the requirement text (for example, "…as a primary language for production services") or raise `must_have_pass`. That kind of decision belongs in the spec, not the model.
- **No `review` cases in this run.** The least confident heavy skill was candidate_d's python_depth at 0.57, which is above the 0.45 floor. With real applicant volume you should expect some.

---

## 6. Tuning and limits

**What to change, and what it costs**

| Change | Where | API calls |
| --- | --- | --- |
| Weights, thresholds | Job YAML, then restart | **None**, re-ranked from cache |
| Question wording, levels, requirements | Job YAML, then restart | Re-asks the affected requests |
| New role | New `jobs/<id>.yaml`, resumes in `resumes/<id>_candidates/` | One request per resume screened |

**Good practice**

- **Calibrate on your own data.** Run 20–50 resumes your recruiters have already judged, and set `must_have_pass`, `must_have_fail` and `min_confidence` so the statuses match what they would do. The values in the sample specs are starting points, not recommendations.
- **Write levels as concrete situations.** Each Score level should describe one situation that makes sense on its own ("Owned the design of a multi-service system handling significant traffic"), not a word like "good". Vague or overlapping levels show up as low confidence.
- **Keep one idea per question.** If a question needs "and", split it into two.
- **Pin the model once tuned.** `jev-latest` moves when TypeSafe ships a new version, and the answers behind it can shift. After tuning thresholds, set `typesafe.model: jev-1.13.0` in `application.yml` and upgrade on your own schedule. The cache includes the model name, so an upgrade re-asks everything.

**Limits to keep in mind**

- **A typed answer is not necessarily a true one.** The format is guaranteed; correctness is not. Calibration holds across many answers, not for each candidate.
- **Jev judges only what the resume says.** It can't verify claims, and it will score a well-written resume above an equally strong but terse one.
- **English works best.** Test before screening resumes in other languages, and watch confidence.
- **Fairness.** Criteria must be job-related. Contact details are removed, but names, schools and employment dates still reach the model. Check outcomes for bias before relying on them. Use the output to decide **which resumes a person reads first**, not who gets hired.

---

## 7. Glossary

| Term | Meaning |
| --- | --- |
| **Jev** | TypeSafe's System One model; `jev-latest` currently points to `jev-1.13.0` |
| **System One model** | A model that returns typed decisions and probabilities instead of text |
| **RLCD** | Reinforcement learning for calibrated decisions, the method used to train Jev |
| **Calibrated** | Across many answers, stated probabilities match how often things turn out true |
| **State** | The content Jev judges; here `{job, resume}` |
| **Question id** | Your key for an answer, never sent to the model |
| **Noul** | Yes/no question; `noul` is the probability of yes |
| **Score** | Question with ordered levels; `score` is the probability-weighted level |
| **`probabilities`** | How much probability Jev puts on each level or option |
| **`legend`** | Level index mapped back to the level text |
| **`confidence`** | How concentrated `probabilities` is, 0 to 1 (Score and Choice only) |
| **Normalized score** | `score / (levels − 1)`, computed by this project |
| **Composite** | Weighted average of normalized scores, computed by this project |
| **Status** | `meets` / `review` / `missing`, computed by this project from thresholds |
