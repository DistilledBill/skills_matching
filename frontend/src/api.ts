// Types mirror the Java records in src/main/java (JSON is camelCase), and each
// function wraps one endpoint of the REST API.

export type Status = 'meets' | 'review' | 'missing'

export interface MustHave {
  id: string
  requirement: string
}

export interface Skill {
  id: string
  weight: number
  question: string
  levels: string[]
}

export interface Thresholds {
  mustHavePass: number
  mustHaveFail: number
  minConfidence: number
}

export interface JobSpec {
  id: string
  title: string
  /** The seniority the role is hired at; sent to Jev as context. */
  targetLevel: string
  summary: string
  mustHaves: MustHave[]
  skills: Skill[]
  thresholds: Thresholds
}

/** A job spec as listed by the API, with the version (a hash of its file) that a save must send back. */
export interface VersionedJobSpec extends JobSpec {
  version: string
}

/** Whether Claude suggestions are available in the spec editor (an Anthropic API key is set). */
export interface AssistStatus {
  enabled: boolean
  model: string
}

/** Advice with a suggestion, such as a question that doesn't name `resume`; the spec is valid without it. */
interface Warned {
  warnings: string[]
}
export interface SummarySuggestion extends Warned {
  summary: string
}
export interface QuestionSuggestion extends Warned {
  question: string
}
export interface LevelsSuggestion extends Warned {
  levels: string[]
}
export interface RequirementSuggestion extends Warned {
  requirement: string
}
export interface SkillSuggestion extends Warned {
  id: string
  question: string
  levels: string[]
  weight: number
}

/** The result of re-reading every spec from disk. */
export interface ReloadResult {
  loaded: string[]
  removed: string[]
  errors: Record<string, string[]>
}

export interface CandidateResult {
  rank: number
  name: string
  status: Status
  composite: number
  reasons: string[]
  mustHaves: Record<string, number>
  scores: Record<string, number>
  confidences: Record<string, number>
}

export interface ScreeningReport {
  jobId: string
  title: string
  candidates: CandidateResult[]
}

export interface ResumeFolder {
  path: string
  fileCount: number
}

export interface ResumeFile {
  fileName: string
  name: string
  format: 'pdf' | 'txt' | 'md'
  size: number
}

/** A resume's text; with `redacted` it is the text Jev receives, otherwise the original with contact details. */
export interface ResumeContent {
  fileName: string
  name: string
  format: 'pdf' | 'txt' | 'md'
  text: string
}

export type Question =
  | {
      type: 'noul'
      instructions: string | { requirement?: string; question?: string }
      criteria?: { true: string; false: string }
    }
  | { type: 'score'; instructions: string; criteria: string[] }

export interface SystemOneRequest {
  model: string
  state: { job: { title: string; target_level: string; summary: string }; resume: string }
  questions: Record<string, Question>
}

export interface Preview {
  request: SystemOneRequest
  /** Whether screening this exact request is answered from the cache (no API call). */
  cached: boolean
}

export interface CacheStatus {
  total: number
  cached: number
}

/** Where the resumes for a screening come from. */
export type Source = { kind: 'folder'; path: string } | { kind: 'upload'; files: File[] }

/** An RFC 9457 problem detail returned by the API, or a network failure. */
export class ApiError extends Error {
  readonly status: number
  /** Every validation problem, when the API lists them (an invalid job spec). */
  readonly errors: string[]

  constructor(status: number, message: string, errors: string[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.errors = errors
  }
}

export async function toApiError(response: Response): Promise<ApiError> {
  let message = `Request failed (HTTP ${response.status})`
  try {
    const problem = (await response.json()) as { title?: string; detail?: string; errors?: unknown }
    message = problem.detail || problem.title || message
    if (Array.isArray(problem.errors)) return new ApiError(response.status, message, problem.errors.map(String))
  } catch {
    // Not a problem-detail body; keep the generic message.
  }
  return new ApiError(response.status, message)
}

async function call(url: string, init?: RequestInit): Promise<Response> {
  let response: Response
  try {
    response = await fetch(url, init)
  } catch {
    throw new ApiError(0, 'Could not reach the screening service. Is it running?')
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response
}

async function json<T>(url: string, init?: RequestInit): Promise<T> {
  return (await (await call(url, init)).json()) as T
}

function postJson<T>(url: string, body: unknown): Promise<T> {
  return json<T>(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
}

/** Request body for a folder or an upload, plus any extra parameters. */
function sourceBody(source: Source, extra: Record<string, string> = {}): FormData | URLSearchParams {
  if (source.kind === 'upload') {
    const form = new FormData()
    source.files.forEach((file) => form.append('files', file))
    Object.entries(extra).forEach(([key, value]) => form.append(key, value))
    return form
  }
  return new URLSearchParams({ path: source.path, ...extra })
}

const enc = encodeURIComponent

export const api = {
  jobs: () => json<VersionedJobSpec[]>('/api/jobs'),

  createJob: (spec: JobSpec) =>
    json<VersionedJobSpec>('/api/jobs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(spec),
    }),

  /** Saves a spec; `version` must match the file on disk or the API answers 409. */
  updateJob: (id: string, version: string, spec: JobSpec) =>
    json<VersionedJobSpec>(`/api/jobs/${enc(id)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ version, spec }),
    }),

  reloadJob: (id: string) => json<VersionedJobSpec>(`/api/jobs/${enc(id)}/reload`, { method: 'POST' }),

  reloadJobs: () => json<ReloadResult>('/api/jobs/reload', { method: 'POST' }),

  folders: () => json<ResumeFolder[]>('/api/resume-folders'),

  folder: (path: string) => json<ResumeFile[]>(`/api/resume-folders/${enc(path)}`),

  screen: (jobId: string, source: Source) =>
    json<ScreeningReport>(screenUrl(jobId, source), { method: 'POST', body: sourceBody(source) }),

  async downloadCsv(jobId: string, source: Source): Promise<{ blob: Blob; fileName: string }> {
    const response = await call(screenUrl(jobId, source), {
      method: 'POST',
      body: sourceBody(source, { format: 'csv' }),
    })
    const disposition = response.headers.get('Content-Disposition') ?? ''
    const fileName = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? `${jobId}.csv`
    return { blob: await response.blob(), fileName }
  },

  async preview(jobId: string, source: Source, name?: string): Promise<Preview> {
    const response = await call(`/api/screenings/${enc(jobId)}/preview`, {
      method: 'POST',
      body: sourceBody(source, name ? { name } : {}),
    })
    return toPreview(response)
  },

  async previewText(jobId: string, name: string, text: string): Promise<Preview> {
    const response = await call(`/api/screenings/${enc(jobId)}/preview-text`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, text }),
    })
    return toPreview(response)
  },

  resume: (folder: string, fileName: string, redacted: boolean) =>
    json<ResumeContent>(
      `/api/resume-folders/${enc(folder)}/resumes/${enc(fileName)}?redacted=${String(redacted)}`,
    ),

  resumeTexts(files: File[], redacted: boolean): Promise<ResumeContent[]> {
    const form = new FormData()
    files.forEach((file) => form.append('files', file))
    return json<ResumeContent[]>(`/api/resumes/text?redacted=${String(redacted)}`, { method: 'POST', body: form })
  },

  /** How many answers are cached for the job, from any folder, upload or earlier spec version. */
  jobCache: (jobId: string) => json<{ count: number }>(`/api/screenings/${enc(jobId)}/cache`),

  /** Deletes every cached answer for the job; the next screening calls Jev again. */
  clearJobCache: (jobId: string) =>
    json<{ removed: number }>(`/api/screenings/${enc(jobId)}/cache`, { method: 'DELETE' }),

  /** Claude suggestions for the spec editor. Each call sends the unsaved draft (never resumes) and is paid. */
  assist: {
    status: () => json<AssistStatus>('/api/assist'),
    summary: (draft: JobSpec, description: string) =>
      postJson<SummarySuggestion>('/api/assist/summary', { draft, hint: description }),
    skillQuestion: (draft: JobSpec, index: number, hint?: string) =>
      postJson<QuestionSuggestion>('/api/assist/skill-question', { draft, index, hint }),
    skillLevels: (draft: JobSpec, index: number, count: number) =>
      postJson<LevelsSuggestion>('/api/assist/skill-levels', { draft, index, count }),
    mustHave: (draft: JobSpec, index: number, hint?: string) =>
      postJson<RequirementSuggestion>('/api/assist/must-have', { draft, index, hint }),
    skill: (draft: JobSpec, description: string, count: number) =>
      postJson<SkillSuggestion>('/api/assist/skill', { draft, hint: description, count }),
  },

  cacheStatus: (jobId: string, source: Source) =>
    json<CacheStatus>(`/api/screenings/${enc(jobId)}/cache-status`, {
      method: 'POST',
      body: sourceBody(source),
    }),
}

/** The stored file, served inline (a PDF opens in the browser). */
export function resumeFileUrl(folder: string, fileName: string): string {
  return `/api/resume-folders/${enc(folder)}/files/${enc(fileName)}`
}

function screenUrl(jobId: string, source: Source): string {
  return source.kind === 'folder' ? `/api/screenings/${enc(jobId)}/folder` : `/api/screenings/${enc(jobId)}`
}

async function toPreview(response: Response): Promise<Preview> {
  return {
    request: (await response.json()) as SystemOneRequest,
    cached: response.headers.get('X-Answer-Cached') === 'true',
  }
}

/** File name without its extension, as the service names candidates. */
export function baseName(fileName: string): string {
  const dot = fileName.lastIndexOf('.')
  return dot > 0 ? fileName.slice(0, dot) : fileName
}
