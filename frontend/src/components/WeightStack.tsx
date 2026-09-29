import { humanize } from '../format'

/** Colour for the skill at this position; the same index colours its swatch in the list. */
export const segmentColor = (i: number) => `var(--seg-${(i % 8) + 1})`

/**
 * One vertical bar split into a segment per skill. Each segment's height is its share of the total
 * weight (the same share the composite uses), so the segments always fill the bar even when the spec's
 * weights don't add up to 1.
 */
export function WeightStack({ skills }: { skills: { id: string; weight: number }[] }) {
  const total = skills.reduce((sum, c) => sum + c.weight, 0) || 1
  const shares = skills.map((c) => ({ id: c.id, share: c.weight / total }))
  const label = shares.map((s) => `${humanize(s.id)} ${Math.round(s.share * 100)}%`).join(', ')
  return (
    <span className="weight-stack" role="img" aria-label={`Weights: ${label}`}>
      {shares.map((s, i) => (
        <span
          key={s.id}
          className="weight-seg"
          style={{ height: `${(s.share * 100).toFixed(3)}%`, background: segmentColor(i) }}
        />
      ))}
    </span>
  )
}
