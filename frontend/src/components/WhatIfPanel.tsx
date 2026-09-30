import { useNavigate } from 'react-router-dom'
import type { JobSpec, Thresholds } from '../api'
import { fixed, humanize } from '../format'
import { HEAVY_WEIGHT, type Policy } from '../policy'

/** Slider values are kept to two decimals, so returning a slider to a spec value restores it exactly. */
const round2 = (v: number) => Math.round(v * 100) / 100

/**
 * Sliders for the skill weights and thresholds. Changes re-rank the table in the browser only;
 * nothing is saved and no API call is made, because Jev never sees weights or thresholds.
 */
export function WhatIfPanel({
  job,
  policy,
  modified,
  onChange,
  onReset,
}: {
  job: JobSpec
  policy: Policy
  modified: boolean
  onChange: (policy: Policy) => void
  onReset: () => void
}) {
  const navigate = useNavigate()
  const total = job.skills.reduce((sum, c) => sum + (policy.weights[c.id] ?? 0), 0)
  const t = policy.thresholds

  const setWeight = (id: string, value: number) => onChange({ ...policy, weights: { ...policy.weights, [id]: value } })
  const setThreshold = (key: keyof Thresholds, value: number) => {
    const next = { ...t, [key]: value }
    // Keep fail <= pass: moving one past the other drags the other along.
    if (key === 'mustHaveFail' && value > next.mustHavePass) next.mustHavePass = value
    if (key === 'mustHavePass' && value < next.mustHaveFail) next.mustHaveFail = value
    onChange({ ...policy, thresholds: next })
  }

  return (
    <details className="card what-if">
      <summary>
        <strong>What-if tuning</strong>{' '}
        <span className="muted small">
          {modified ? 'using what-if values' : 'try other weights and thresholds; nothing is saved'}
        </span>
      </summary>
      <div className="what-if-grid">
        <fieldset>
          <legend>Skill weights</legend>
          {job.skills.map((c) => {
            const w = policy.weights[c.id] ?? 0
            return (
              <Slider
                key={c.id}
                label={humanize(c.id)}
                value={w}
                changed={w !== c.weight}
                onChange={(v) => setWeight(c.id, v)}
                note={w >= HEAVY_WEIGHT ? 'heavy' : undefined}
              />
            )
          })}
          <p className="muted small">
            <WeightTotal total={total} /> The composite uses each weight's share of the total. Weights of{' '}
            {fixed(HEAVY_WEIGHT, 1)} or more are heavy: low confidence on them sends a candidate to review.
          </p>
        </fieldset>
        <fieldset>
          <legend>Thresholds</legend>
          <Slider
            label="Must-have pass"
            value={t.mustHavePass}
            changed={t.mustHavePass !== job.thresholds.mustHavePass}
            onChange={(v) => setThreshold('mustHavePass', v)}
          />
          <Slider
            label="Must-have fail"
            value={t.mustHaveFail}
            changed={t.mustHaveFail !== job.thresholds.mustHaveFail}
            onChange={(v) => setThreshold('mustHaveFail', v)}
          />
          <Slider
            label="Min confidence"
            value={t.minConfidence}
            changed={t.minConfidence !== job.thresholds.minConfidence}
            onChange={(v) => setThreshold('minConfidence', v)}
          />
          <p className="muted small">
            A must-have at or above pass is met, below fail is missing, and in between goes to review.
          </p>
        </fieldset>
      </div>
      <div className="actions">
        <button className="button button-secondary" onClick={onReset} disabled={!modified}>
          Reset to spec values
        </button>
        <button
          className="button button-secondary"
          disabled={!modified}
          onClick={() => navigate(`/jobs/${job.id}/edit`, { state: { whatIf: policy } })}
        >
          Save to spec…
        </button>
      </div>
    </details>
  )
}

/**
 * The weights' total, in red with the direction and amount needed to get back to 1 whenever it isn't 1.00.
 * Compared at two decimals, like the sliders, so floating-point noise never shows as off.
 */
export function WeightTotal({ total }: { total: number }) {
  const shown = round2(total)
  const gap = round2(1 - shown)
  if (gap === 0) return <span className="weight-total">Total {fixed(shown)}.</span>
  const below = gap > 0
  return (
    <span className="weight-total weight-total-off">
      Total {fixed(shown)} {below ? '▲' : '▼'} {fixed(Math.abs(gap))}
      <span className="visually-hidden">{below ? ' below 1' : ' above 1'}</span>.
    </span>
  )
}

function Slider({
  label,
  value,
  changed,
  note,
  onChange,
}: {
  label: string
  value: number
  changed: boolean
  note?: string
  onChange: (value: number) => void
}) {
  return (
    <label className={`slider${changed ? ' slider-changed' : ''}`}>
      <span>{label}</span>
      <input
        type="range"
        min={0}
        max={1}
        step={0.01}
        value={value}
        onChange={(e) => onChange(round2(Number(e.target.value)))}
      />
      <output>{fixed(value)}</output>
      <span className="slider-note">{note}</span>
    </label>
  )
}
