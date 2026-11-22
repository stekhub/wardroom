const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** Times are shown relative for this long, then as a date (spec 008, "Thread card"). */
const RELATIVE_FOR = 7 * DAY

const DATE = new Intl.DateTimeFormat('en', { dateStyle: 'medium' })
const EXACT = new Intl.DateTimeFormat('en', { dateStyle: 'full', timeStyle: 'short' })

/**
 * A time as a card or an article shows it: "2 h ago" for 7 days, then the date. A time in the
 * future is shown as its date.
 *
 * @param millis the time in UTC milliseconds
 * @param now    the current time in UTC milliseconds
 */
export function relativeTime(millis: number, now: number): string {
  const age = now - millis
  if (age < 0 || age >= RELATIVE_FOR) {
    return DATE.format(millis)
  }
  if (age < MINUTE) {
    return 'just now'
  }
  if (age < HOUR) {
    return `${String(Math.floor(age / MINUTE))} min ago`
  }
  if (age < DAY) {
    return `${String(Math.floor(age / HOUR))} h ago`
  }
  return `${String(Math.floor(age / DAY))} d ago`
}

/** The exact time in the time zone of the browser, for the hover text. */
export function exactTime(millis: number): string {
  return EXACT.format(millis)
}

/**
 * The name of an author for display: the name part of `From`, or the whole text when it has no
 * name part. The text comes from an article and is shown as text only.
 */
export function authorName(from: string | null | undefined): string {
  const text = (from ?? '').trim()
  if (text === '') {
    return 'Unknown author'
  }
  // "Jane Doe <jane@example.org>"
  const beforeAddress = /^([^<]*)<[^<>]*>\s*$/.exec(text)
  if (beforeAddress !== null) {
    const name = unquoted(beforeAddress[1] ?? '')
    return name === '' ? text.replace(/[<>]/g, '').trim() : name
  }
  // "jane@example.org (Jane Doe)"
  const inParentheses = /^\S+@\S+\s*\(([^()]+)\)\s*$/.exec(text)
  if (inParentheses !== null) {
    return (inParentheses[1] ?? '').trim()
  }
  return text
}

function unquoted(name: string): string {
  return name
    .trim()
    .replace(/^"(.*)"$/, '$1')
    .trim()
}
