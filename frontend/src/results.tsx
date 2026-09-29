import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import type { ScreeningReport, Source } from './api'

export interface ScreeningRun {
  report: ScreeningReport
  source: Source
  ranAt: Date
}

interface ResultsStore {
  get(jobId: string): ScreeningRun | undefined
  set(jobId: string, run: ScreeningRun): void
}

const ResultsContext = createContext<ResultsStore | null>(null)

/**
 * Keeps the latest screening per job for this browser tab. Uploaded files
 * can't be stored across reloads, so results live in memory only; re-running
 * a screening is free because answers are cached on the server.
 */
export function ResultsProvider({ children }: { children: ReactNode }) {
  const [runs, setRuns] = useState<Record<string, ScreeningRun>>({})
  const get = useCallback((jobId: string) => runs[jobId], [runs])
  const set = useCallback((jobId: string, run: ScreeningRun) => setRuns((prev) => ({ ...prev, [jobId]: run })), [])
  const store = useMemo(() => ({ get, set }), [get, set])
  return <ResultsContext.Provider value={store}>{children}</ResultsContext.Provider>
}

export function useResults(): ResultsStore {
  const store = useContext(ResultsContext)
  if (!store) throw new Error('useResults must be used inside ResultsProvider')
  return store
}
