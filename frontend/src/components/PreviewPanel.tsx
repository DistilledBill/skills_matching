import { useQuery } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { api, type Preview, type Question, type Source } from '../api'
import { estimateTokens, sourceKey } from '../format'
import { ErrorBox } from './ErrorBox'

/** What to preview: a resume from a folder or upload, or text that hasn't been saved yet. */
export type PreviewTarget = { source: Source; name?: string } | { text: string; name: string }

/** Shows the exact request one resume would send to Jev. Never calls Jev, so it works without an API key. */
export function PreviewPanel({ jobId, target }: { jobId: string; target: PreviewTarget }) {
  const query = useQuery({
    queryKey: ['preview', jobId, targetKey(target)],
    queryFn: () =>
      'source' in target
        ? api.preview(jobId, target.source, target.name)
        : api.previewText(jobId, target.name, target.text),
  })
  if (query.isPending) return <p className="muted">Building the request…</p>
  if (query.isError) return <ErrorBox error={query.error} />
  return <PreviewView preview={query.data} />
}

export function PreviewView({ preview }: { preview: Preview }) {
  const [tab, setTab] = useState<'readable' | 'raw'>('readable')
  const { request, cached } = preview
  const raw = JSON.stringify(request, null, 2)
  const size = JSON.stringify(request).length
  const entries = Object.entries(request.questions)
  const mustHaves = entries.filter(([, q]) => q.type === 'noul')
  const scores = entries.filter(([, q]) => q.type === 'score')

  return (
    <section className="preview" aria-label="What gets sent to Jev">
      <div className="preview-bar">
        <div className="tabs" role="tablist">
          <button role="tab" aria-selected={tab === 'readable'} onClick={() => setTab('readable')}>
            Readable
          </button>
          <button role="tab" aria-selected={tab === 'raw'} onClick={() => setTab('raw')}>
            Raw JSON
          </button>
        </div>
        <span className={`badge ${cached ? 'badge-good' : 'badge-warn'}`}>
          {cached ? 'Cached: screening this is free' : 'Not cached: screening calls Jev'}
        </span>
      </div>

      {tab === 'raw' ? (
        <div className="raw">
          <button className="button-small" onClick={() => void navigator.clipboard?.writeText(raw)}>
            Copy
          </button>
          <pre>{raw}</pre>
        </div>
      ) : (
        <div className="readable">
          <p className="muted">
            Model <code>{request.model}</code> · {size.toLocaleString()} characters · about{' '}
            {estimateTokens(size).toLocaleString()} input tokens (estimate)
          </p>

          <h4>State</h4>
          <dl className="state">
            <dt>Job title</dt>
            <dd>{request.state.job.title}</dd>
            <dt>Target level</dt>
            <dd>{request.state.job.target_level}</dd>
            <dt>Job summary</dt>
            <dd>{request.state.job.summary}</dd>
            <dt>Resume, as Jev receives it</dt>
            <dd>
              <pre className="resume-text">{highlightRedactions(request.state.resume)}</pre>
            </dd>
          </dl>

          <h4>Must-haves ({mustHaves.length}): yes/no questions</h4>
          {mustHaves.map(([id, q]) => (
            <QuestionCard key={id} id={id} question={q} />
          ))}

          <h4>Skills ({scores.length}): scored on levels</h4>
          <p className="muted">Weights and thresholds are applied in code afterwards. Jev never sees them.</p>
          {scores.map(([id, q]) => (
            <QuestionCard key={id} id={id} question={q} />
          ))}
        </div>
      )}
    </section>
  )
}

function QuestionCard({ id, question }: { id: string; question: Question }) {
  if (question.type === 'noul') {
    const ins = typeof question.instructions === 'string' ? { question: question.instructions } : question.instructions
    return (
      <article className="qcard">
        <header>
          <code>{id}</code> <span className="muted">Noul</span>
        </header>
        {ins.requirement && (
          <p>
            <strong>Requirement:</strong> {ins.requirement}
          </p>
        )}
        <p>
          <strong>Question:</strong> {ins.question}
        </p>
        {question.criteria && (
          <ul className="criteria">
            <li>
              <strong>Yes:</strong> {question.criteria.true}
            </li>
            <li>
              <strong>No:</strong> {question.criteria.false}
            </li>
          </ul>
        )}
      </article>
    )
  }
  return (
    <article className="qcard">
      <header>
        <code>{id}</code> <span className="muted">Score</span>
      </header>
      <p>
        <strong>Question:</strong> {question.instructions}
      </p>
      <ol start={0} className="levels">
        {question.criteria.map((level, i) => (
          <li key={i}>{level}</li>
        ))}
      </ol>
    </article>
  )
}

/** Wraps [email], [phone] and [link] in <mark> so redactions stand out. */
export function highlightRedactions(text: string): ReactNode[] {
  return text
    .split(/(\[(?:email|phone|link)\])/g)
    .map((part, i) => (i % 2 === 1 ? <mark key={i}>{part}</mark> : part))
}

function targetKey(target: PreviewTarget): string {
  return 'source' in target ? `${sourceKey(target.source)}#${target.name ?? ''}` : `text#${target.name}#${target.text}`
}
