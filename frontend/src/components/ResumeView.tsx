import { useQuery } from '@tanstack/react-query'
import { api, baseName, resumeFileUrl, type ResumeContent, type Source } from '../api'
import { sourceKey } from '../format'
import { ErrorBox } from './ErrorBox'

/**
 * One screened resume as stored, contact details included. Folder resumes are read from the library;
 * uploaded ones are re-read from the File still held in memory for this run.
 */
export function ResumeView({ source, name }: { source: Source; name: string }) {
  const folderPath = source.kind === 'folder' ? source.path : null
  const upload = source.kind === 'upload' ? source.files.find((f) => baseName(f.name) === name) : undefined

  const files = useQuery({
    queryKey: ['folder', folderPath],
    queryFn: () => api.folder(folderPath!),
    enabled: folderPath !== null,
  })
  const fileName = files.data?.find((f) => f.name === name)?.fileName

  const resume = useQuery<ResumeContent>({
    queryKey: ['resume', sourceKey(source), name, 'original'],
    queryFn: async () =>
      folderPath !== null ? api.resume(folderPath, fileName!, false) : (await api.resumeTexts([upload!], false))[0],
    enabled: folderPath !== null ? fileName !== undefined : upload !== undefined,
  })

  if (folderPath !== null && files.isSuccess && fileName === undefined) {
    return <p className="muted">No file for {name} in {folderPath} any more.</p>
  }
  if (source.kind === 'upload' && !upload) {
    return <p className="muted">The uploaded file for {name} is no longer available.</p>
  }
  const error = files.error ?? resume.error
  if (error) return <ErrorBox error={error} />
  if (!resume.data) return <p className="muted">Loading resume…</p>

  return (
    <div className="resume-view">
      <p className="muted small">
        Shown with contact details. Jev receives this without them; see <em>What was sent to Jev</em>.
      </p>
      {folderPath !== null && resume.data.format === 'pdf' && (
        <p className="small">
          Text extracted from the PDF, as the screener reads it.{' '}
          <a href={resumeFileUrl(folderPath, resume.data.fileName)} target="_blank" rel="noreferrer">
            Open original file
          </a>
        </p>
      )}
      <pre className="resume-text">{resume.data.text}</pre>
    </div>
  )
}
