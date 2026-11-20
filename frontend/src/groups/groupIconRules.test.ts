import { expect, test } from 'vitest'
import { fnv1a, GROUP_COLOR_COUNT, groupIcon } from '@/groups/groupIconRules'
import { GROUP_ICON_SYMBOLS } from '@/groups/groupIconSymbols'
import {
  GENERIC_ICON,
  HIERARCHY_ICONS,
  ICON_KEYWORDS,
  IGNORED_SEGMENTS,
} from '@/groups/groupIconTable'

function symbolOf(name: string, description?: string) {
  return groupIcon(name, description).symbol
}

// The examples of the Group Icons document

test.each([
  ['comp.lang.java.programmer', undefined, 'coffee'],
  ['de.comp.lang.java', undefined, 'coffee'],
  ['sci.space.history', undefined, 'scroll'],
  ['rec.food.cooking', undefined, 'chef-hat'],
  ['alt.example.misc', 'Talk about guitars', 'guitar'],
  ['comp.unknown', undefined, 'cpu'],
  ['foo.bar', undefined, 'messages-square'],
])('%s described as %s gets the symbol %s', (name, description, symbol) => {
  expect(symbolOf(name, description)).toBe(symbol)
})

// Matching order

test('topic segments are tried from the last to the first', () => {
  expect(symbolOf('sci.space.history')).toBe('scroll')
  expect(symbolOf('sci.history.space')).toBe('rocket')
  expect(symbolOf('rec.music.guitar.unknown')).toBe('guitar')
})

test('a topic segment wins over the description, and the description over the hierarchy', () => {
  expect(symbolOf('rec.pets.unknown', 'All about guitars')).toBe('paw-print')
  expect(symbolOf('rec.unknown', 'All about guitars')).toBe('guitar')
  expect(symbolOf('rec.unknown', 'Nothing that matches')).toBe('party-popper')
  expect(symbolOf('unknown.unknown', 'Nothing that matches')).toBe(GENERIC_ICON)
})

test('the top-level hierarchy is not matched as a keyword', () => {
  // "music" is a keyword, but here it is the hierarchy, and the hierarchy table does not have it.
  expect(symbolOf('music.unknown')).toBe(GENERIC_ICON)
  expect(symbolOf('news.unknown')).toBe('newspaper')
})

test('a segment matches with one of its parts between hyphens and underscores', () => {
  expect(symbolOf('alt.fan-fiction')).toBe('star')
  expect(symbolOf('alt.old_books')).toBe('book-open')
  expect(symbolOf('comp.os.ms-windows')).toBe('app-window')
  expect(symbolOf('alt.guitarists')).toBe('sparkles')
})

test('keywords with other characters than letters match as segments', () => {
  expect(symbolOf('comp.lang.c++')).toBe('code-xml')
  expect(symbolOf('comp.unknown', 'About C# and more')).toBe('cpu')
  expect(symbolOf('comp.unknown', 'About C++ and more')).toBe('code-xml')
})

test('description words are tried in order and without regard to case', () => {
  expect(symbolOf('alt.unknown', 'GUITARS, then drums')).toBe('guitar')
  expect(symbolOf('alt.unknown', 'Drums, then guitars')).toBe('drum')
  expect(symbolOf('alt.unknown', 'Not guitarists')).toBe('sparkles')
})

test('keywords shorter than three characters do not match in the description', () => {
  expect(symbolOf('alt.unknown', 'What is on TV and in AI')).toBe('sparkles')
  expect(symbolOf('alt.tv')).toBe('tv')
  expect(symbolOf('alt.unknown', 'The sql of it')).toBe('database')
})

test('upper case in the name does not matter', () => {
  expect(groupIcon('Comp.Lang.Java', null)).toEqual(groupIcon('comp.lang.java', null))
})

// Country prefixes

test.each(['de', 'fr', 'uk', 'aus', 'can', 'japan'])(
  'the leading country hierarchy %s is dropped',
  (country) => {
    expect(groupIcon(`${country}.comp.lang.java`, null)).toEqual(groupIcon('comp.lang.java', null))
    expect(symbolOf(`${country}.comp.unknown`)).toBe('cpu')
  },
)

test('only the first segment can be a country, and only short or listed names are one', () => {
  // "comp" stays the hierarchy; "de" is a topic segment without a keyword.
  expect(symbolOf('comp.de')).toBe('cpu')
  // "usa" is neither two letters nor listed, so it is the hierarchy and "comp" a topic.
  expect(symbolOf('usa.comp')).toBe(GENERIC_ICON)
  expect(groupIcon('usa.comp.lang', null).colorIndex).toBe(fnv1a('usa.comp') % GROUP_COLOR_COUNT)
})

test('a name that is only a country keeps it as its hierarchy', () => {
  expect(groupIcon('de', null)).toEqual({ symbol: GENERIC_ICON, colorIndex: fnv1a('de') % 12 })
})

// Ignored segments

test.each(IGNORED_SEGMENTS)('the segment %s is skipped', (ignored) => {
  expect(symbolOf(`rec.music.${ignored}`)).toBe('eighth-notes')
  expect(symbolOf(`rec.${ignored}`)).toBe('party-popper')
})

test('an ignored segment is skipped although it is a keyword elsewhere', () => {
  // "misc" is a hierarchy with its own icon, and ignored as a topic.
  expect(symbolOf('misc.unknown')).toBe('shapes')
  expect(symbolOf('alt.misc')).toBe('sparkles')
})

// Color

test('the hash is 32-bit FNV-1a over the UTF-8 bytes', () => {
  expect(fnv1a('')).toBe(0x811c9dc5)
  expect(fnv1a('a')).toBe(0xe40c292c)
  expect(fnv1a('foobar')).toBe(0xbf9cf968)
  // Two bytes in UTF-8: 0xC3 0xBC.
  expect(fnv1a('ü')).toBe(fnv1a('ü'))
  expect(fnv1a('ü')).not.toBe(fnv1a('u'))
})

test('the color comes from the hierarchy and the first topic segment', () => {
  const expected = fnv1a('comp.lang') % GROUP_COLOR_COUNT

  expect(groupIcon('comp.lang.java', null).colorIndex).toBe(expected)
  expect(groupIcon('comp.lang.python.announce', 'Other description').colorIndex).toBe(expected)
  expect(groupIcon('de.comp.lang.java', null).colorIndex).toBe(expected)
  expect(groupIcon('comp', null).colorIndex).toBe(fnv1a('comp') % GROUP_COLOR_COUNT)
})

test('the colors are spread over the whole palette', () => {
  const used = new Set<number>()
  for (let index = 0; index < 200; index++) {
    used.add(groupIcon(`hierarchy${String(index)}.topic`, null).colorIndex)
  }

  expect([...used].sort((a, b) => a - b)).toEqual([...Array(GROUP_COLOR_COUNT).keys()])
})

// The tables

test('keywords are lowercase and unique across the table', () => {
  const keywords = ICON_KEYWORDS.flatMap(([, iconKeywords]) => iconKeywords)

  expect(keywords.filter((keyword) => keyword !== keyword.toLowerCase())).toEqual([])
  expect(keywords.filter((keyword, index) => keywords.indexOf(keyword) !== index)).toEqual([])
})

test('every icon of the tables is bundled, and nothing else', () => {
  const used = new Set<string>([
    GENERIC_ICON,
    ...Object.values(HIERARCHY_ICONS),
    ...ICON_KEYWORDS.map(([icon]) => icon),
  ])

  expect([...used].sort()).toEqual(Object.keys(GROUP_ICON_SYMBOLS).sort())
})
