import { describe, expect, it } from 'vitest'
import type { JobSpec } from './api'
import backend from './fixtures/senior_backend_engineer.report.json'
import { changeCost, specToYaml, suggestId, validateSpec } from './spec'

const job = (backend as unknown as { job: JobSpec }).job
const clone = (): JobSpec => structuredClone(job)

describe('validateSpec', () => {
  it('accepts a checked-in spec', () => {
    expect(validateSpec(job)).toEqual([])
  })

  // The same case and messages as JobSpecRepositoryTest.validateListsEveryProblem.
  it('lists every problem with the same messages as the server', () => {
    const bad: JobSpec = {
      id: 'ok_id',
      title: ' ',
      targetLevel: '',
      summary: 's',
      mustHaves: [{ id: 'Bad-Id', requirement: '' }],
      skills: [{ id: 'depth', weight: 0, question: '', levels: ['only one'] }],
      thresholds: { mustHavePass: 0.2, mustHaveFail: 0.8, minConfidence: 0.45 },
    }
    expect(validateSpec(bad).map((e) => e.message)).toEqual([
      'title is required',
      'target_level is required',
      "must_have id Bad-Id may only use lowercase letters, digits and '_'",
      'must_have Bad-Id needs a requirement',
      'skill depth needs a positive weight',
      'skill depth needs a question',
      'skill depth needs 2 to 10 levels',
      'thresholds need 0 <= must_have_fail <= must_have_pass <= 1',
    ])
  })

  it('points each problem at its field', () => {
    const j = clone()
    j.skills[1].question = ''
    j.mustHaves.push({ ...j.mustHaves[0] })
    expect(validateSpec(j)).toEqual([
      { path: 'mustHaves.2.id', message: 'duplicate must_have id python_professional' },
      { path: 'skills.1.question', message: 'skill distributed_systems needs a question' },
    ])
  })
})

describe('changeCost', () => {
  it('is free when only weights and thresholds change, and ignores reordering', () => {
    const j = clone()
    j.skills[0].weight = 0.5
    j.thresholds.minConfidence = 0.6
    j.skills.reverse()
    j.mustHaves.reverse()
    expect(changeCost(job, j)).toEqual({ kind: 'free' })
    expect(changeCost(job, clone())).toEqual({ kind: 'none' })
  })

  it('is paid when anything sent to Jev changes, and names what changed', () => {
    const j = clone()
    j.targetLevel = 'Director'
    j.skills[0].levels = [...j.skills[0].levels, 'Wrote the language']
    j.mustHaves = j.mustHaves.slice(1)
    j.skills.push({ id: 'on_call', weight: 0.1, question: 'Q?', levels: ['a', 'b'] })
    expect(changeCost(job, j)).toEqual({
      kind: 'paid',
      changed: ['target level', 'must-have python_professional removed', 'skill python_depth levels', 'skill on_call added'],
    })
  })

  it('ignores whitespace around the summary, as the server does', () => {
    const j = clone()
    j.summary = `  ${job.summary.trim()}\n`
    expect(changeCost(job, j)).toEqual({ kind: 'none' })
  })

  it('treats a job with no saved version as new', () => {
    expect(changeCost(null, job)).toEqual({ kind: 'new' })
  })
})

describe('helpers', () => {
  it('suggests an id from the title', () => {
    expect(suggestId('Director of Engineering')).toBe('director_of_engineering')
    expect(suggestId('  Sr. Engineer (Payments) ')).toBe('sr_engineer_payments')
  })

  it('previews the YAML with the header and quotes only what needs it', () => {
    const yaml = specToYaml(job)
    expect(yaml).toMatch(/^# Job spec for the screener/)
    expect(yaml).toContain('title: Senior Backend Engineer\ntarget_level: Vice President\n')
    expect(yaml).toContain('  - id: python_depth\n    weight: 0.3\n')
    expect(specToYaml({ ...job, title: 'Lead: Payments' })).toContain('title: "Lead: Payments"')
  })
})
