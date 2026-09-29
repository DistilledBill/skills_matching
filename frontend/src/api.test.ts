import { describe, expect, it } from 'vitest'
import { baseName, toApiError } from './api'

describe('toApiError', () => {
  it('uses the problem detail from the API', async () => {
    const response = new Response(JSON.stringify({ title: 'Bad Request', detail: "'..' is not a folder inside resumes" }), {
      status: 400,
    })
    const error = await toApiError(response)
    expect(error.status).toBe(400)
    expect(error.message).toBe("'..' is not a folder inside resumes")
  })

  it('falls back when the body is not JSON', async () => {
    const error = await toApiError(new Response('oops', { status: 500 }))
    expect(error.message).toBe('Request failed (HTTP 500)')
  })
})

describe('baseName', () => {
  it('drops the extension like the service does', () => {
    expect(baseName('candidate_a.txt')).toBe('candidate_a')
    expect(baseName('resume.v2.pdf')).toBe('resume.v2')
    expect(baseName('noext')).toBe('noext')
  })
})
