import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { JobSpec } from '../api'
import backend from '../fixtures/senior_backend_engineer.report.json'
import { ResultsProvider } from '../results'
import { JobsPage } from './JobsPage'

const job = (backend as unknown as { job: JobSpec }).job

describe('job cards', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('names each must-have above its requirement', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{ ...job, version: 'v1' }]))))
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ResultsProvider>
          <MemoryRouter>
            <JobsPage />
          </MemoryRouter>
        </ResultsProvider>
      </QueryClientProvider>,
    )
    const title = await screen.findByText('Python professional')
    expect(title).toHaveClass('item-title')
    expect(title.closest('li')).toHaveTextContent(`Python professional${job.mustHaves[0].requirement}`)
    expect(screen.getByText('Backend services').closest('li')).toHaveTextContent(job.mustHaves[1].requirement)
  })
})
