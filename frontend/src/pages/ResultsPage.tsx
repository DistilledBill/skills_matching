import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, type CandidateResult, type JobSpec, type Status } from '../api'
import { Bar } from '../components/Bar'
import { ErrorBox } from '../components/ErrorBox'
import { PreviewPanel } from '../components/PreviewPanel'
import { ResumeView } from '../components/ResumeView'
import { Splitter, usePanelWidth } from '../components/Splitter'
import { StatusPill } from '../components/StatusPill'
import { WhatIfPanel } from '../components/WhatIfPanel'
import { describeSource, fixed, humanize, mustHaveBand } from '../format'
import { rank, samePolicy, specPolicy, type Policy } from '../policy'
import { useResults, type ScreeningRun } from '../results'

type SortKey = 'rank' | 'name' | 'composite' | `skill:${string}` | `must:${string}`

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
  // Keyed by run so what-if values, filters and the selection reset when a new screening comes in.
  return <Results key={run.ranAt.getTime()} job={job} run={run} />
}

export function Results({ job, run }: { job: JobSpec; run: ScreeningRun }) {
  const [filter, setFilter] = useState<Status | 'all'>('all')
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'rank', desc: false })
  const [selected, setSelected] = useState<string | null>(null)
  // null means the spec's own values, so the table shows the server's ranking unchanged.
  const [whatIf, setWhatIf] = useState<Policy | null>(null)
  const policy = whatIf ?? specPolicy(job)
  const t = policy.thresholds
  const weightOf = (id: string) => policy.weights[id] ?? 0

  const candidates = useMemo(
    () => (whatIf ? rank(job, run.report.candidates, whatIf) : run.report.candidates),
    [job, run.report.candidates, whatIf],
  )
  const specRank = useMemo(
    () => new Map(run.report.candidates.map((c) => [c.name, c.rank])),
    [run.report.candidates],
  )
  const changePolicy = (next: Policy) => setWhatIf(samePolicy(next, specPolicy(job)) ? null : next)

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
            : sort.key.startsWith('must:')
              ? (c.mustHaves[sort.key.slice(5)] ?? 0)
              : (c.scores[sort.key.slice(6)] ?? 0)
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
  const split = useRef<HTMLDivElement>(null)
  const panel = usePanelWidth(split, !!current)

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

      <WhatIfPanel
        job={job}
        policy={policy}
        modified={whatIf !== null}
        onChange={changePolicy}
        onReset={() => setWhatIf(null)}
      />
      {whatIf && (
        <p className="what-if-banner" role="status">
          <strong>What-if values:</strong> this ranking isn't saved, and arrows show the change from the spec's
          ranking. Download CSV uses the spec's values.
        </p>
      )}

      {/* On wide screens the candidate panel docks to the right of the table, with a splitter between them. */}
      <div
        ref={split}
        className={current ? 'split split-open' : 'split'}
        style={current ? ({ '--panel-w': `${panel.width}px` } as CSSProperties) : undefined}
      >
        <div className="split-main">
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
                  {job.skills.map((c) => (
                    <th key={c.id} className="skill-col">
                      <button className="th-button" onClick={() => sortBy(`skill:${c.id}`)} title={c.question}>
                        {humanize(c.id)}
                        <span className="muted"> {fixed(weightOf(c.id))}</span>
                        {sort.key === `skill:${c.id}` ? (sort.desc ? ' ↓' : ' ↑') : ''}
                        <span className="th-sub">score · conf.</span>
                      </button>
                    </th>
                  ))}
                  {job.mustHaves.map((m) => (
                    <th key={m.id} className="skill-col">
                      <button className="th-button" onClick={() => sortBy(`must:${m.id}`)} title={m.requirement}>
                        {humanize(m.id)}
                        {sort.key === `must:${m.id}` ? (sort.desc ? ' ↓' : ' ↑') : ''}
                        <span className="th-sub">probability met</span>
                      </button>
                    </th>
                  ))}
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
                    <td className="num">
                      {c.rank}
                      {whatIf && <RankChange from={specRank.get(c.name) ?? c.rank} to={c.rank} />}
                    </td>
                    <td>
                      <strong>{c.name}</strong>
                    </td>
                    <td>
                      <StatusPill status={c.status} />
                    </td>
                    <td>
                      <Bar value={c.composite} />
                    </td>
                    {job.skills.map((skill) => {
                      const conf = c.confidences[skill.id] ?? 1
                      return (
                        <td key={skill.id}>
                          <Bar
                            value={c.scores[skill.id] ?? 0}
                            confidence={conf}
                            lowConfidence={conf < t.minConfidence}
                          />
                        </td>
                      )
                    })}
                    {job.mustHaves.map((m) => {
                      const p = c.mustHaves[m.id] ?? 0
                      return (
                        <td key={m.id}>
                          <span className={`chip chip-${mustHaveBand(p, t)}`}>{fixed(p)}</span>
                        </td>
                      )
                    })}
                    <td className="reasons">{c.reasons.join('; ') || <span className="muted">none</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="muted small">
            Score (0–1) is inside each bar; the number beside it is Jev's confidence. Confidence below{' '}
            {fixed(t.minConfidence)} is shown in amber. Select a row for details and the exact request
            sent to Jev.
          </p>
        </div>

        {current && (
          <>
            <Splitter width={panel.width} onChange={panel.set} label="Resize the candidate panel" />
            <CandidatePanel job={job} run={run} policy={policy} candidate={current} onClose={() => setSelected(null)} />
          </>
        )}
      </div>
    </>
  )
}

/** How far a candidate moved from the spec's ranking under the what-if values. */
function RankChange({ from, to }: { from: number; to: number }) {
  if (from === to) return null
  const up = to < from
  return (
    <span className={`rank-change ${up ? 'rank-up' : 'rank-down'}`}>
      {up ? '▲' : '▼'}
      {Math.abs(from - to)}
      <span className="visually-hidden">{up ? ' places up' : ' places down'}</span>
    </span>
  )
}

function CandidatePanel({
  job,
  run,
  policy,
  candidate,
  onClose,
}: {
  job: JobSpec
  run: ScreeningRun
  policy: Policy
  candidate: CandidateResult
  onClose: () => void
}) {
  const [tab, setTab] = useState<'scores' | 'resume' | 'request'>('scores')
  const t = policy.thresholds
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
        <button role="tab" aria-selected={tab === 'resume'} onClick={() => setTab('resume')}>
          Resume
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
                  <span className="item-title">{humanize(m.id)}</span>
                  <span className={`chip chip-${mustHaveBand(p, t)}`}>{fixed(p)}</span> {m.requirement}
                </li>
              )
            })}
          </ul>
          <h3>
            Skills <span className="small">(score in the bar, confidence beside it)</span>
          </h3>
          <ul className="plain">
            {job.skills.map((c) => {
              const conf = candidate.confidences[c.id] ?? 1
              return (
                <li key={c.id}>
                  <strong>{humanize(c.id)}</strong> (weight {fixed(policy.weights[c.id] ?? 0)}):{' '}
                  <Bar
                    value={candidate.scores[c.id] ?? 0}
                    confidence={conf}
                    lowConfidence={conf < t.minConfidence}
                  />
                </li>
              )
            })}
          </ul>
        </div>
      ) : tab === 'resume' ? (
        <div className="drawer-body">
          <ResumeView source={run.source} name={candidate.name} />
        </div>
      ) : (
        <div className="drawer-body">
          <PreviewPanel jobId={job.id} target={{ source: run.source, name: candidate.name }} />
        </div>
      )}
    </aside>
  )
}
