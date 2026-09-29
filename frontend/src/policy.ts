// A TypeScript copy of ScreeningPolicy.decide and CandidateResult.RANKING
// (src/main/java/com/example/resumescreening/screening/), used for what-if
// tuning in the browser. Keep the two in step: policy.test.ts checks this copy
// against a real report produced by the Java code.

import type { CandidateResult, JobSpec, Status, Thresholds } from './api'

/** Skills weighted at least this much send low-confidence answers to review (ScreeningPolicy.HEAVY_WEIGHT). */
export const HEAVY_WEIGHT = 0.2

/** The weights and thresholds a ranking is computed with. Jev never sees these. */
export interface Policy {
  weights: Record<string, number>
  thresholds: Thresholds
}

const STATUS_ORDER: Record<Status, number> = { meets: 0, review: 1, missing: 2 }

export function specPolicy(job: JobSpec): Policy {
  return {
    weights: Object.fromEntries(job.skills.map((c) => [c.id, c.weight])),
    thresholds: { ...job.thresholds },
  }
}

export function samePolicy(a: Policy, b: Policy): boolean {
  const t = (k: keyof Thresholds) => a.thresholds[k] === b.thresholds[k]
  const keys = new Set([...Object.keys(a.weights), ...Object.keys(b.weights)])
  return t('mustHavePass') && t('mustHaveFail') && t('minConfidence') && [...keys].every((k) => a.weights[k] === b.weights[k])
}

/**
 * Re-decides one candidate from the answers already in the report. Scores in the report are normalized
 * (score / max level), so only the weights and thresholds change the outcome. Rank is left as it was.
 */
export function decide(job: JobSpec, candidate: CandidateResult, policy: Policy): CandidateResult {
  const t = policy.thresholds
  let status: Status = 'meets'
  const reasons: string[] = []

  for (const req of job.mustHaves) {
    const p = candidate.mustHaves[req.id] ?? 0
    if (p < t.mustHaveFail) {
      status = 'missing'
      reasons.push(`not shown: ${req.id} (${format2(p)})`)
    } else if (p < t.mustHavePass) {
      status = worse(status, 'review')
      reasons.push(`unclear: ${req.id} (${format2(p)})`)
    }
  }

  const weight = (id: string) => policy.weights[id] ?? 0
  const totalWeight = job.skills.reduce((sum, c) => sum + weight(c.id), 0)
  let composite = 0
  for (const skill of job.skills) {
    const normalized = candidate.scores[skill.id] ?? 0
    const confidence = candidate.confidences[skill.id] ?? 1
    // All-zero weights would divide by zero; treat the composite as 0 instead.
    if (totalWeight > 0) composite += (weight(skill.id) / totalWeight) * normalized
    if (weight(skill.id) >= HEAVY_WEIGHT && confidence < t.minConfidence) {
      status = worse(status, 'review')
      reasons.push(`uncertain: ${skill.id} (conf ${format2(confidence)})`)
    }
  }

  return { ...candidate, status, composite, reasons }
}

/** Re-decides every candidate and ranks them: status first, then highest composite, then name. */
export function rank(job: JobSpec, candidates: CandidateResult[], policy: Policy): CandidateResult[] {
  return candidates
    .map((c) => decide(job, c, policy))
    .sort(
      (a, b) =>
        STATUS_ORDER[a.status] - STATUS_ORDER[b.status] ||
        b.composite - a.composite ||
        (a.name < b.name ? -1 : a.name > b.name ? 1 : 0),
    )
    .map((c, i) => ({ ...c, rank: i + 1 }))
}

function worse(a: Status, b: Status): Status {
  return STATUS_ORDER[a] >= STATUS_ORDER[b] ? a : b
}

/**
 * Java's String.format("%.2f") for a value in the reasons text: it rounds the shortest decimal form of the
 * number half-up, where toFixed rounds the exact binary value (so 1.005 gives "1.01" here, "1.00" from toFixed).
 */
export function format2(value: number): string {
  const text = String(Math.abs(value))
  if (text.includes('e')) return value.toFixed(2)
  const [whole, frac = ''] = text.split('.')
  const digits = (whole + (frac + '000').slice(0, 3)).split('').map(Number)
  let carry = digits.pop()! >= 5 ? 1 : 0
  for (let i = digits.length - 1; i >= 0 && carry; i--) {
    const d = digits[i] + carry
    digits[i] = d % 10
    carry = d >= 10 ? 1 : 0
  }
  if (carry) digits.unshift(1)
  const out = digits.join('')
  const rounded = `${out.slice(0, -2) || '0'}.${out.slice(-2)}`
  return value < 0 && Number(rounded) !== 0 ? `-${rounded}` : rounded
}
