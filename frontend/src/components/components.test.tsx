import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type Preview } from '../api'
import { ErrorBox } from './ErrorBox'
import { PreviewView } from './PreviewPanel'
import { StatusPill } from './StatusPill'

describe('StatusPill', () => {
  it.each(['meets', 'review', 'missing'] as const)('shows %s with its colour class', (status) => {
    render(<StatusPill status={status} />)
    expect(screen.getByText(status)).toHaveClass(`pill-${status}`)
  })
})

describe('ErrorBox', () => {
  it('explains a missing API key', () => {
    render(<ErrorBox error={new ApiError(503, 'TYPESAFE_API_KEY is not set')} />)
    expect(screen.getByRole('alert')).toHaveTextContent('No TypeSafe API key is set')
    expect(screen.getByRole('alert')).toHaveTextContent('TYPESAFE_API_KEY is not set')
  })

  it('explains a TypeSafe failure', () => {
    render(<ErrorBox error={new ApiError(502, 'upstream 500')} />)
    expect(screen.getByRole('alert')).toHaveTextContent('TypeSafe failed to answer')
  })

  it('renders nothing without an error', () => {
    const { container } = render(<ErrorBox error={null} />)
    expect(container).toBeEmptyDOMElement()
  })
})

const preview: Preview = {
  cached: true,
  request: {
    model: 'jev-latest',
    state: { job: { title: 'Senior Backend Engineer', summary: 'Payments.' }, resume: 'Candidate C\n[email] | [phone]' },
    questions: {
      must_python_professional: {
        type: 'noul',
        instructions: { requirement: 'Has used Python', question: 'Does `resume` show evidence?' },
        criteria: { true: 'yes text', false: 'no text' },
      },
      comp_python_depth: { type: 'score', instructions: 'How deep?', criteria: ['None', 'Some', 'Lots'] },
    },
  },
}

describe('PreviewView', () => {
  it('highlights redactions and shows one card per question', () => {
    render(<PreviewView preview={preview} />)
    expect(screen.getByText('[email]').tagName).toBe('MARK')
    expect(screen.getByText('[phone]').tagName).toBe('MARK')
    expect(screen.getByText('must_python_professional')).toBeInTheDocument()
    expect(screen.getByText('comp_python_depth')).toBeInTheDocument()
    expect(screen.getByText('Has used Python')).toBeInTheDocument()
    expect(screen.getAllByRole('listitem').map((li) => li.textContent)).toEqual(
      expect.arrayContaining(['None', 'Some', 'Lots']),
    )
  })

  it('says whether screening would be free', () => {
    const { rerender } = render(<PreviewView preview={preview} />)
    expect(screen.getByText(/Cached: screening this is free/)).toBeInTheDocument()
    rerender(<PreviewView preview={{ ...preview, cached: false }} />)
    expect(screen.getByText(/Not cached: screening calls Jev/)).toBeInTheDocument()
  })

  it('shows the exact request as JSON', async () => {
    render(<PreviewView preview={preview} />)
    await userEvent.click(screen.getByRole('tab', { name: 'Raw JSON' }))
    expect(screen.getByText(/"model": "jev-latest"/)).toBeInTheDocument()
  })
})
