import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ClearCache } from './ClearCache'
import { WeightTotal } from './WhatIfPanel'

describe('WeightTotal', () => {
  it('is plain at exactly 1, including floating-point sums', () => {
    const { container } = render(<WeightTotal total={0.3 + 0.25 + 0.15 + 0.2 + 0.1} />)
    expect(container.firstChild).toHaveTextContent('Total 1.00.')
    expect(container.firstChild).not.toHaveClass('weight-total-off')
    expect(container).not.toHaveTextContent(/[▲▼]/)
  })

  it('is red with ▲ and the shortfall below 1', () => {
    const { container } = render(<WeightTotal total={0.8} />)
    expect(container.firstChild).toHaveClass('weight-total-off')
    expect(container.firstChild).toHaveTextContent('Total 0.80 ▲ 0.20 below 1.')
  })

  it('is red with ▼ and the excess above 1', () => {
    const { container } = render(<WeightTotal total={1.9} />)
    expect(container.firstChild).toHaveClass('weight-total-off')
    expect(container.firstChild).toHaveTextContent('Total 1.90 ▼ 0.90 above 1.')
  })
})

describe('ClearCache', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('deletes only after the confirmation, and not on cancel', async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) =>
      new Response(JSON.stringify(init?.method === 'DELETE' ? { removed: 3 } : { count: 3 }), { status: 200 }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const deletes = () => fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE').length
    const user = userEvent.setup()
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ClearCache jobId="senior_backend_engineer" jobTitle="Senior Backend Engineer" />
      </QueryClientProvider>,
    )

    await user.click(await screen.findByRole('button', { name: 'Clear cache for this job (3)' }))
    expect(screen.getByRole('alertdialog')).toHaveTextContent(
      'Delete 3 cached answers for Senior Backend Engineer? Every answer for this job is removed',
    )
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(deletes()).toBe(0)

    await user.click(screen.getByRole('button', { name: /Clear cache for this job/ }))
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('Deleted 3 cached answers.')).toBeInTheDocument()
    expect(deletes()).toBe(1)
    expect(fetchMock).toHaveBeenCalledWith('/api/screenings/senior_backend_engineer/cache', { method: 'DELETE' })
  })

  it('is disabled when nothing is cached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ count: 0 }), { status: 200 })))
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ClearCache jobId="j" jobTitle="J" />
      </QueryClientProvider>,
    )
    expect(await screen.findByRole('button', { name: 'Clear cache for this job (0)' })).toBeDisabled()
  })
})
