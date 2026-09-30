import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type Preview } from '../api'
import { Bar } from './Bar'
import { ErrorBox } from './ErrorBox'
import { PreviewView } from './PreviewPanel'
import { StatusPill } from './StatusPill'
import { WeightStack } from './WeightStack'

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
    state: {
      job: { title: 'Senior Backend Engineer', target_level: 'Vice President', summary: 'Payments.' },
      resume: 'Candidate C\n[email] | [phone]',
    },
    questions: {
      must_python_professional: {
        type: 'noul',
        instructions: { requirement: 'Has used Python', question: 'Does `resume` show evidence?' },
        criteria: { true: 'yes text', false: 'no text' },
      },
      skill_python_depth: { type: 'score', instructions: 'How deep?', criteria: ['None', 'Some', 'Lots'] },
    },
  },
}

describe('PreviewView', () => {
  it('highlights redactions and shows one card per question', () => {
    render(<PreviewView preview={preview} />)
    expect(screen.getByText('[email]').tagName).toBe('MARK')
    expect(screen.getByText('[phone]').tagName).toBe('MARK')
    expect(screen.getByText('must_python_professional')).toBeInTheDocument()
    expect(screen.getByText('skill_python_depth')).toBeInTheDocument()
    expect(screen.getByText('Has used Python')).toBeInTheDocument()
    expect(screen.getByText('Vice President')).toBeInTheDocument()
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

describe('Bar', () => {
  it('puts the score inside the bar and the confidence beside it, with no tooltip', () => {
    const { container } = render(<Bar value={0.75} confidence={0.9} />)
    expect(container.querySelector('.bar-track')).toHaveTextContent('0.75')
    expect(container.querySelector('.bar-fill')).toHaveStyle({ width: '75%' })
    expect(container.querySelector('.bar-conf')).toHaveTextContent('0.90')
    expect(container.querySelector('.bar-conf')).not.toHaveClass('bar-conf-low')
    expect(container.querySelector('[title]')).toBeNull()
  })

  it('highlights low confidence', () => {
    const { container } = render(<Bar value={0.5} confidence={0.3} lowConfidence />)
    expect(container.querySelector('.bar-conf')).toHaveClass('bar-conf-low')
    expect(container.querySelector('.bar-conf')).toHaveTextContent('0.30 (low confidence)')
  })

  it('shows no confidence when none is given', () => {
    const { container } = render(<Bar value={0.906} />)
    expect(container.querySelector('.bar-track')).toHaveTextContent('0.91')
    expect(container.querySelector('.bar-conf')).toBeNull()
  })
})

describe('WeightStack', () => {
  it('sizes segments by share of the total weight, even when weights do not sum to 1', () => {
    render(
      <WeightStack
        skills={[
          { id: 'python_depth', weight: 2 },
          { id: 'api_design', weight: 1 },
        ]}
      />,
    )
    const stack = screen.getByRole('img')
    expect(stack).toHaveAccessibleName('Weights: Python depth 67%, Api design 33%')
    const heights = [...stack.querySelectorAll<HTMLElement>('.weight-seg')].map((el) => parseFloat(el.style.height))
    expect(heights[0]).toBeCloseTo(66.667, 2)
    expect(heights[1]).toBeCloseTo(33.333, 2)
    expect(heights[0] + heights[1]).toBeCloseTo(100, 6)
  })
})
