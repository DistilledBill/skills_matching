import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'
import type { JobSpec, ScreeningReport } from '../api'
import backend from '../fixtures/senior_backend_engineer.report.json'
import { Results } from './ResultsPage'

const { job, report } = backend as unknown as { job: JobSpec; report: ScreeningReport }

/** Stands in for the editor: shows the router state it was opened with. */
function EditorStub() {
  return <pre data-testid="editor-state">{JSON.stringify(useLocation().state)}</pre>
}

function renderResults() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <Routes>
          <Route
            path="/"
            element={<Results job={job} run={{ report, source: { kind: 'folder', path: 'x' }, ranAt: new Date() }} />}
          />
          <Route path="/jobs/:jobId/edit" element={<EditorStub />} />
        </Routes>
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

  it('Save to spec opens the editor with the what-if weights and thresholds', () => {
    renderResults()
    expect(screen.getByRole('button', { name: 'Save to spec…' })).toBeDisabled()
    fireEvent.change(screen.getByRole('slider', { name: /Domain relevance/ }), { target: { value: '0.3' } })
    fireEvent.change(screen.getByRole('slider', { name: /Min confidence/ }), { target: { value: '0.6' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save to spec…' }))

    const state = JSON.parse(screen.getByTestId('editor-state').textContent!)
    expect(state.whatIf.weights.domain_relevance).toBe(0.3)
    expect(state.whatIf.weights.python_depth).toBe(0.3)
    expect(state.whatIf.thresholds.minConfidence).toBe(0.6)
  })
})

describe('must-have columns on the results page', () => {
  it('gives each must-have its own named, sortable column', () => {
    renderResults()
    const headers = screen.getAllByRole('columnheader')
    const python = headers.find((h) => h.textContent?.startsWith('Python professional'))!
    const backendServices = headers.find((h) => h.textContent?.startsWith('Backend services'))!
    expect(python).toHaveTextContent('probability met')
    expect(within(backendServices).getByRole('button')).toHaveAttribute('title', job.mustHaves[1].requirement)
    expect(headers.some((h) => h.textContent === 'Must-haves')).toBe(false)

    // Highest first, then lowest first on a second click.
    fireEvent.click(within(backendServices).getByRole('button'))
    fireEvent.click(within(backendServices).getByRole('button'))
    expect(names().slice(0, 3)).toEqual(['candidate_h', 'candidate_i', 'candidate_b'])
    const firstRow = within(screen.getByRole('table')).getAllByRole('row')[1]
    expect(firstRow).toHaveTextContent('0.07')
  })

  it('names each must-have in the candidate panel', () => {
    renderResults()
    fireEvent.click(screen.getByText('candidate_c'))
    const title = screen.getAllByText('Python professional').find((e) => e.classList.contains('item-title'))!
    expect(title.closest('li')).toHaveTextContent(job.mustHaves[0].requirement)
  })
})

describe('candidate panel docked beside the table', () => {
  afterEach(() => localStorage.clear())

  const splitter = () => screen.queryByRole('separator', { name: 'Resize the candidate panel' })
  const width = () => Number(splitter()!.getAttribute('aria-valuenow'))
  const docked = () => document.querySelector('.split-open')

  it('opens beside the table, resizes from the keyboard, and resets on double-click', () => {
    renderResults()
    expect(splitter()).toBeNull()
    fireEvent.click(screen.getByText('candidate_c'))

    expect(docked()).toContainElement(screen.getByRole('table'))
    expect(docked()).toContainElement(screen.getByRole('complementary', { name: 'Details for candidate_c' }))
    expect(splitter()).toHaveAttribute('aria-orientation', 'vertical')
    const start = width()
    fireEvent.keyDown(splitter()!, { key: 'ArrowLeft' })
    expect(width()).toBe(start + 24)
    expect(localStorage.getItem('results.panelWidth')).toBe(String(start + 24))
    fireEvent.keyDown(splitter()!, { key: 'ArrowRight' })
    fireEvent.keyDown(splitter()!, { key: 'ArrowRight' })
    expect(width()).toBe(start - 24)
    fireEvent.doubleClick(splitter()!)
    expect(width()).toBe(start)
    expect(localStorage.getItem('results.panelWidth')).toBeNull()
  })

  it('opens at the width last chosen, and Close or Escape gives the table its full width back', () => {
    localStorage.setItem('results.panelWidth', '600')
    renderResults()
    fireEvent.click(screen.getByText('candidate_c'))
    expect(width()).toBe(600)

    fireEvent.click(screen.getByRole('button', { name: 'Close details' }))
    expect(splitter()).toBeNull()
    expect(docked()).toBeNull()

    fireEvent.click(screen.getByText('candidate_a'))
    expect(docked()).not.toBeNull()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(docked()).toBeNull()
  })
})

