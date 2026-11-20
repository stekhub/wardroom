import { fnv1a } from '@/groups/groupIconRules'

/**
 * The two colors of an author (UI concept, "Author icons"), from the 32-bit FNV-1a hash of the
 * author key. The fixed lightness values keep the symbol readable on every circle.
 */
export function authorColors(authorKey: string): { circle: string; symbol: string } {
  const hash = fnv1a(authorKey)
  return {
    circle: `hsl(${String(hash % 360)} 55% 42%)`,
    symbol: `hsl(${String((hash >>> 16) % 360)} 70% 90%)`,
  }
}
