import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'
import { ErrorBox } from './ErrorBox'

/**
 * Deletes every cached answer for one job, after an inline confirmation. Only this job's answers go;
 * other jobs keep theirs.
 */
export function ClearCache({ jobId, jobTitle }: { jobId: string; jobTitle: string }) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const count = useQuery({ queryKey: ['job-cache', jobId], queryFn: () => api.jobCache(jobId) })
  const n = count.data?.count ?? 0

  const clear = useMutation({
    mutationFn: () => api.clearJobCache(jobId),
    onSuccess: () => {
      setConfirming(false)
      // Everything that shows "cached" for this job is now out of date.
      for (const key of ['job-cache', 'cache-status', 'preview']) {
        void queryClient.invalidateQueries({ queryKey: [key, jobId] })
      }
    },
  })

  const answers = `${n} cached answer${n === 1 ? '' : 's'}`
  return (
    <div className="clear-cache">
      {!confirming ? (
        <button
          className="button button-secondary button-small"
          disabled={n === 0 || count.isPending}
          onClick={() => setConfirming(true)}
        >
          Clear cache for this job ({n})
        </button>
      ) : (
        <div className="confirm" role="alertdialog" aria-labelledby="clear-cache-question">
          <p id="clear-cache-question">
            <strong>
              Delete {answers} for {jobTitle}?
            </strong>{' '}
            Every answer for this job is removed, from any folder or upload. The next screening will call Jev for
            each resume and cost money.
          </p>
          <div className="actions">
            <button className="button button-danger" onClick={() => clear.mutate()} disabled={clear.isPending}>
              {clear.isPending ? 'Deleting…' : 'Delete'}
            </button>
            <button className="button button-secondary" onClick={() => setConfirming(false)} disabled={clear.isPending}>
              Cancel
            </button>
          </div>
        </div>
      )}
      {clear.isSuccess && !confirming && (
        <p className="muted small" role="status">
          Deleted {clear.data.removed} cached answer{clear.data.removed === 1 ? '' : 's'}.
        </p>
      )}
      <ErrorBox error={count.error ?? clear.error} />
    </div>
  )
}
