import { describe, expect, it } from 'vitest'
import type { CandidateResult, JobSpec, ScreeningReport } from './api'
import backend from './fixtures/senior_backend_engineer.report.json'
import hr from './fixtures/senior_hr_product_owner.report.json'
import tpo from './fixtures/technical_product_owner.report.json'
import { decide, format2, rank, samePolicy, specPolicy, type Policy } from './policy'

type Fixture = { job: JobSpec; report: ScreeningReport }
const fixtures = [backend, hr, tpo] as unknown as Fixture[]
const job = (backend as unknown as Fixture).job

/** Mirrors TestAnswers.strong: every must-have at 0.95, every skill at its top level with confidence 0.9. */
function strong(name = 'a'): CandidateResult {
  return {
    rank: 0,
    name,
    status: 'meets',
    composite: 0,
    reasons: [],
    mustHaves: Object.fromEntries(job.mustHaves.map((m) => [m.id, 0.95])),
    scores: Object.fromEntries(job.skills.map((c) => [c.id, 1])),
    confidences: Object.fromEntries(job.skills.map((c) => [c.id, 0.9])),
  }
}

const spec = specPolicy(job)
const decideWith = (c: CandidateResult, policy: Policy = spec) => decide(job, c, policy)

// The same cases as ScreeningPolicyTest.java.
describe('decide', () => {
  it('a strong candidate meets with a full composite', () => {
    const result = decideWith(strong())
    expect(result.status).toBe('meets')
    expect(result.composite).toBeCloseTo(1, 9)
    expect(result.reasons).toEqual([])
  })

  it('the composite is the weighted average of normalized scores', () => {
    const c = strong()
    job.skills.forEach((skill) => (c.scores[skill.id] = 0))
    c.scores.python_depth = 0.5
    const total = job.skills.reduce((sum, skill) => sum + skill.weight, 0)
    expect(decideWith(c).composite).toBeCloseTo((0.3 / total) * 0.5, 9)
  })

  it('a must-have below the fail threshold is missing', () => {
    const c = strong()
    c.mustHaves.python_professional = 0.05
    const result = decideWith(c)
    expect(result.status).toBe('missing')
    expect(result.reasons).toEqual(['not shown: Python professional (0.05)'])
  })

  it('a must-have between the thresholds goes to review', () => {
    const c = strong()
    c.mustHaves.backend_services = 0.5
    const result = decideWith(c)
    expect(result.status).toBe('review')
    expect(result.reasons).toEqual(['unclear: Backend services (0.50)'])
  })

  it('missing is not downgraded by later review reasons', () => {
    const c = strong()
    c.mustHaves.python_professional = 0.05
    c.mustHaves.backend_services = 0.5
    c.confidences.python_depth = 0.1
    const result = decideWith(c)
    expect(result.status).toBe('missing')
    expect(result.reasons).toHaveLength(3)
  })

  it('low confidence on a heavy skill goes to review', () => {
    const c = strong()
    c.confidences.python_depth = 0.3
    const result = decideWith(c)
    expect(result.status).toBe('review')
    expect(result.reasons).toEqual(['uncertain: Python depth (conf 0.30)'])
  })

  it('low confidence on a light skill is ignored', () => {
    const c = strong()
    c.confidences.domain_relevance = 0.1
    expect(decideWith(c).status).toBe('meets')
  })

  it('uses the what-if weight to decide what counts as heavy', () => {
    const c = strong()
    c.confidences.domain_relevance = 0.1
    const heavier = { ...spec, weights: { ...spec.weights, domain_relevance: 0.2 } }
    expect(decideWith(c, heavier).status).toBe('review')
  })

  it('treats all-zero weights as a zero composite', () => {
    const zero = { ...spec, weights: Object.fromEntries(Object.keys(spec.weights).map((k) => [k, 0])) }
    expect(decideWith(strong(), zero).composite).toBe(0)
  })
})

describe('rank', () => {
  it('orders by status, then composite', () => {
    const missing = strong('missing')
    missing.mustHaves.python_professional = 0
    const review = strong('review')
    review.mustHaves.backend_services = 0.5
    const meetsLow = strong('meets-low')
    meetsLow.scores.python_depth = 0
    const ranked = rank(job, [missing, review, meetsLow, strong('meets-high')], spec)
    expect(ranked.map((c) => c.name)).toEqual(['meets-high', 'meets-low', 'review', 'missing'])
    expect(ranked.map((c) => c.rank)).toEqual([1, 2, 3, 4])
  })

  it('breaks ties by name', () => {
    expect(rank(job, [strong('b'), strong('a')], spec).map((c) => c.name)).toEqual(['a', 'b'])
  })
})

describe('parity with the Java service', () => {
  it.each(fixtures.map((f) => [f.job.id, f] as const))(
    '%s: the spec values reproduce the server report exactly',
    (_id, { job: fixtureJob, report }) => {
      const ours = rank(fixtureJob, report.candidates, specPolicy(fixtureJob))
      expect(ours.map((c) => [c.rank, c.name, c.status])).toEqual(
        report.candidates.map((c) => [c.rank, c.name, c.status]),
      )
      ours.forEach((c, i) => {
        expect(c.composite).toBeCloseTo(report.candidates[i].composite, 9)
        expect(c.reasons).toEqual(report.candidates[i].reasons)
      })
    },
  )

  it('the backend fixture covers every status', () => {
    const statuses = new Set((backend as unknown as Fixture).report.candidates.map((c) => c.status))
    expect(statuses).toEqual(new Set(['meets', 'review', 'missing']))
  })
})

describe('format2', () => {
  it.each([
    [0.05, '0.05'],
    [0.5, '0.50'],
    [0.125, '0.13'],
    [1.005, '1.01'],
    [0.995, '1.00'],
    [0, '0.00'],
    [0.3, '0.30'],
    [1e-7, '0.00'],
  ])('%s → %s, as Java formats it', (value, expected) => {
    expect(format2(value)).toBe(expected)
  })
})

describe('samePolicy', () => {
  it('compares weights and thresholds', () => {
    expect(samePolicy(spec, specPolicy(job))).toBe(true)
    expect(samePolicy(spec, { ...spec, thresholds: { ...spec.thresholds, minConfidence: 0.5 } })).toBe(false)
    expect(samePolicy(spec, { ...spec, weights: { ...spec.weights, python_depth: 0.31 } })).toBe(false)
  })
})
