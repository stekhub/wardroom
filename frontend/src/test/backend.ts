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

type Group = components['schemas']['Group']
type GroupListRefreshStatus = components['schemas']['GroupListRefreshStatus']
type Subscription = components['schemas']['Subscription']

/** The refresh status of a group list that was fetched and is not being fetched now. */
export const IDLE_REFRESH: GroupListRefreshStatus = {
  running: false,
  groupsReceived: 0,
  refreshedAt: 1_795_000_000_000,
}

/** Builds a group as the backend returns it. */
export function testGroup(id: number, name: string, overrides: Partial<Group> = {}): Group {
  return { id, name, postingStatus: 'ALLOWED', subscribed: false, ...overrides }
}

/** Builds a subscribed group as the backend returns it: nothing unread, never synchronized. */
export function testSubscription(
  groupId: number,
  name: string,
  overrides: Partial<Subscription> = {},
): Subscription {
  return {
    groupId,
    serverId: TEST_SERVER.id,
    name,
    postingStatus: 'ALLOWED',
    removed: false,
    unreadCount: 0,
    sync: { running: false },
    ...overrides,
  }
}

/**
 * The backend with an account, asked by a browser that is logged in. Unless a test says
 * otherwise, a server is configured, so the application itself is shown, the group list of
 * the server is empty and no group is subscribed.
 */
export function backendLoggedIn(username = 'owner', servers: Server[] = [TEST_SERVER]) {
  server.use(
    http.get('/api/auth/me', () => HttpResponse.json({ username })),
    http.get('/api/servers', () => HttpResponse.json(servers)),
    http.get('/api/servers/:serverId/groups', () =>
      HttpResponse.json({ items: [], page: 0, size: 50, total: 0 }),
    ),
    http.get('/api/servers/:serverId/groups/refresh', () => HttpResponse.json(IDLE_REFRESH)),
    http.get('/api/subscriptions', () => HttpResponse.json([])),
  )
}

/** The backend right after the account was created: logged in, but no server exists yet. */
export function backendWithoutServer() {
  backendLoggedIn('owner', [])
}
