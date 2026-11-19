import assert from 'node:assert/strict'
import { test } from 'node:test'
import { api, ApiError } from './api.ts'

test('mutations send a fresh CSRF token and preserve the requested version', async (t) => {
  const mock = t.mock.method(globalThis, 'fetch', async (path: string, request: RequestInit) => {
    if (path.endsWith('/auth/csrf')) {
      return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'fixture-token' })
    }
    const headers = new Headers(request.headers)
    assert.equal(path, '/api/v1/organizations/3/members/9')
    assert.equal(request.method, 'PATCH')
    assert.equal(request.credentials, 'same-origin')
    assert.equal(headers.get('X-CSRF-TOKEN'), 'fixture-token')
    assert.equal(headers.get('If-Match'), '"4"')
    assert.equal(headers.get('Content-Type'), 'application/json')
    return new Response(null, { status: 204 })
  })

  const result = await api('/organizations/3/members/9', {
    method: 'PATCH',
    headers: { 'If-Match': '"4"' },
    body: JSON.stringify({ enabled: false, roles: ['MEMBER'] }),
  })
  assert.equal(result, undefined)
  assert.equal(mock.mock.callCount(), 2)
})

test('a read surfaces an API problem without requesting a CSRF token', async (t) => {
  const mock = t.mock.method(globalThis, 'fetch', async (path: string) => {
    assert.equal(path, '/api/v1/auth/me')
    return Response.json({ detail: 'Sign in to continue' }, { status: 401 })
  })
  await assert.rejects(api('/auth/me'), new ApiError(401, 'Sign in to continue'))
  assert.equal(mock.mock.callCount(), 1)
})
