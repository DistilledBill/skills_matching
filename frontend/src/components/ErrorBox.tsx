import { ApiError } from '../api'

/** Explains an API failure in plain words, with the server's own detail underneath. */
export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null
  const status = error instanceof ApiError ? error.status : undefined
  const detail = error instanceof Error ? error.message : String(error)
  return (
    <div className="error-box" role="alert">
      <strong>{headline(status)}</strong>
      <p>{detail}</p>
    </div>
  )
}

function headline(status: number | undefined): string {
  switch (status) {
    case 503:
      return 'No TypeSafe API key is set, so screening is unavailable. Previews still work.'
    case 502:
      return 'TypeSafe failed to answer. Try again in a moment.'
    case 404:
      return 'Not found.'
    case 400:
      return 'The request was rejected.'
    case 0:
      return 'The screening service is not reachable.'
    default:
      return 'Something went wrong.'
  }
}
