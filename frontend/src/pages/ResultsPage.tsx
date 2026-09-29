import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, type CandidateResult, type JobSpec, type Status } from '../api'
import { Bar } from '../components/Bar'
import { ErrorBox } from '../components/ErrorBox'
import { PreviewPanel } from '../components/PreviewPanel'
import { StatusPill } from '../components/StatusPill'
import { describeSource, fixed, humanize, mustHaveBand } from '../format'
import { useResults, type ScreeningRun } from '../results'

type SortKey = 'rank' | 'name' | 'composite' | `comp:${string}`

export function ResultsPage() {
  const { jobId = '' } = useParams()
  const run = useResults().get(jobId)
  const jobs = useQuery({ queryKey: ['jobs'], queryFn: api.jobs })
  const job = jobs.data?.find((j) => j.id === jobId)

  if (!run) {
    return (
      <>
        <h1>Results</h1>
        <p className="lede">
          No screening has been run for this job in this tab yet.{' '}
          <Link to={`/jobs/${jobId}/screen`}>Screen resumes</Link>. Resumes that were screened before come back
          from the cache, so re-running costs nothing.
        </p>
      </>
    )
  }
  if (!job) return jobs.isError ? <ErrorBox error={jobs.error} /> : <p className="muted">Loading…</p>
  return <Results job={job} run={run} />
}

function Results({ job, run }: { job: JobSpec; run: ScreeningRun }) {
  const [filter, setFilter] = useState<Status | 'all'>('all')
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'rank', desc: false })
  const [selected, setSelected] = useState<string | null>(null)
  const candidates = run.report.candidates

  const counts = useMemo(() => {
    const c = { meets: 0, review: 0, missing: 0 }
    candidates.forEach((r) => c[r.status]++)
    return c
  }, [candidates])

  const rows = useMemo(() => {
    const visible = candidates.filter((c) => filter === 'all' || c.status === filter)
    const value = (c: CandidateResult): number | string =>
      sort.key === 'rank'
        ? c.rank
        : sort.key === 'name'
          ? c.name
          : sort.key === 'composite'
            ? c.composite
            : (c.scores[sort.key.slice(5)] ?? 0)
    return [...visible].sort((a, b) => {
      const [x, y] = [value(a), value(b)]
      const cmp = typeof x === 'string' ? x.localeCompare(String(y)) : x - (y as number)
      return sort.desc ? -cmp : cmp
    })
  }, [candidates, filter, sort])

  const csv = useMutation({
    mutationFn: () => api.downloadCsv(job.id, run.source),
    onSuccess: ({ blob, fileName }) => {
      const url = URL.createObjectURL(blob)
      const a = Object.assign(document.createElement('a'), { href: url, download: fileName })
      a.click()
      URL.revokeObjectURL(url)
    },
  })

  function sortBy(key: SortKey) {
    // Scores read best-first by default; rank and name read top-down.
    setSort((s) => (s.key === key ? { key, desc: !s.desc } : { key, desc: key !== 'rank' && key !== 'name' }))
  }

  const header = (key: SortKey, label: string) => (
    <th aria-sort={sort.key === key ? (sort.desc ? 'descending' : 'ascending') : 'none'}>
      <button className="th-button" onClick={() => sortBy(key)}>
        {label}
        {sort.key === key ? (sort.desc ? ' ↓' : ' ↑') : ''}
      </button>
    </th>
  )

  const current = candidates.find((c) => c.name === selected)

  return (
    <>
      <p className="crumbs">
        <Link to="/">Jobs</Link> / {job.title}
      </p>
      <div className="title-row">
        <h1>Results</h1>
        <div className="actions">
          <Link className="button button-secondary" to={`/jobs/${job.id}/screen`}>
            Screen again
          </Link>
          <button className="button" onClick={() => csv.mutate()} disabled={csv.isPending}>
            {csv.isPending ? 'Preparing…' : 'Download CSV'}
          </button>
        </div>
      </div>
      <p className="lede">
        {candidates.length} candidates from {describeSource(run.source)}, screened {run.ranAt.toLocaleTimeString()}.
        Ranked by status, then composite.
      </p>
      <ErrorBox error={csv.error} />

      <div className="filters" role="radiogroup" aria-label="Filter by status">
        {(['all', 'meets', 'review', 'missing'] as const).map((f) => (
          <button key={f} role="radio" aria-checked={filter === f} onClick={() => setFilter(f)}>
            {f === 'all' ? `All ${candidates.length}` : `${f} ${counts[f]}`}
          </button>
        ))}
      </div>

      <div className="table-wrap">
        <table className="results">
          <thead>
            <tr>
              {header('rank', '#')}
              {header('name', 'Candidate')}
              <th>Status</th>
              {header('composite', 'Composite')}
              {job.competencies.map((c) => (
                <th key={c.id} className="comp-col">
                  <button className="th-button" onClick={() => sortBy(`comp:${c.id}`)} title={c.question}>
                    {humanize(c.id)}
                    <span className="muted"> {fixed(c.weight)}</span>
                    {sort.key === `comp:${c.id}` ? (sort.desc ? ' ↓' : ' ↑') : ''}
                  </button>
                </th>
              ))}
              <th>Must-haves</th>
              <th>Reasons</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr
                key={c.name}
                className={c.name === selected ? 'selected' : undefined}
                onClick={() => setSelected(c.name)}
                tabIndex={0}
                onKeyDown={(e) => e.key === 'Enter' && setSelected(c.name)}
              >
                <td className="num">{c.rank}</td>
                <td>
                  <strong>{c.name}</strong>
                </td>
                <td>
                  <StatusPill status={c.status} />
                </td>
                <td>
                  <Bar value={c.composite} />
                </td>
                {job.competencies.map((comp) => {
                  const conf = c.confidences[comp.id] ?? 1
                  const low = conf < job.thresholds.minConfidence
                  return (
                    <td key={comp.id}>
                      <Bar
                        value={c.scores[comp.id] ?? 0}
                        faded={low}
                        title={`confidence ${fixed(conf)}${low ? ' (low)' : ''}`}
                      />
                    </td>
                  )
                })}
                <td>
                  <MustHaveChips job={job} candidate={c} />
                </td>
                <td className="reasons">{c.reasons.join('; ') || <span className="muted">none</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small">
        Faded bars have confidence below {fixed(job.thresholds.minConfidence)}. Select a row for details and the
        exact request sent to Jev.
      </p>

      {current && <CandidatePanel job={job} run={run} candidate={current} onClose={() => setSelected(null)} />}
    </>
  )
}

function MustHaveChips({ job, candidate }: { job: JobSpec; candidate: CandidateResult }) {
  return (
    <span className="chips">
      {job.mustHaves.map((m) => {
        const p = candidate.mustHaves[m.id] ?? 0
        return (
          <span key={m.id} className={`chip chip-${mustHaveBand(p, job.thresholds)}`} title={m.requirement}>
            {fixed(p)}
          </span>
        )
      })}
    </span>
  )
}

function CandidatePanel({
  job,
  run,
  candidate,
  onClose,
}: {
  job: JobSpec
  run: ScreeningRun
  candidate: CandidateResult
  onClose: () => void
}) {
  const [tab, setTab] = useState<'scores' | 'request'>('scores')
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <aside className="drawer" aria-label={`Details for ${candidate.name}`}>
      <header className="drawer-header">
        <h2>
          #{candidate.rank} {candidate.name} <StatusPill status={candidate.status} />
        </h2>
        <button className="link" onClick={onClose} aria-label="Close details">
          Close
        </button>
      </header>
      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={tab === 'scores'} onClick={() => setTab('scores')}>
          Scores
        </button>
        <button role="tab" aria-selected={tab === 'request'} onClick={() => setTab('request')}>
          What was sent to Jev
        </button>
      </div>
      {tab === 'scores' ? (
        <div className="drawer-body">
          <p>
            Composite <strong>{fixed(candidate.composite, 3)}</strong>
          </p>
          {candidate.reasons.length > 0 && (
            <ul className="plain">
              {candidate.reasons.map((r) => (
                <li key={r}>{r}</li>
              ))}
            </ul>
          )}
          <h3>Must-haves</h3>
          <ul className="plain">
            {job.mustHaves.map((m) => {
              const p = candidate.mustHaves[m.id] ?? 0
              return (
                <li key={m.id}>
                  <span className={`chip chip-${mustHaveBand(p, job.thresholds)}`}>{fixed(p)}</span> {m.requirement}
                </li>
              )
            })}
          </ul>
          <h3>Competencies</h3>
          <ul className="plain">
            {job.competencies.map((c) => (
              <li key={c.id}>
                <strong>{humanize(c.id)}</strong> (weight {fixed(c.weight)}):{' '}
                <Bar value={candidate.scores[c.id] ?? 0} /> confidence {fixed(candidate.confidences[c.id] ?? 0)}
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <div className="drawer-body">
          <PreviewPanel jobId={job.id} target={{ source: run.source, name: candidate.name }} />
        </div>
      )}
    </aside>
  )
}
