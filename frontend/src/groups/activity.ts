const COUNT = new Intl.NumberFormat('en')

/**
 * The activity of a group as the discover view shows it (spec 003): "240 per day". The value is
 * approximate, so it is shown as a whole number.
 */
export function formatActivity(articlesPerDay: number): string {
  if (articlesPerDay <= 0) {
    return '0 per day'
  }
  if (articlesPerDay < 1) {
    return 'under 1 per day'
  }
  return `${COUNT.format(Math.round(articlesPerDay))} per day`
}
