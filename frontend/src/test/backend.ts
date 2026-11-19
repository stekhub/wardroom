import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'

/** Builds an API error as the backend sends it (Problem Details, RFC 9457). */
export function problem(status: number, detail: string, headers?: Record<string, string>) {
  return HttpResponse.json(
    { type: 'about:blank', title: 'Error', status, detail },
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  )
}

/** The backend of a fresh installation: no account exists, so setup is available. */
export function backendWithoutAccount() {
  server.use(
    http.get('/api/auth/me', () => problem(401, 'Log in to use this endpoint.')),
    http.get('/api/auth/setup', () => new HttpResponse(null, { status: 200 })),
  )
}

/** The backend with an account, asked by a browser that is not logged in. */
export function backendLoggedOut() {
  server.use(
    http.get('/api/auth/me', () => problem(401, 'Log in to use this endpoint.')),
    http.get('/api/auth/setup', () => problem(404, 'An account already exists.')),
  )
}

/** The backend with an account, asked by a browser that is logged in. */
export function backendLoggedIn(username = 'owner') {
  server.use(http.get('/api/auth/me', () => HttpResponse.json({ username })))
}
