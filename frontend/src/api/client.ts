import createClient, { type Middleware } from 'openapi-fetch'
import type { paths } from '@/api/schema'

const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'
const READ_ONLY_METHODS = ['GET', 'HEAD', 'OPTIONS']

/** Paths whose 401 is an expected answer and not a session that ended. */
const PATHS_WITH_EXPECTED_401 = ['/api/auth/me', '/api/auth/login']

let onSessionEnded: () => void = () => undefined

/**
 * Registers what happens when the backend answers 401 to a request that needs a session:
 * the session has ended, and the application shows the login page (spec 001).
 */
export function setSessionEndedHandler(handler: () => void) {
  onSessionEnded = handler
}

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`
  const entry = document.cookie.split('; ').find((cookie) => cookie.startsWith(prefix))
  return entry === undefined ? undefined : decodeURIComponent(entry.slice(prefix.length))
}

/**
 * The backend puts the CSRF token into a cookie this code can read. Every request that changes
 * state sends it back in a header (spec 001); another website cannot read the cookie.
 */
const csrfToken: Middleware = {
  onRequest({ request }) {
    if (!READ_ONLY_METHODS.includes(request.method)) {
      const token = readCookie(CSRF_COOKIE)
      if (token !== undefined) {
        request.headers.set(CSRF_HEADER, token)
      }
    }
    return request
  },
}

const sessionEnded: Middleware = {
  onResponse({ request, response }) {
    const path = new URL(request.url).pathname
    if (response.status === 401 && !PATHS_WITH_EXPECTED_401.includes(path)) {
      onSessionEnded()
    }
    return response
  },
}

/**
 * The API client. Its types are generated from the committed `openapi.json` (ADR 0027) into
 * `schema.d.ts` by `pnpm run generate`; API types are never written by hand.
 *
 * Frontend and API share one origin, so requests carry the session cookie without further setup.
 */
export const api = createClient<paths>({
  baseUrl: window.location.origin,
  // Looked up on every request and not once at start, so the tests can put their mocked backend in place.
  fetch: (request) => globalThis.fetch(request),
})
api.use(csrfToken, sessionEnded)
