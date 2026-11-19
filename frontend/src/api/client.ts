import createClient from 'openapi-fetch'
import type { paths } from '@/api/schema'

/**
 * The API client. Its types are generated from the committed `openapi.json` (ADR 0027) into
 * `schema.d.ts` by `pnpm run generate`; API types are never written by hand.
 *
 * Frontend and API share one origin, so requests carry the session cookie without further setup.
 */
export const api = createClient<paths>({ baseUrl: '/' })
