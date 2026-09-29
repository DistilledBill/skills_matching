import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { JobSpec, ScreeningReport } from '../api'
import backend from '../fixtures/senior_backend_engineer.report.json'
import { Results } from './ResultsPage'

const { job, report } = backend as unknown as { job: JobSpec; report: ScreeningReport }

function renderResults() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <Results job={job} run={{ report, source: { kind: 'folder', path: 'x' }, ranAt: new Date() }} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const banner = () => screen.queryByText(/this ranking isn't saved/)

const names = () =>
  within(screen.getByRole('table'))
    .getAllByRole('row')
    .slice(1)
    .map((row) => row.querySelector('strong')!.textContent)

describe('what-if tuning on the results page', () => {
  it('re-ranks when a weight moves, shows rank arrows, and resets to the spec ranking', () => {
    renderResults()
    const specOrder = report.candidates.map((c) => c.name)
    expect(names()).toEqual(specOrder)
    expect(banner()).toBeNull()

    // Make domain relevance dominate: the ranking within each status changes.
    fireEvent.change(screen.getByRole('slider', { name: /Domain relevance/ }), { target: { value: '1' } })

    expect(banner()).toBeInTheDocument()
    expect(names()).not.toEqual(specOrder)
    expect(document.querySelectorAll('.rank-change').length).toBeGreaterThan(0)

    fireEvent.click(screen.getByRole('button', { name: 'Reset to spec values' }))
    expect(names()).toEqual(specOrder)
    expect(document.querySelectorAll('.rank-change')).toHaveLength(0)
    expect(banner()).toBeNull()
  })

  it('moving a slider back to the spec value clears the what-if state', () => {
    renderResults()
    const slider = screen.getByRole('slider', { name: /Min confidence/ })
    fireEvent.change(slider, { target: { value: '0.9' } })
    expect(banner()).toBeInTheDocument()
    fireEvent.change(slider, { target: { value: String(job.thresholds.minConfidence) } })
    expect(banner()).toBeNull()
  })

  it('keeps must-have fail at or below pass', () => {
    renderResults()
    fireEvent.change(screen.getByRole('slider', { name: /Must-have fail/ }), { target: { value: '0.9' } })
    expect(screen.getByRole('slider', { name: /Must-have pass/ })).toHaveValue('0.9')
  })
})
