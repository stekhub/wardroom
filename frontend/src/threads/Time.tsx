import { useState } from 'react'
import { exactTime, relativeTime } from '@/threads/times'

/** The latest time a browser can show; beyond it, formatting fails. */
const MAX_TIME = 8.64e15

/**
 * A time, relative for 7 days and then as a date; the exact time is shown on hover (spec 008).
 * Without a time it says so.
 */
export function Time({ millis }: { millis: number | null | undefined }) {
  // The time the view was opened; a shown time does not change while it is read.
  const [now] = useState(() => Date.now())
  // A date comes from an article and can be one that no calendar has.
  if (millis === null || millis === undefined || Math.abs(millis) > MAX_TIME) {
    return <span>date unknown</span>
  }
  return (
    <time dateTime={new Date(millis).toISOString()} title={exactTime(millis)}>
      {relativeTime(millis, now)}
    </time>
  )
}
