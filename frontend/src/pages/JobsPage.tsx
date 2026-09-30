import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api, type JobSpec } from '../api'
import { ErrorBox } from '../components/ErrorBox'
import { segmentColor, WeightStack } from '../components/WeightStack'
import { fixed, humanize } from '../format'
import { useResults } from '../results'

export function JobsPage() {
  const jobs = useQuery({ queryKey: ['jobs'], queryFn: api.jobs })
  return (
    <>
      <h1>Jobs</h1>
      <p className="lede">Each job spec defines what Jev judges. Pick one to screen resumes against it.</p>
      {jobs.isPending && <p className="muted">Loading job specs…</p>}
      <ErrorBox error={jobs.error} />
      <div className="card-grid">
        {jobs.data?.map((job) => (
          <JobCard key={job.id} job={job} />
        ))}
      </div>
    </>
  )
}

function JobCard({ job }: { job: JobSpec }) {
  const results = useResults()
  const run = results.get(job.id)
  const t = job.thresholds
  return (
    <article className="card job-card">
      <header>
        <h2>{job.title}</h2>
        <code className="muted">{job.id}</code>
        <span className="target-level">Target level: {job.targetLevel}</span>
      </header>
      <p className="summary">{job.summary}</p>

      <h3>Must-haves</h3>
      <ul className="plain">
        {job.mustHaves.map((m) => (
          <li key={m.id}>{m.requirement}</li>
        ))}
      </ul>

      <h3>Skills</h3>
      <div className="weights-block">
        <ul className="weights">
          {job.skills.map((c, i) => (
            <li key={c.id}>
              <span className="swatch" style={{ background: segmentColor(i) }} aria-hidden="true" />
              <span>{humanize(c.id)}</span>
              <span className="num">{fixed(c.weight)}</span>
            </li>
          ))}
        </ul>
        <WeightStack skills={job.skills} />
      </div>

      <p className="muted small">
        Thresholds: pass ≥ {fixed(t.mustHavePass)} · fail &lt; {fixed(t.mustHaveFail)} · min confidence{' '}
        {fixed(t.minConfidence)}
      </p>

      <footer className="actions">
        <Link className="button" to={`/jobs/${job.id}/screen`}>
          Screen resumes
        </Link>
        {run && (
          <Link className="button button-secondary" to={`/jobs/${job.id}/results`}>
            Latest results
          </Link>
        )}
      </footer>
    </article>
  )
}
