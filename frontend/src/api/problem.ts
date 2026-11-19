import type { components } from '@/api/schema'

type ProblemDetail = components['schemas']['ProblemDetail']

/**
 * The message of an API error (Problem Details, RFC 9457) for the owner.
 * Falls back to the given text when the error carries no usable message.
 */
export function problemMessage(problem: ProblemDetail | undefined, fallback: string): string {
  const detail = problem?.detail
  return detail !== undefined && detail !== '' ? detail : fallback
}

export const UNEXPECTED_ERROR = 'Something went wrong. Try again.'
