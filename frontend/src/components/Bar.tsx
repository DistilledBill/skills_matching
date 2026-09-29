/** A 0–1 value as a small horizontal bar with the number beside it. */
export function Bar({ value, faded = false, title }: { value: number; faded?: boolean; title?: string }) {
  const pct = Math.max(0, Math.min(1, value)) * 100
  return (
    <span className={`bar${faded ? ' bar-faded' : ''}`} title={title}>
      <span className="bar-track" aria-hidden="true">
        <span className="bar-fill" style={{ width: `${pct}%` }} />
      </span>
      <span className="bar-value">{value.toFixed(2)}</span>
    </span>
  )
}
