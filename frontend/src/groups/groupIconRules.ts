import type { GroupIconName } from '@/groups/groupIconSymbols'
import {
  COUNTRY_HIERARCHIES,
  GENERIC_ICON,
  HIERARCHY_ICONS,
  ICON_KEYWORDS,
  IGNORED_SEGMENTS,
} from '@/groups/groupIconTable'

/** The number of circle colors; the palette is in `GroupIcon.tsx`. */
export const GROUP_COLOR_COUNT = 12

/** Description words shorter than this are too ambiguous to pick a symbol. */
const SHORTEST_DESCRIPTION_KEYWORD = 3

const ICON_BY_KEYWORD: ReadonlyMap<string, GroupIconName> = new Map(
  ICON_KEYWORDS.flatMap(([icon, keywords]) => keywords.map((keyword) => [keyword, icon] as const)),
)

export interface GroupIconChoice {
  /** The Lucide name of the symbol. */
  symbol: GroupIconName
  /** The position of the circle color in the palette, from 0. */
  colorIndex: number
}

/** 32-bit FNV-1a over the UTF-8 bytes of the text, as an unsigned number. */
export function fnv1a(text: string): number {
  let hash = 0x811c9dc5
  for (const byte of new TextEncoder().encode(text)) {
    hash ^= byte
    hash = Math.imul(hash, 0x01000193) >>> 0
  }
  return hash >>> 0
}

/**
 * Splits a group name into its top-level hierarchy and its topic segments. A leading country
 * hierarchy is dropped: two letters, or one of the listed names. A name that consists of the
 * country alone keeps it.
 */
function hierarchyAndTopics(name: string): { hierarchy: string; topics: string[] } {
  const segments = name
    .toLowerCase()
    .split('.')
    .filter((segment) => segment !== '')
  const first = segments[0] ?? ''
  const isCountry = /^[a-z]{2}$/.test(first) || COUNTRY_HIERARCHIES.includes(first)
  const remaining = isCountry && segments.length > 1 ? segments.slice(1) : segments
  return { hierarchy: remaining[0] ?? '', topics: remaining.slice(1) }
}

/** A segment matches as a whole, or with one of its parts between hyphens and underscores. */
function iconOfSegment(segment: string): GroupIconName | undefined {
  if (IGNORED_SEGMENTS.includes(segment)) {
    return undefined
  }
  const whole = ICON_BY_KEYWORD.get(segment)
  if (whole !== undefined) {
    return whole
  }
  for (const part of segment.split(/[-_]/)) {
    const icon = ICON_BY_KEYWORD.get(part)
    if (icon !== undefined) {
      return icon
    }
  }
  return undefined
}

function iconOfDescription(description: string): GroupIconName | undefined {
  const words = description.toLowerCase().match(/[\p{L}\p{N}+#]+/gu) ?? []
  for (const word of words) {
    if (word.length >= SHORTEST_DESCRIPTION_KEYWORD) {
      const icon = ICON_BY_KEYWORD.get(word)
      if (icon !== undefined) {
        return icon
      }
    }
  }
  return undefined
}

function symbolOf(hierarchy: string, topics: string[], description: string): GroupIconName {
  for (const topic of [...topics].reverse()) {
    const icon = iconOfSegment(topic)
    if (icon !== undefined) {
      return icon
    }
  }
  // Own properties only: a hierarchy named like a property of every object must not match.
  const hierarchyIcon = Object.hasOwn(HIERARCHY_ICONS, hierarchy)
    ? HIERARCHY_ICONS[hierarchy]
    : undefined
  return iconOfDescription(description) ?? hierarchyIcon ?? GENERIC_ICON
}

/**
 * The icon of a newsgroup, derived from its name and description (Group Icons). The same input
 * always gives the same icon, and related groups share a color.
 */
export function groupIcon(name: string, description: string | null | undefined): GroupIconChoice {
  const { hierarchy, topics } = hierarchyAndTopics(name)
  const colorInput = topics.length > 0 ? `${hierarchy}.${topics[0] ?? ''}` : hierarchy
  return {
    symbol: symbolOf(hierarchy, topics, description ?? ''),
    colorIndex: fnv1a(colorInput) % GROUP_COLOR_COUNT,
  }
}
