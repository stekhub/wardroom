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

type ThreadCard = components['schemas']['ThreadCard']
type ThreadPage = components['schemas']['ThreadPage']
type ThreadArticle = components['schemas']['ThreadArticle']

/** Builds a thread card as the backend returns it: one read article by Ada, without cached text. */
export function testThreadCard(
  rootId: number,
  subject: string,
  overrides: Partial<ThreadCard> = {},
): ThreadCard {
  return {
    rootId,
    cardArticleId: rootId,
    subject,
    from: 'Ada Lovelace <ada@example.org>',
    authorKey: 'ada@example.org',
    date: 1_763_450_000_000,
    excerpt: null,
    messageCount: 1,
    latestActivity: 1_763_450_000_000,
    unreadCount: 0,
    ...overrides,
  }
}

/** Builds a page of thread cards: the last one, of a group whose newest range is range 7. */
export function testThreadPage(
  items: ThreadCard[] = [],
  overrides: Partial<ThreadPage> = {},
): ThreadPage {
  return {
    items,
    nextCursor: null,
    rangeId: 7,
    lowWatermarkReached: false,
    highWatermarkReached: true,
    ...overrides,
  }
}

/** Builds an article of a thread as the backend returns it: read, by Ada, located in the group. */
export function testThreadArticle(
  id: number,
  parentId: number | null,
  depth: number,
  overrides: Partial<ThreadArticle> = {},
): ThreadArticle {
  return {
    id,
    parentId,
    depth,
    isStub: false,
    subject: 'Hello',
    from: 'Ada Lovelace <ada@example.org>',
    authorKey: 'ada@example.org',
    ignoredAuthor: false,
    date: 1_763_450_000_000,
    read: true,
    otherGroup: null,
    sent: false,
    ...overrides,
  }
}

/**
 * The backend with an account, asked by a browser that is logged in. Unless a test says
 * otherwise, a server is configured, so the application itself is shown, the group list of
 * the server is empty, no group is subscribed and a group has no threads.
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
    http.get('/api/ignore-rules', () => HttpResponse.json([])),
    http.get('/api/about', () => HttpResponse.json({ version: '0.9.0' })),
    http.get('/api/groups/:groupId/threads', () => HttpResponse.json(testThreadPage())),
  )
}

/** The backend right after the account was created: logged in, but no server exists yet. */
export function backendWithoutServer() {
  backendLoggedIn('owner', [])
}
