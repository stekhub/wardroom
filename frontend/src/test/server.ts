import { setupServer } from 'msw/node'

/**
 * The mocked backend for component tests (ADR 0008). A test adds the responses it needs with
 * `server.use(...)`; they are removed again after each test.
 */
export const server = setupServer()
