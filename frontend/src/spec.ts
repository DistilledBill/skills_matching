// Pure helpers for the job spec editor. validateSpec mirrors JobSpecRepository.validate
// (src/main/java/com/example/resumescreening/job/) message for message; the server checks
// again on save, so keep the two in step.

import type { JobSpec } from './api'

export const JOB_ID = /^[A-Za-z0-9_-]+$/
export const ITEM_ID = /^[a-z0-9_]+$/

const blank = (s: string | null | undefined) => !s || !s.trim()

/** One problem, with the form field it belongs to (for example `skills.2.question`). */
export interface SpecError {
  path: string
  message: string
}

export function validateSpec(job: JobSpec): SpecError[] {
  const errors: SpecError[] = []
  const check = (ok: boolean, path: string, message: string) => {
    if (!ok) errors.push({ path, message })
  }
  check(!!job.id && JOB_ID.test(job.id), 'id', "the file name (the job id) may only use letters, digits, '_' and '-'")
  check(!blank(job.title), 'title', 'title is required')
  check(!blank(job.targetLevel), 'targetLevel', 'target_level is required')
  check(!blank(job.summary), 'summary', 'summary is required')
  check(job.skills.length > 0, 'skills', 'at least one skill is required')

  const ids = new Set<string>()
  const itemId = (kind: string, id: string, path: string) => {
    if (blank(id)) {
      errors.push({ path, message: `every ${kind} needs an id` })
      return false
    }
    check(ITEM_ID.test(id), path, `${kind} id ${id} may only use lowercase letters, digits and '_'`)
    return true
  }
  job.mustHaves.forEach((m, i) => {
    if (!itemId('must_have', m.id, `mustHaves.${i}.id`)) return
    check(!ids.has(`must_${m.id}`), `mustHaves.${i}.id`, `duplicate must_have id ${m.id}`)
    ids.add(`must_${m.id}`)
    check(!blank(m.requirement), `mustHaves.${i}.requirement`, `must_have ${m.id} needs a requirement`)
  })
  job.skills.forEach((s, i) => {
    if (!itemId('skill', s.id, `skills.${i}.id`)) return
    check(!ids.has(`skill_${s.id}`), `skills.${i}.id`, `duplicate skill id ${s.id}`)
    ids.add(`skill_${s.id}`)
    check(s.weight > 0, `skills.${i}.weight`, `skill ${s.id} needs a positive weight`)
    check(!blank(s.question), `skills.${i}.question`, `skill ${s.id} needs a question`)
    check(s.levels.length >= 2 && s.levels.length <= 10, `skills.${i}.levels`, `skill ${s.id} needs 2 to 10 levels`)
    check(s.levels.every((l) => !blank(l)), `skills.${i}.levels`, `skill ${s.id} has an empty level`)
  })

  const t = job.thresholds
  check(
    0 <= t.mustHaveFail && t.mustHaveFail <= t.mustHavePass && t.mustHavePass <= 1,
    'thresholds',
    'thresholds need 0 <= must_have_fail <= must_have_pass <= 1',
  )
  check(0 <= t.minConfidence && t.minConfidence <= 1, 'thresholds', 'min_confidence must be between 0 and 1')
  return errors
}

/**
 * What a save costs. Only fields sent to Jev matter: the answer cache is keyed on the whole request, so any
 * of them changing means every resume is asked again. Weights and thresholds are applied in code, and the
 * order of must-haves and skills doesn't matter (the request's questions are keyed by id).
 */
export type ChangeCost = { kind: 'new' } | { kind: 'none' } | { kind: 'free' } | { kind: 'paid'; changed: string[] }

export function changeCost(original: JobSpec | null, draft: JobSpec): ChangeCost {
  if (!original) return { kind: 'new' }
  const changed: string[] = []
  if (original.title !== draft.title) changed.push('title')
  if (original.targetLevel !== draft.targetLevel) changed.push('target level')
  if (original.summary.trim() !== draft.summary.trim()) changed.push('summary')

  const before = new Map(original.mustHaves.map((m) => [m.id, m.requirement]))
  const after = new Map(draft.mustHaves.map((m) => [m.id, m.requirement]))
  for (const [id, req] of after) {
    if (!before.has(id)) changed.push(`must-have ${id} added`)
    else if (before.get(id) !== req) changed.push(`must-have ${id} requirement`)
  }
  for (const id of before.keys()) if (!after.has(id)) changed.push(`must-have ${id} removed`)

  const skillsBefore = new Map(original.skills.map((s) => [s.id, s]))
  const skillsAfter = new Map(draft.skills.map((s) => [s.id, s]))
  for (const [id, s] of skillsAfter) {
    const old = skillsBefore.get(id)
    if (!old) changed.push(`skill ${id} added`)
    else {
      if (old.question !== s.question) changed.push(`skill ${id} question`)
      if (JSON.stringify(old.levels) !== JSON.stringify(s.levels)) changed.push(`skill ${id} levels`)
    }
  }
  for (const id of skillsBefore.keys()) if (!skillsAfter.has(id)) changed.push(`skill ${id} removed`)

  if (changed.length) return { kind: 'paid', changed }
  const same = JSON.stringify(normalize(original)) === JSON.stringify(normalize(draft))
  return same ? { kind: 'none' } : { kind: 'free' }
}

function normalize(job: JobSpec) {
  return { ...job, summary: job.summary.trim() }
}

/** `Director of Engineering` → `director_of_engineering` */
export function suggestId(title: string): string {
  return title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
}

/** A read-only preview of the file the server writes (JobSpecRepository.toYaml), close but not byte-exact. */
export function specToYaml(job: JobSpec): string {
  const lines = [
    '# Job spec for the screener. Everything the model judges comes from here;',
    '# weights and thresholds are applied in code, so editing them re-ranks from',
    '# cached answers without new API calls.',
    '',
    `title: ${scalar(job.title)}`,
    `target_level: ${scalar(job.targetLevel)}`,
    `summary: ${scalar(job.summary.trim())}`,
    '',
    '# Each must-have becomes one Noul: "does the resume show evidence of this?"',
    job.mustHaves.length ? 'must_haves:' : 'must_haves: []',
    ...job.mustHaves.flatMap((m) => [`  - id: ${scalar(m.id)}`, `    requirement: ${scalar(m.requirement)}`]),
    '',
    '# Each skill becomes one Score. Levels run from least to most and must',
    '# each describe a concrete situation on their own.',
    'skills:',
    ...job.skills.flatMap((s) => [
      `  - id: ${scalar(s.id)}`,
      `    weight: ${s.weight}`,
      `    question: ${scalar(s.question)}`,
      '    levels:',
      ...s.levels.map((l) => `      - ${scalar(l)}`),
    ]),
    '',
    'thresholds:',
    `  must_have_pass: ${job.thresholds.mustHavePass}`,
    `  must_have_fail: ${job.thresholds.mustHaveFail}`,
    `  min_confidence: ${job.thresholds.minConfidence}`,
  ]
  return lines.join('\n') + '\n'
}

/** Quotes a YAML scalar only when it needs it. */
function scalar(value: string): string {
  if (value === '') return "''"
  const plain =
    !/^[\s\-?:,[\]{}#&*!|>'"%@`]/.test(value) &&
    !/(: |\s#)/.test(value) &&
    !/\s$/.test(value) &&
    !/^(true|false|null|yes|no|~|[-+]?[0-9.]+)$/i.test(value)
  return plain ? value : JSON.stringify(value)
}
