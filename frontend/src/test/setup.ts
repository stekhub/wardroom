import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from '@/test/server'

// jsdom has no media queries; the sidebar asks for one to tell a narrow window from a wide one.
window.matchMedia = (query: string) =>
  ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }) as unknown as MediaQueryList

// jsdom does not observe element sizes; the checkbox component asks for an observer.
window.ResizeObserver = class {
  observe() {
    // Nothing changes its size in a test.
  }
  unobserve() {
    // See observe.
  }
  disconnect() {
    // See observe.
  }
}

// A request no test has mocked is a mistake in the test, so it fails instead of reaching the network.
beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' })
})

afterEach(() => {
  server.resetHandlers()
  cleanup()
  // Every test starts at the start page and without cookies.
  window.history.replaceState(null, '', '/')
  for (const cookie of document.cookie.split('; ')) {
    const name = cookie.split('=')[0] ?? ''
    document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
  }
})

afterAll(() => {
  server.close()
})
