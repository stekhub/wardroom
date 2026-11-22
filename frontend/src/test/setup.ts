import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { toast } from 'sonner'
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

// jsdom does not capture pointers; toasts and menus ask for it when they are pressed.
Element.prototype.setPointerCapture = () => undefined
Element.prototype.releasePointerCapture = () => undefined
Element.prototype.hasPointerCapture = () => false

// jsdom does not scroll; the application scrolls to the top when a view opens.
window.scrollTo = () => undefined

// A request no test has mocked is a mistake in the test, so it fails instead of reaching the network.
beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' })
})

afterEach(() => {
  server.resetHandlers()
  // Toasts are kept outside the page; one test must not see those of another.
  toast.dismiss()
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
