const DAY = /^(\d{4})-(\d{2})-(\d{2})$/

const LONG_DATE = new Intl.DateTimeFormat('en', { dateStyle: 'long' })

/**
 * The start of a day in the time zone of the browser, in milliseconds (spec 005, "Jump to date").
 *
 * @param day a day as `YYYY-MM-DD`, as a date field and the address of a date view have it
 * @returns `undefined` when the text is not a day that exists
 */
export function startOfDay(day: string): number | undefined {
  const parts = DAY.exec(day)
  if (parts === null) {
    return undefined
  }
  const year = Number(parts[1])
  const month = Number(parts[2])
  const dayOfMonth = Number(parts[3])
  const start = new Date(year, month - 1, dayOfMonth)
  // A day such as 2015-02-31 would silently become one in March.
  if (
    start.getFullYear() !== year ||
    start.getMonth() !== month - 1 ||
    start.getDate() !== dayOfMonth
  ) {
    return undefined
  }
  return start.getTime()
}

/** The day of a time in the time zone of the browser, as `YYYY-MM-DD`. */
export function dayOf(millis: number): string {
  const date = new Date(millis)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const dayOfMonth = String(date.getDate()).padStart(2, '0')
  return `${String(date.getFullYear()).padStart(4, '0')}-${month}-${dayOfMonth}`
}

/** A time as a date in words, for example "January 1, 2015". */
export function formatDay(millis: number): string {
  return LONG_DATE.format(millis)
}
