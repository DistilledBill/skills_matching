import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { JobSpec } from '../api'
import backend from '../fixtures/senior_backend_engineer.report.json'
import { EditJobPage } from './EditJobPage'

const job = { ...(backend as unknown as { job: JobSpec }).job, version: 'v1' }

type Sent = { url: string; body: Record<string, unknown> }

/** Serves the editor's API; `assist` answers the suggestion calls. No real Claude call is ever made. */
function serve(enabled: boolean, assist: (url: string, body: Record<string, unknown>) => unknown = () => ({})) {
  const sent: Sent[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const ok = (data: unknown) => new Response(JSON.stringify(data), { status: 200 })
      if (url === '/api/jobs') return ok([job])
      if (url === '/api/resume-folders') return ok([])
      if (url === '/api/assist') return ok({ enabled, model: 'claude-sonnet-5' })
      if (url.startsWith('/api/assist/')) {
        const body = JSON.parse(String(init?.body))
        sent.push({ url, body })
        return ok(assist(url, body))
      }
      return new Response('{}', { status: 404 })
    }),
  )
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[`/jobs/${job.id}/edit`]}>
        <Routes>
          <Route path="jobs/:jobId/edit" element={<EditJobPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return sent
}

const levelInputs = (skillId: string) => screen.getAllByLabelText(new RegExp(`^Level \\d+ of ${skillId}$`))

describe('Claude suggestions in the editor', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('turns the buttons off, with a reason, when no key is set', async () => {
    serve(false)
    expect((await screen.findAllByText(/Suggestions are off. Set ANTHROPIC_API_KEY/)).length).toBe(3)
    for (const button of screen.getAllByRole('button', { name: /^✨/ })) expect(button).toBeDisabled()
  })

  it('suggests levels with the chosen count, and changes nothing until Accept', async () => {
    const user = userEvent.setup()
    const sent = serve(true, () => ({ levels: ['None', 'Some', 'A lot'], warnings: [] }))
    const before = (await screen.findAllByLabelText(/^Level \d+ of python_depth$/)).map((i) => (i as HTMLInputElement).value)

    const skill = screen.getAllByRole('group', { name: /Levels, least to most/ })[0]
    await user.selectOptions(within(skill).getByLabelText('Levels'), '3')
    await user.click(within(skill).getByRole('button', { name: '✨ Suggest levels' }))

    const box = await screen.findByRole('region', { name: 'Suggest levels: suggestion' })
    expect(await within(box).findByText('A lot')).toBeInTheDocument()
    expect(sent[0]).toMatchObject({ url: '/api/assist/skill-levels', body: { index: 0, count: 3 } })
    expect((sent[0].body.draft as JobSpec).targetLevel).toBe('Vice President')
    expect(levelInputs('python_depth').map((i) => (i as HTMLInputElement).value)).toEqual(before)

    await user.click(within(box).getByRole('button', { name: 'Accept' }))
    expect(levelInputs('python_depth').map((i) => (i as HTMLInputElement).value)).toEqual(['None', 'Some', 'A lot'])
    expect(screen.queryByRole('region', { name: 'Suggest levels: suggestion' })).toBeNull()
  })

  it('sends the hint on Try again and shows warnings; Discard leaves the question alone', async () => {
    const user = userEvent.setup()
    const sent = serve(true, (_url, body) => ({
      question: body.hint ? 'How much Kafka?' : 'How deep, based on `resume`?',
      warnings: body.hint ? ["The question doesn't name `resume`."] : [],
    }))
    const original = job.skills[1].question
    await user.click((await screen.findAllByRole('button', { name: '✨ Suggest question' }))[1])
    const box = await screen.findByRole('region', { name: 'Suggest question: suggestion' })
    expect(await within(box).findByText('How deep, based on `resume`?')).toBeInTheDocument()

    await user.type(within(box).getByLabelText('Hint for the next suggestion'), 'focus on Kafka')
    await user.click(within(box).getByRole('button', { name: 'Try again' }))
    expect(await within(box).findByText('How much Kafka?')).toBeInTheDocument()
    expect(within(box).getByText(/doesn't name `resume`/)).toBeInTheDocument()
    expect(sent[1].body).toMatchObject({ index: 1, hint: 'focus on Kafka' })

    await user.click(within(box).getByRole('button', { name: 'Discard' }))
    expect(screen.getByDisplayValue(original)).toBeInTheDocument()
  })

  it('drafts the job overview from a description, and changes the summary only on Accept', async () => {
    const user = userEvent.setup()
    const sent = serve(true, () => ({ summary: 'Leads the payments platform.\nCore Responsibilities', warnings: [] }))
    const summary = (await screen.findByLabelText('Summary')) as HTMLTextAreaElement
    const before = summary.value
    const button = screen.getByRole('button', { name: '✨ Draft job overview' })
    const panel = screen.getByRole('region', { name: /describe the job and let AI draft the summary/ })
    expect(within(panel).getByText(/Claude then improves the summary above/)).toBeInTheDocument()

    // With a summary there, Claude can improve it without a description; with none, it needs one.
    await waitFor(() => expect(button).toBeEnabled())
    await user.clear(summary)
    expect(button).toBeDisabled()
    await user.type(summary, before.slice(0, 20))
    await user.type(screen.getByLabelText('Describe the job in a few words'), 'MD of payments product')
    await user.click(button)

    const box = await screen.findByRole('region', { name: 'Draft job overview: suggestion' })
    await within(box).findByText(/Leads the payments platform/)
    expect(sent.at(-1)).toMatchObject({ url: '/api/assist/summary', body: { hint: 'MD of payments product' } })
    expect(summary.value).toBe(before.slice(0, 20))
    await user.click(within(box).getByRole('button', { name: 'Accept' }))

    expect(summary.value).toBe('Leads the payments platform.\nCore Responsibilities')
    expect(screen.getByRole('complementary')).toHaveTextContent('Paid: summary')
  })

  it('drafts a new skill from a description and adds it on Accept', async () => {
    const user = userEvent.setup()
    serve(true, () => ({ id: 'on_call', question: 'How much on-call, based on `resume`?', levels: ['None', 'Led it'], weight: 0.1, warnings: [] }))
    await user.type(await screen.findByLabelText('Describe the new skill'), 'on-call for payments')
    await user.click(screen.getByRole('button', { name: '✨ Draft skill' }))
    const box = await screen.findByRole('region', { name: 'Draft skill: suggestion' })
    await within(box).findByText('on_call')
    await user.click(within(box).getByRole('button', { name: 'Accept' }))

    expect(screen.getByDisplayValue('on_call')).toBeInTheDocument()
    expect(levelInputs('on_call')).toHaveLength(2)
    expect(screen.getByRole('complementary')).toHaveTextContent('Total 1.10 ▼ 0.10')
    expect(screen.getByRole('complementary')).toHaveTextContent('Paid: skill on_call added')
  })

  it('shows the error when Claude fails, without touching the form', async () => {
    const user = userEvent.setup()
    serve(true)
    vi.mocked(fetch).mockImplementation(async (url) =>
      String(url).startsWith('/api/assist/')
        ? new Response(JSON.stringify({ detail: "Claude's suggestion didn't pass the spec checks" }), { status: 502 })
        : String(url) === '/api/assist'
          ? new Response(JSON.stringify({ enabled: true, model: 'm' }))
          : new Response(JSON.stringify([job])),
    )
    await user.click((await screen.findAllByRole('button', { name: '✨ Suggest requirement' }))[0])
    expect(await screen.findByRole('alert')).toHaveTextContent("didn't pass the spec checks")
    expect(within(screen.getByRole('region', { name: 'Suggest requirement: suggestion' })).getByRole('button', { name: 'Accept' })).toBeDisabled()
  })
})
