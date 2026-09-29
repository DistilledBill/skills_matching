/**
 * A 0–1 score as a small horizontal bar with the score centred inside it. When a confidence is given it
 * sits beside the bar, highlighted when it is below the job's min_confidence.
 */
export function Bar({
  value,
  confidence,
  lowConfidence = false,
}: {
  value: number
  confidence?: number
  lowConfidence?: boolean
}) {
  const pct = Math.max(0, Math.min(1, value)) * 100
  return (
    <span className="bar">
      <span className="bar-track">
        <span className="bar-fill" style={{ width: `${pct}%` }} aria-hidden="true" />
        <span className="bar-value">{value.toFixed(2)}</span>
      </span>
      {confidence !== undefined && (
        <span className={`bar-conf${lowConfidence ? ' bar-conf-low' : ''}`}>
          {confidence.toFixed(2)}
          {lowConfidence && <span className="visually-hidden"> (low confidence)</span>}
        </span>
      )}
    </span>
  )
}
