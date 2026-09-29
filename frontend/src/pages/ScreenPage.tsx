import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type DragEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, baseName, type Source } from '../api'
import { ErrorBox } from '../components/ErrorBox'
import { PreviewPanel } from '../components/PreviewPanel'
import { sourceKey } from '../format'
import { useResults } from '../results'

const ACCEPTED = ['.txt', '.md', '.pdf']

export function ScreenPage() {
  const { jobId = '' } = useParams()
  const navigate = useNavigate()
  const results = useResults()

  const jobs = useQuery({ queryKey: ['jobs'], queryFn: api.jobs })
  const folders = useQuery({ queryKey: ['folders'], queryFn: api.folders })
  const job = jobs.data?.find((j) => j.id === jobId)

  const [mode, setMode] = useState<'folder' | 'upload'>('folder')
  const [folderPath, setFolderPath] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [previewName, setPreviewName] = useState('')
  const [showPreview, setShowPreview] = useState(false)

  // Default to this job's own folder, then the first one.
  useEffect(() => {
    if (folderPath || !folders.data?.length) return
    const own = folders.data.find((f) => f.path === `${jobId}_candidates`)
    setFolderPath((own ?? folders.data[0]).path)
  }, [folders.data, folderPath, jobId])

  const source: Source | null = useMemo(() => {
    if (mode === 'folder') return folderPath ? { kind: 'folder', path: folderPath } : null
    return files.length ? { kind: 'upload', files } : null
  }, [mode, folderPath, files])

  const folderFiles = useQuery({
    queryKey: ['folder', folderPath],
    queryFn: () => api.folder(folderPath),
    enabled: mode === 'folder' && !!folderPath,
  })
  const names = mode === 'folder' ? (folderFiles.data?.map((f) => f.name) ?? []) : files.map((f) => baseName(f.name))

  useEffect(() => {
    if (!names.includes(previewName)) setPreviewName(names[0] ?? '')
  }, [names, previewName])

  const cache = useQuery({
    queryKey: ['cache-status', jobId, source && sourceKey(source)],
    queryFn: () => api.cacheStatus(jobId, source!),
    enabled: !!source && !!job,
  })

  const run = useMutation({
    mutationFn: (s: Source) => api.screen(jobId, s),
    onSuccess: (report, s) => {
      results.set(jobId, { report, source: s, ranAt: new Date() })
      navigate(`/jobs/${jobId}/results`)
    },
  })

  function addFiles(list: FileList | null) {
    if (!list) return
    const picked = Array.from(list).filter((f) => ACCEPTED.some((ext) => f.name.toLowerCase().endsWith(ext)))
    setFiles((prev) => [...prev.filter((p) => !picked.some((f) => f.name === p.name)), ...picked])
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    addFiles(e.dataTransfer.files)
  }

  if (jobs.isError) return <ErrorBox error={jobs.error} />
  if (jobs.isSuccess && !job) return <ErrorBox error={new Error(`No job spec with id '${jobId}'`)} />

  const uncached = cache.data ? cache.data.total - cache.data.cached : 0

  return (
    <>
      <p className="crumbs">
        <Link to="/">Jobs</Link> / {job?.title ?? jobId}
      </p>
      <h1>Screen resumes</h1>
      <p className="lede">Against {job ? <strong>{job.title}</strong> : '…'}.</p>

      <section className="card">
        <div className="segmented" role="radiogroup" aria-label="Resume source">
          <button role="radio" aria-checked={mode === 'folder'} onClick={() => setMode('folder')}>
            From a folder
          </button>
          <button role="radio" aria-checked={mode === 'upload'} onClick={() => setMode('upload')}>
            Upload files
          </button>
        </div>

        {mode === 'folder' ? (
          <label className="field">
            <span>Folder under resumes/</span>
            <select value={folderPath} onChange={(e) => setFolderPath(e.target.value)}>
              {folders.data?.map((f) => (
                <option key={f.path} value={f.path}>
                  {f.path} ({f.fileCount})
                </option>
              ))}
            </select>
          </label>
        ) : (
          <div className="dropzone" onDragOver={(e) => e.preventDefault()} onDrop={onDrop}>
            <p>Drop .txt, .md or .pdf resumes here, or</p>
            <label className="button button-secondary">
              Choose files
              <input type="file" multiple accept={ACCEPTED.join(',')} hidden onChange={(e) => addFiles(e.target.files)} />
            </label>
            {files.length > 0 && (
              <ul className="file-list">
                {files.map((f) => (
                  <li key={f.name}>
                    {f.name}{' '}
                    <button className="link" onClick={() => setFiles(files.filter((x) => x !== f))}>
                      remove
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {cache.data && (
          <p className="muted" aria-live="polite">
            {cache.data.total} resume{cache.data.total === 1 ? '' : 's'}: {cache.data.cached} already cached
            {uncached > 0 ? `, ${uncached} will call Jev.` : ', so this run makes no API calls.'}
          </p>
        )}
        <ErrorBox error={cache.error ?? folderFiles.error} />

        <div className="actions">
          <button className="button" disabled={!source || run.isPending} onClick={() => source && run.mutate(source)}>
            {run.isPending ? 'Screening…' : 'Run screening'}
          </button>
          <button className="button button-secondary" disabled={!source} onClick={() => setShowPreview((v) => !v)}>
            {showPreview ? 'Hide preview' : 'Preview what Jev sees'}
          </button>
        </div>
        <ErrorBox error={run.error} />
      </section>

      {showPreview && source && (
        <section className="card">
          <label className="field">
            <span>Resume to preview</span>
            <select value={previewName} onChange={(e) => setPreviewName(e.target.value)}>
              {names.map((n) => (
                <option key={n}>{n}</option>
              ))}
            </select>
          </label>
          {previewName && <PreviewPanel jobId={jobId} target={{ source, name: previewName }} />}
        </section>
      )}
    </>
  )
}
