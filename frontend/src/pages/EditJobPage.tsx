import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  ApiError,
  api,
  type AssistStatus,
  type JobSpec,
  type LevelsSuggestion,
  type Skill,
  type SkillSuggestion,
  type VersionedJobSpec,
} from '../api'
import { ClearCache } from '../components/ClearCache'
import { ErrorBox } from '../components/ErrorBox'
import { Suggest } from '../components/Suggest'
import { WeightTotal } from '../components/WhatIfPanel'
import { HEAVY_WEIGHT, type Policy } from '../policy'
import { changeCost, specToYaml, suggestId, validateSpec, type ChangeCost } from '../spec'

const MAX_LEVELS = 10

const BLANK: JobSpec = {
  id: '',
  title: '',
  targetLevel: '',
  summary: '',
  mustHaves: [],
  skills: [{ id: '', weight: 1, question: '', levels: ['', ''] }],
  thresholds: { mustHavePass: 0.8, mustHaveFail: 0.2, minConfidence: 0.45 },
}

/** Router state: `whatIf` comes from the Results page's Save to spec; `created` after a new job is saved. */
interface EditState {
  whatIf?: Policy
  created?: boolean
}

export function EditJobPage() {
  const { jobId } = useParams()
  const jobs = useQuery({ queryKey: ['jobs'], queryFn: api.jobs })
  const loaded = jobId ? jobs.data?.find((j) => j.id === jobId) : undefined

  if (jobs.isError) return <ErrorBox error={jobs.error} />
  if (jobId && jobs.isSuccess && !loaded) return <ErrorBox error={new Error(`No job spec with id '${jobId}'`)} />
  if (jobId && !loaded) return <p className="muted">Loading…</p>
  return <Editor key={jobId ?? 'new'} saved={loaded ?? null} />
}

function withoutVersion(job: VersionedJobSpec): JobSpec {
  const { version: _version, ...spec } = job
  return spec
}

function Editor({ saved: initial }: { saved: VersionedJobSpec | null }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const state = (useLocation().state ?? {}) as EditState

  // `saved` is what's on disk (null for a new job); `draft` is the form.
  const [saved, setSaved] = useState<VersionedJobSpec | null>(initial)
  const [draft, setDraft] = useState<JobSpec>(() => {
    const start = initial ? withoutVersion(initial) : BLANK
    if (!state.whatIf) return start
    const { weights, thresholds } = state.whatIf
    return { ...start, skills: start.skills.map((s) => ({ ...s, weight: weights[s.id] ?? s.weight })), thresholds }
  })
  const [idEdited, setIdEdited] = useState(false)
  const [showErrors, setShowErrors] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [lastSave, setLastSave] = useState<ChangeCost | null>(null)
  const [loadDiskConfirm, setLoadDiskConfirm] = useState(false)

  const isNew = saved === null
  const errors = useMemo(() => validateSpec(draft), [draft])
  const errorsAt = (path: string) => errors.filter((e) => e.path === path).map((e) => e.message)
  const cost = changeCost(saved ? withoutVersion(saved) : null, draft)
  const folders = useQuery({ queryKey: ['folders'], queryFn: api.folders })
  const assist = useQuery({ queryKey: ['assist'], queryFn: api.assist.status })
  const assistOn = assist.data?.enabled ?? false
  const resumeCount = folders.data?.find((f) => f.path === `${draft.id}_candidates`)?.fileCount

  const set = (patch: Partial<JobSpec>) => setDraft((d) => ({ ...d, ...patch }))
  const setSkill = (i: number, patch: Partial<Skill>) =>
    set({ skills: draft.skills.map((s, j) => (j === i ? { ...s, ...patch } : s)) })

  const remember = (job: VersionedJobSpec) =>
    queryClient.setQueryData<VersionedJobSpec[]>(['jobs'], (old = []) =>
      [...old.filter((j) => j.id !== job.id), job].sort((a, b) => a.id.localeCompare(b.id)),
    )

  const save = useMutation({
    mutationFn: () => (saved ? api.updateJob(saved.id, saved.version, draft) : api.createJob(draft)),
    onSuccess: (result) => {
      remember(result)
      void queryClient.invalidateQueries({ queryKey: ['folders'] })
      if (isNew) {
        navigate(`/jobs/${result.id}/edit`, { replace: true, state: { created: true } satisfies EditState })
        return
      }
      setLastSave(cost)
      setSaved(result)
      setDraft(withoutVersion(result))
      setConfirming(false)
    },
    onError: () => setConfirming(false),
  })

  const loadFromDisk = useMutation({
    mutationFn: () => api.reloadJob(saved!.id),
    onSuccess: (result) => {
      remember(result)
      setSaved(result)
      setDraft(withoutVersion(result))
      setLoadDiskConfirm(false)
      save.reset()
    },
  })

  function onSave() {
    setShowErrors(true)
    setLastSave(null)
    if (errors.length) return
    if (cost.kind === 'paid') setConfirming(true)
    else save.mutate()
  }

  const conflict = save.error instanceof ApiError && save.error.status === 409 && !isNew
  const serverErrors = save.error instanceof ApiError ? save.error.errors : []

  return (
    <>
      <p className="crumbs">
        <Link to="/">Jobs</Link> / {isNew ? 'New job' : saved.title}
      </p>
      <h1>{isNew ? 'New job spec' : 'Edit job spec'}</h1>
      {state.created && !isNew && (
        <p className="notice" role="status">
          Created <code>jobs/{saved.id}.yaml</code> and <code>resumes/{saved.id}_candidates/</code>. Add resumes to
          that folder, then <Link to={`/jobs/${saved.id}/screen`}>screen them</Link>.
        </p>
      )}
      {state.whatIf && (
        <p className="notice" role="status">
          The weights and thresholds from what-if tuning are filled in. Review them, then save.
        </p>
      )}

      <div className="editor">
        <div className="editor-form">
          <section className="card">
            <h2>Basics</h2>
            {isNew ? (
              <Field label="Job id (the file name)" errors={showErrors ? errorsAt('id') : []}>
                <input
                  value={draft.id}
                  onChange={(e) => {
                    setIdEdited(true)
                    set({ id: e.target.value })
                  }}
                  placeholder="director_of_engineering"
                />
              </Field>
            ) : (
              <p className="muted small">
                Job id <code>{saved.id}</code> (the file name; it can't be changed).
              </p>
            )}
            <Field label="Title" errors={showErrors ? errorsAt('title') : []}>
              <input
                value={draft.title}
                onChange={(e) =>
                  set({ title: e.target.value, ...(isNew && !idEdited ? { id: suggestId(e.target.value) } : {}) })
                }
              />
            </Field>
            <Field label="Target level" errors={showErrors ? errorsAt('targetLevel') : []}>
              <input
                value={draft.targetLevel}
                onChange={(e) => set({ targetLevel: e.target.value })}
                placeholder="Executive Director"
              />
            </Field>
            <Field
              label="Summary"
              hint="Context for Jev. Only questions that name `job.summary` use it."
              errors={showErrors ? errorsAt('summary') : []}
            >
              <textarea rows={5} value={draft.summary} onChange={(e) => set({ summary: e.target.value })} />
            </Field>
          </section>

          <section className="card">
            <h2>Must-haves</h2>
            <AssistNote status={assist.data} />
            <p className="muted small">
              Each becomes a yes/no question: "Does `resume` show evidence that the candidate meets `requirement`?"
            </p>
            {draft.mustHaves.map((m, i) => (
              <div className="item" key={i}>
                <div className="item-head">
                  <Field label="Id" errors={showErrors ? errorsAt(`mustHaves.${i}.id`) : []}>
                    <input
                      value={m.id}
                      onChange={(e) =>
                        set({ mustHaves: draft.mustHaves.map((x, j) => (j === i ? { ...x, id: e.target.value } : x)) })
                      }
                    />
                  </Field>
                  <ItemButtons
                    label={`must-have ${m.id || i + 1}`}
                    index={i}
                    count={draft.mustHaves.length}
                    onMove={(to) => set({ mustHaves: move(draft.mustHaves, i, to) })}
                    onRemove={() => set({ mustHaves: draft.mustHaves.filter((_, j) => j !== i) })}
                  />
                </div>
                <Field label="Requirement" errors={showErrors ? errorsAt(`mustHaves.${i}.requirement`) : []}>
                  <textarea
                    rows={2}
                    value={m.requirement}
                    onChange={(e) =>
                      set({
                        mustHaves: draft.mustHaves.map((x, j) => (j === i ? { ...x, requirement: e.target.value } : x)),
                      })
                    }
                  />
                </Field>
                <Suggest
                  label="Suggest requirement"
                  enabled={assistOn}
                  withHint
                  run={(hint) => api.assist.mustHave(draft, i, hint)}
                  render={(r) => <p className="suggestion">{r.requirement}</p>}
                  onAccept={(r) =>
                    set({ mustHaves: draft.mustHaves.map((x, j) => (j === i ? { ...x, requirement: r.requirement } : x)) })
                  }
                />
              </div>
            ))}
            <button
              className="button button-secondary button-small"
              onClick={() => set({ mustHaves: [...draft.mustHaves, { id: '', requirement: '' }] })}
            >
              Add must-have
            </button>
          </section>

          <section className="card">
            <h2>Skills</h2>
            <AssistNote status={assist.data} />
            <p className="muted small">
              Each becomes a scored question. Levels run from least to most, and each must describe a concrete
              situation on its own.
            </p>
            {showErrors && errorsAt('skills').map((m) => <p key={m} className="field-error">{m}</p>)}
            {draft.skills.map((s, i) => (
              <div className="item" key={i}>
                <div className="item-head">
                  <Field label="Id" errors={showErrors ? errorsAt(`skills.${i}.id`) : []}>
                    <input value={s.id} onChange={(e) => setSkill(i, { id: e.target.value })} />
                  </Field>
                  <Field label="Weight" errors={showErrors ? errorsAt(`skills.${i}.weight`) : []}>
                    <NumberInput value={s.weight} onValue={(weight) => setSkill(i, { weight })} />
                  </Field>
                  <span className="slider-note">{s.weight >= HEAVY_WEIGHT ? 'heavy' : ''}</span>
                  <ItemButtons
                    label={`skill ${s.id || i + 1}`}
                    index={i}
                    count={draft.skills.length}
                    onMove={(to) => set({ skills: move(draft.skills, i, to) })}
                    onRemove={() => set({ skills: draft.skills.filter((_, j) => j !== i) })}
                  />
                </div>
                <Field label="Question" errors={showErrors ? errorsAt(`skills.${i}.question`) : []}>
                  <textarea rows={2} value={s.question} onChange={(e) => setSkill(i, { question: e.target.value })} />
                </Field>
                <Suggest
                  label="Suggest question"
                  enabled={assistOn}
                  withHint
                  run={(hint) => api.assist.skillQuestion(draft, i, hint)}
                  render={(r) => <p className="suggestion">{r.question}</p>}
                  onAccept={(r) => setSkill(i, { question: r.question })}
                />
                <fieldset className="levels-edit">
                  <legend>Levels, least to most ({s.levels.length})</legend>
                  {s.levels.map((level, k) => (
                    <div className="level-row" key={k}>
                      <span className="level-num">{k}</span>
                      <input
                        aria-label={`Level ${k} of ${s.id || `skill ${i + 1}`}`}
                        value={level}
                        onChange={(e) => setSkill(i, { levels: s.levels.map((l, n) => (n === k ? e.target.value : l)) })}
                      />
                      <ItemButtons
                        label={`level ${k}`}
                        index={k}
                        count={s.levels.length}
                        onMove={(to) => setSkill(i, { levels: move(s.levels, k, to) })}
                        onRemove={() => setSkill(i, { levels: s.levels.filter((_, n) => n !== k) })}
                      />
                    </div>
                  ))}
                  {showErrors && errorsAt(`skills.${i}.levels`).map((m) => <p key={m} className="field-error">{m}</p>)}
                  <button
                    className="button button-secondary button-small"
                    disabled={s.levels.length >= MAX_LEVELS}
                    onClick={() => setSkill(i, { levels: [...s.levels, ''] })}
                  >
                    Add level
                  </button>
                  <SuggestLevels
                    enabled={assistOn && !!s.question.trim()}
                    run={(count) => api.assist.skillLevels(draft, i, count)}
                    onAccept={(levels) => setSkill(i, { levels })}
                  />
                </fieldset>
              </div>
            ))}
            <button
              className="button button-secondary button-small"
              onClick={() => set({ skills: [...draft.skills, { id: '', weight: 0.1, question: '', levels: ['', ''] }] })}
            >
              Add skill
            </button>
            <NewSkillFromDescription
              enabled={assistOn}
              run={(description, count) => api.assist.skill(draft, description, count)}
              onAccept={(skill) => set({ skills: [...draft.skills, skill] })}
            />
          </section>

          <section className="card">
            <h2>Thresholds</h2>
            <div className="threshold-grid">
              {(
                [
                  ['mustHavePass', 'Must-have pass', 'At or above: requirement met'],
                  ['mustHaveFail', 'Must-have fail', 'Below: requirement not shown; between: review'],
                  ['minConfidence', 'Min confidence', 'Below this on a heavy skill: review'],
                ] as const
              ).map(([key, label, hint]) => (
                <Field key={key} label={label} hint={hint}>
                  <NumberInput
                    value={draft.thresholds[key]}
                    onValue={(v) => set({ thresholds: { ...draft.thresholds, [key]: v } })}
                  />
                </Field>
              ))}
            </div>
            {showErrors && errorsAt('thresholds').map((m) => <p key={m} className="field-error">{m}</p>)}
          </section>
        </div>

        <aside className="editor-side">
          <section className="card">
            <h2>Summary</h2>
            <p className="small">
              <WeightTotal total={draft.skills.reduce((sum, s) => sum + (Number.isFinite(s.weight) ? s.weight : 0), 0)} />{' '}
              The composite uses each weight's share of the total.
            </p>
            <CostNote cost={cost} jobId={draft.id} resumeCount={resumeCount} />
            {showErrors && errors.length > 0 && (
              <p className="field-error" role="alert">
                {errors.length} problem{errors.length === 1 ? '' : 's'} to fix before saving.
              </p>
            )}
            {serverErrors.length > 0 && (
              <ul className="field-error">
                {serverErrors.map((m) => (
                  <li key={m}>{m}</li>
                ))}
              </ul>
            )}

            {conflict ? (
              <div className="confirm" role="alertdialog" aria-labelledby="conflict-text">
                <p id="conflict-text">
                  <strong>This spec changed on disk since you opened it.</strong> Saving would overwrite that edit.
                </p>
                {!loadDiskConfirm ? (
                  <div className="actions">
                    <button className="button" onClick={() => setLoadDiskConfirm(true)}>
                      Load the version on disk
                    </button>
                  </div>
                ) : (
                  <>
                    <p>This replaces the form with the file on disk. Your unsaved changes here are lost.</p>
                    <div className="actions">
                      <button className="button button-danger" onClick={() => loadFromDisk.mutate()}>
                        Load it
                      </button>
                      <button className="button button-secondary" onClick={() => setLoadDiskConfirm(false)}>
                        Cancel
                      </button>
                    </div>
                  </>
                )}
                <ErrorBox error={loadFromDisk.error} />
              </div>
            ) : (
              !serverErrors.length && <ErrorBox error={save.error} />
            )}

            {confirming && cost.kind === 'paid' ? (
              <div className="confirm" role="alertdialog" aria-labelledby="paid-save">
                <p id="paid-save">
                  <strong>This save changes what Jev is asked</strong> ({cost.changed.join(', ')}). The next screening
                  asks every resume again{resumeCount ? `, about ${resumeCount} calls for ${draft.id}_candidates` : ''}.
                </p>
                <div className="actions">
                  <button className="button" onClick={() => save.mutate()} disabled={save.isPending}>
                    {save.isPending ? 'Saving…' : 'Save anyway'}
                  </button>
                  <button className="button button-secondary" onClick={() => setConfirming(false)}>
                    Cancel
                  </button>
                </div>
              </div>
            ) : (
              <div className="actions">
                {!conflict && (
                  <button className="button" onClick={onSave} disabled={save.isPending || cost.kind === 'none'}>
                    {save.isPending ? 'Saving…' : isNew ? 'Create job' : 'Save'}
                  </button>
                )}
                <Link className="button button-secondary" to="/">
                  {cost.kind === 'none' ? 'Back to jobs' : 'Cancel'}
                </Link>
              </div>
            )}

            {lastSave && !save.isPending && !save.error && (
              <div className="notice" role="status">
                <p>Saved.</p>
                {lastSave.kind === 'paid' && saved && (
                  <>
                    <p className="small">
                      The answers cached for the old spec won't be used again. Keep them if you might undo this change.
                    </p>
                    <ClearCache jobId={saved.id} jobTitle={saved.title} />
                  </>
                )}
              </div>
            )}
            <p className="muted small">
              Saving rewrites the file in a standard layout. Comments you added by hand, other than the standard
              header, are not kept.
            </p>
          </section>

          <details className="card">
            <summary>YAML preview</summary>
            <pre className="yaml-preview">{specToYaml(draft)}</pre>
          </details>
        </aside>
      </div>
    </>
  )
}

/** Whether Claude suggestions are on, and what pressing a ✨ button does. */
function AssistNote({ status }: { status?: AssistStatus }) {
  if (!status) return null
  return status.enabled ? (
    <p className="muted small">
      ✨ buttons ask Claude ({status.model}) for a draft. Each press is one small paid call, and nothing changes until
      you accept.
    </p>
  ) : (
    <p className="muted small">Suggestions are off. Set ANTHROPIC_API_KEY to enable the ✨ buttons.</p>
  )
}

function LevelCount({ value, onChange }: { value: number; onChange: (count: number) => void }) {
  return (
    <label className="level-count">
      <span>Levels</span>
      <select value={value} onChange={(e) => onChange(Number(e.target.value))}>
        {Array.from({ length: 9 }, (_, n) => n + 2).map((n) => (
          <option key={n}>{n}</option>
        ))}
      </select>
    </label>
  )
}

function SuggestLevels({
  enabled,
  run,
  onAccept,
}: {
  enabled: boolean
  run: (count: number) => Promise<LevelsSuggestion>
  onAccept: (levels: string[]) => void
}) {
  const [count, setCount] = useState(5)
  return (
    <Suggest
      label="Suggest levels"
      enabled={enabled}
      controls={<LevelCount value={count} onChange={setCount} />}
      run={() => run(count)}
      render={(r) => (
        <ol className="suggestion" start={0}>
          {r.levels.map((l, k) => (
            <li key={k}>{l}</li>
          ))}
        </ol>
      )}
      onAccept={(r) => onAccept(r.levels)}
    />
  )
}

function NewSkillFromDescription({
  enabled,
  run,
  onAccept,
}: {
  enabled: boolean
  run: (description: string, count: number) => Promise<SkillSuggestion>
  onAccept: (skill: Skill) => void
}) {
  const [description, setDescription] = useState('')
  const [count, setCount] = useState(5)
  return (
    <div className="new-skill">
      <h3>New skill from a description</h3>
      <input
        aria-label="Describe the new skill"
        placeholder="e.g. experience running on-call for payment systems"
        value={description}
        onChange={(e) => setDescription(e.target.value)}
      />
      <Suggest
        label="Draft skill"
        enabled={enabled && !!description.trim()}
        controls={<LevelCount value={count} onChange={setCount} />}
        run={() => run(description, count)}
        render={(r) => (
          <div className="suggestion">
            <p>
              <code>{r.id}</code>, weight {r.weight}
            </p>
            <p>{r.question}</p>
            <ol start={0}>
              {r.levels.map((l, k) => (
                <li key={k}>{l}</li>
              ))}
            </ol>
          </div>
        )}
        onAccept={(r) => {
          onAccept({ id: r.id, weight: r.weight, question: r.question, levels: r.levels })
          setDescription('')
        }}
      />
    </div>
  )
}

function CostNote({ cost, jobId, resumeCount }: { cost: ChangeCost; jobId: string; resumeCount?: number }) {
  switch (cost.kind) {
    case 'new':
      return <p className="small">New job: each resume you screen against it makes one call to Jev.</p>
    case 'none':
      return <p className="small muted">No changes yet.</p>
    case 'free':
      return (
        <p className="small cost-free">
          <strong>Free:</strong> only weights or thresholds changed. The next screening re-ranks from cached answers.
        </p>
      )
    case 'paid':
      return (
        <p className="small cost-paid">
          <strong>Paid:</strong> {cost.changed.join(', ')}. Every resume is asked again
          {resumeCount ? `: about ${resumeCount} calls to Jev for ${jobId}_candidates` : ''}.
        </p>
      )
  }
}

/**
 * A number field that keeps its own text while you type, so clearing it or typing "0." doesn't snap to a
 * number mid-edit. The form only gets a value when the text parses.
 */
function NumberInput({ value, onValue }: { value: number; onValue: (value: number) => void }) {
  const [text, setText] = useState(String(value))
  useEffect(() => {
    // Follow outside changes (a reload from disk), but not our own keystrokes.
    setText((t) => (Number(t) === value && t.trim() !== '' ? t : String(value)))
  }, [value])
  return (
    <input
      type="number"
      min={0}
      max={1}
      step={0.05}
      value={text}
      onChange={(e) => {
        setText(e.target.value)
        if (e.target.value.trim() !== '' && Number.isFinite(Number(e.target.value))) onValue(Number(e.target.value))
      }}
    />
  )
}

function Field({
  label,
  hint,
  errors = [],
  children,
}: {
  label: string
  hint?: string
  errors?: string[]
  children: ReactNode
}) {
  // The hint and errors sit outside the <label>, so the field's accessible name is just the label.
  return (
    <div className={`field${errors.length ? ' field-invalid' : ''}`}>
      <label>
        <span>{label}</span>
        {children}
      </label>
      {hint && <small className="muted">{hint}</small>}
      {errors.map((m) => (
        <small key={m} className="field-error">
          {m}
        </small>
      ))}
    </div>
  )
}

function ItemButtons({
  label,
  index,
  count,
  onMove,
  onRemove,
}: {
  label: string
  index: number
  count: number
  onMove: (to: number) => void
  onRemove: () => void
}) {
  return (
    <span className="item-buttons">
      <button className="icon-button" aria-label={`Move ${label} up`} disabled={index === 0} onClick={() => onMove(index - 1)}>
        ↑
      </button>
      <button
        className="icon-button"
        aria-label={`Move ${label} down`}
        disabled={index === count - 1}
        onClick={() => onMove(index + 1)}
      >
        ↓
      </button>
      <button className="icon-button" aria-label={`Remove ${label}`} onClick={onRemove}>
        ✕
      </button>
    </span>
  )
}

function move<T>(items: T[], from: number, to: number): T[] {
  const next = [...items]
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item)
  return next
}

