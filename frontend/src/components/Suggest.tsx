import { useMutation } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'

/**
 * A "✨ Suggest …" button that asks Claude for a draft and shows it in a review box. Nothing in the form
 * changes until Accept. Try again asks for another draft, with an optional hint when `withHint` is set.
 */
export function Suggest<T extends { warnings: string[] }>({
  label,
  enabled,
  run,
  render,
  onAccept,
  withHint = false,
  controls,
}: {
  label: string
  enabled: boolean
  run: (hint: string) => Promise<T>
  render: (suggestion: T) => ReactNode
  onAccept: (suggestion: T) => void
  withHint?: boolean
  /** Extra inputs shown beside the button, such as a level count. */
  controls?: ReactNode
}) {
  const [open, setOpen] = useState(false)
  const [hint, setHint] = useState('')
  // The request is built when the button is pressed, so it always sends the latest draft.
  const suggest = useMutation({ mutationFn: (request: () => Promise<T>) => request() })

  const ask = () => {
    setOpen(true)
    suggest.mutate(() => run(hint))
  }
  const close = () => {
    setOpen(false)
    suggest.reset()
  }

  return (
    <div className="suggest">
      <div className="suggest-bar">
        <button className="button button-secondary button-small" disabled={!enabled || suggest.isPending} onClick={ask}>
          ✨ {label}
        </button>
        {controls}
      </div>
      {open && (
        <div className="suggest-box" role="region" aria-label={`${label}: suggestion`}>
          {suggest.isPending && <p className="muted small">Asking Claude…</p>}
          {suggest.error && (
            <p className="field-error" role="alert">
              {suggest.error.message}
            </p>
          )}
          {suggest.data && (
            <>
              {render(suggest.data)}
              {suggest.data.warnings.map((w) => (
                <p key={w} className="small cost-paid">
                  <strong>Note:</strong> {w}
                </p>
              ))}
            </>
          )}
          {withHint && (
            <input
              className="suggest-hint"
              aria-label="Hint for the next suggestion"
              placeholder="Optional hint for Try again, e.g. focus on Kafka"
              value={hint}
              onChange={(e) => setHint(e.target.value)}
            />
          )}
          <div className="actions">
            <button
              className="button button-small"
              disabled={!suggest.data || suggest.isPending}
              onClick={() => {
                onAccept(suggest.data!)
                close()
              }}
            >
              Accept
            </button>
            <button className="button button-secondary button-small" disabled={suggest.isPending} onClick={ask}>
              Try again
            </button>
            <button className="button button-secondary button-small" onClick={close}>
              Discard
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
