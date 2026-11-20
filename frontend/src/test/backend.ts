import { http, HttpResponse } from 'msw'
import type { components } from '@/api/schema'
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

type Server = components['schemas']['Server']

/** A server as the backend returns it: with a login whose password is stored. */
export const TEST_SERVER: Server = {
  id: 1,
  name: 'My provider',
  host: 'news.example.com',
  port: 563,
  tlsMode: 'IMPLICIT',
  authMethod: 'USER_PASS',
  username: 'reader',
  passwordStored: true,
  maxConnections: 4,
  credentialsInvalid: false,
}

/**
 * The backend with an account, asked by a browser that is logged in. Unless a test says
 * otherwise, a server is configured, so the application itself is shown.
 */
export function backendLoggedIn(username = 'owner', servers: Server[] = [TEST_SERVER]) {
  server.use(
    http.get('/api/auth/me', () => HttpResponse.json({ username })),
    http.get('/api/servers', () => HttpResponse.json(servers)),
  )
}

/** The backend right after the account was created: logged in, but no server exists yet. */
export function backendWithoutServer() {
  backendLoggedIn('owner', [])
}
