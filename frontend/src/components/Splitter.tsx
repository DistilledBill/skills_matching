import { useLayoutEffect, useState, type RefObject } from 'react'

export const MIN_PANEL = 320
export const MIN_MAIN = 360
const STEP = 24
const SPLITTER = 8
const STORAGE_KEY = 'results.panelWidth'

/** The remembered panel width, or null for the default. Storage can be missing or blocked, so access is guarded. */
function stored(): number | null {
  try {
    const value = Number(localStorage.getItem(STORAGE_KEY))
    return Number.isFinite(value) && value > 0 ? value : null
  } catch {
    return null
  }
}

function remember(width: number | null) {
  try {
    if (width === null) localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, String(width))
  } catch {
    // Not remembering is fine.
  }
}

/**
 * The right-hand panel's width in a two-pane split: about 40% of the container by default, otherwise the
 * width last chosen in this browser, always kept between the panes' minimums. The container is measured
 * when the split opens and whenever the window is resized.
 */
export function usePanelWidth(container: RefObject<HTMLElement | null>, active: boolean) {
  const [chosen, setChosen] = useState<number | null>(stored)
  const [total, setTotal] = useState(0)
  useLayoutEffect(() => {
    if (!active) return
    const measure = () => setTotal(container.current?.getBoundingClientRect().width ?? 0)
    measure()
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [active, container])

  const clamp = (width: number) => {
    const max = total - MIN_MAIN - SPLITTER
    return Math.round(Math.max(MIN_PANEL, max > MIN_PANEL ? Math.min(width, max) : width))
  }
  const width = clamp(chosen ?? (total > 0 ? total * 0.4 : 480))
  const set = (next: number | null) => {
    const value = next === null ? null : clamp(next)
    setChosen(value)
    remember(value)
  }
  return { width, set }
}

/** A vertical bar between two panes. Drag it, or focus it and use the arrow keys; double-click resets it. */
export function Splitter({
  width,
  onChange,
  label,
}: {
  width: number
  onChange: (width: number | null) => void
  label: string
}) {
  return (
    <div
      className="splitter"
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuenow={width}
      aria-valuemin={MIN_PANEL}
      tabIndex={0}
      onPointerDown={(e) => {
        e.preventDefault()
        const bar = e.currentTarget
        bar.setPointerCapture(e.pointerId)
        // The panel is everything right of the pointer, so its width follows from the container's right edge.
        const right = bar.parentElement!.getBoundingClientRect().right
        const move = (m: PointerEvent) => onChange(right - m.clientX - SPLITTER / 2)
        const up = () => {
          bar.removeEventListener('pointermove', move)
          bar.removeEventListener('pointerup', up)
        }
        bar.addEventListener('pointermove', move)
        bar.addEventListener('pointerup', up)
      }}
      onKeyDown={(e) => {
        // Left moves the bar left, which widens the panel on its right.
        if (e.key === 'ArrowLeft') onChange(width + STEP)
        else if (e.key === 'ArrowRight') onChange(width - STEP)
        else return
        e.preventDefault()
      }}
      onDoubleClick={() => onChange(null)}
    >
      <span className="splitter-grip" aria-hidden="true" />
    </div>
  )
}
