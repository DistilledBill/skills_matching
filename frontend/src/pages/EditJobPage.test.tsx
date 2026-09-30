import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { JobSpec } from '../api'
import backend from '../fixtures/senior_backend_engineer.report.json'
import { EditJobPage } from './EditJobPage'

const job = { ...(backend as unknown as { job: JobSpec }).job, version: 'v1' }

type Call = { url: string; method: string; body?: unknown }

function serve(put: (body: { version: string; spec: JobSpec }) => Response, state?: unknown) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      calls.push({ url, method, body })
      const ok = (data: unknown) => new Response(JSON.stringify(data), { status: 200 })
      if (url === '/api/jobs') return ok([job])
      if (url === '/api/resume-folders') return ok([{ path: `${job.id}_candidates`, fileCount: 10 }])
      if (method === 'PUT') return put(body)
      if (url.endsWith('/reload')) return ok({ ...job, title: 'Edited on disk', version: 'v3' })
      if (url.endsWith('/cache')) return ok({ count: 10 })
      return new Response('{}', { status: 404 })
    }),
  )
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[{ pathname: `/jobs/${job.id}/edit`, state }]}>
        <Routes>
          <Route path="jobs/:jobId/edit" element={<EditJobPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return calls
}

const side = () => within(screen.getByRole('complementary'))

describe('EditJobPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('saves a free change straight away, sending the version it loaded', async () => {
    const user = userEvent.setup()
    const calls = serve((body) => new Response(JSON.stringify({ ...body.spec, version: 'v2' }), { status: 200 }))
    const weight = (await screen.findAllByLabelText('Weight'))[0]
    await user.clear(weight)
    await user.type(weight, '0.5')

    expect(side().getByText(/only weights or thresholds changed/)).toBeInTheDocument()
    await user.click(side().getByRole('button', { name: 'Save' }))

    expect(await side().findByText('Saved.')).toBeInTheDocument()
    const put = calls.find((c) => c.method === 'PUT')!
    expect(put.url).toBe(`/api/jobs/${job.id}`)
    expect(put.body).toMatchObject({ version: 'v1', spec: { skills: expect.arrayContaining([expect.objectContaining({ id: 'python_depth', weight: 0.5 })]) } })
  })

  it('asks before a paid save and then offers to clear the old answers', async () => {
    const user = userEvent.setup()
    serve((body) => new Response(JSON.stringify({ ...body.spec, version: 'v2' }), { status: 200 }))
    await user.type(await screen.findByLabelText('Target level'), ' (hybrid)')

    expect(side().getByText('Paid:').closest('p')).toHaveTextContent('Paid: target level. Every resume is asked again: about 10 calls to Jev')
    await user.click(side().getByRole('button', { name: 'Save' }))
    expect(side().getByRole('alertdialog')).toHaveTextContent('This save changes what Jev is asked (target level)')
    await user.click(side().getByRole('button', { name: 'Save anyway' }))

    expect(await side().findByText('Saved.')).toBeInTheDocument()
    expect(await side().findByRole('button', { name: 'Clear cache for this job (10)' })).toBeInTheDocument()
  })

  it('shows every problem and sends nothing while the spec is invalid', async () => {
    const user = userEvent.setup()
    const calls = serve(() => new Response('{}', { status: 500 }))
    await user.clear(await screen.findByLabelText('Title'))
    await user.click(side().getByRole('button', { name: 'Save' }))
    expect(screen.getAllByText('title is required').length).toBeGreaterThan(0)
    expect(side().getByRole('alert')).toHaveTextContent('1 problem to fix before saving.')
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)
  })

  it('on a conflict, offers to load the version on disk after a confirmation', async () => {
    const user = userEvent.setup()
    serve(
      () =>
        new Response(JSON.stringify({ detail: 'changed on disk since it was loaded' }), {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    )
    const weight = (await screen.findAllByLabelText('Weight'))[0]
    await user.clear(weight)
    await user.type(weight, '0.4')
    await user.click(side().getByRole('button', { name: 'Save' }))

    expect(await side().findByText('This spec changed on disk since you opened it.')).toBeInTheDocument()
    expect(side().queryByRole('button', { name: 'Save' })).toBeNull()
    await user.click(side().getByRole('button', { name: 'Load the version on disk' }))
    await user.click(side().getByRole('button', { name: 'Load it' }))
    expect(await screen.findByDisplayValue('Edited on disk')).toBeInTheDocument()
  })

  it('fills in weights and thresholds passed from what-if tuning', async () => {
    const weights = Object.fromEntries(job.skills.map((s) => [s.id, s.weight]))
    serve(() => new Response('{}'), {
      whatIf: { weights: { ...weights, python_depth: 0.45 }, thresholds: { ...job.thresholds, minConfidence: 0.6 } },
    })
    expect((await screen.findAllByLabelText('Weight'))[0]).toHaveValue(0.45)
    expect(screen.getByLabelText('Min confidence')).toHaveValue(0.6)
    expect(screen.getByText(/from what-if tuning are filled in/)).toBeInTheDocument()
    expect(side().getByText(/only weights or thresholds changed/)).toBeInTheDocument()
  })
})
