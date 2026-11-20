import { expect, test } from 'vitest'
import { findLinks, openableUrl, parseArticleText, unwrapFlowed } from '@/articles/textRules'

function links(text: string): string[] {
  return findLinks(text).flatMap((segment) => (segment.kind === 'link' ? [segment.url] : []))
}

// Quotes and signature (spec 007, "Display")

test('quoted lines are told apart from written lines, each with its level', () => {
  const parsed = parseArticleText(
    'Ada wrote:\n> first\n>> second\n> > also second\n>third\nMy answer',
  )

  expect(parsed.blocks).toEqual([
    { kind: 'text', text: 'Ada wrote:' },
    {
      kind: 'quote',
      lines: [
        { level: 1, text: 'first' },
        { level: 2, text: 'second' },
        { level: 2, text: 'also second' },
        { level: 1, text: 'third' },
      ],
    },
    { kind: 'text', text: 'My answer' },
  ])
  expect(parsed.signature).toBeNull()
})

test('the signature is everything from the separator line on', () => {
  const parsed = parseArticleText('Text\n-- \nAda\n> not a quote any more')

  expect(parsed.blocks).toEqual([{ kind: 'text', text: 'Text' }])
  expect(parsed.signature).toBe('-- \nAda\n> not a quote any more')
})

test('two dashes without the space are no signature separator', () => {
  expect(parseArticleText('Text\n--\nmore').signature).toBeNull()
})

// format=flowed (RFC 3676)

test('flowed lines are joined into paragraphs and fixed lines are kept', () => {
  const body = 'A paragraph that \nruns over \nthree lines.\n\nA fixed line.\nAnother one.'

  expect(unwrapFlowed(body, false)).toBe(
    'A paragraph that runs over three lines.\n\nA fixed line.\nAnother one.',
  )
})

test('flowed quotes are joined per quote level and stay quotes', () => {
  const body = '> a quote that \n> goes on\n>> deeper \n>> still\n> back \nnot a quote'

  expect(unwrapFlowed(body, false)).toBe(
    '> a quote that goes on\n>> deeper still\n> back \nnot a quote',
  )
})

test('the space that was put before a line is removed', () => {
  expect(unwrapFlowed(' >not a quote\n  indented\n From the start', false)).toBe(
    '>not a quote\n indented\nFrom the start',
  )
})

test('the signature separator is never joined with the next line', () => {
  expect(unwrapFlowed('Text \n-- \nAda', false)).toBe('Text \n-- \nAda')
})

test('with DelSp the space before the line break is not part of the text', () => {
  expect(unwrapFlowed('Donau \ndampf \nschiff', true)).toBe('Donaudampfschiff')
  expect(unwrapFlowed('Donau \ndampf \nschiff', false)).toBe('Donau dampf schiff')
})

// Links

test('only http and https addresses are links', () => {
  expect(links('See https://example.org/a and HTTP://example.org/b now')).toEqual([
    'https://example.org/a',
    'HTTP://example.org/b',
  ])
  expect(
    links('ftp://example.org/file mailto:ada@example.org javascript:alert(1) news:example.talk'),
  ).toEqual([])
  expect(links('www.example.org and example.org/path')).toEqual([])
})

test('the text around a link is kept exactly', () => {
  expect(findLinks('a https://example.org b')).toEqual([
    { kind: 'text', text: 'a ' },
    { kind: 'link', text: 'https://example.org', url: 'https://example.org' },
    { kind: 'text', text: ' b' },
  ])
})

test('an address ends at whitespace, angle brackets and double quotes', () => {
  expect(
    links('<https://example.org/a> "https://example.org/b" https://example.org/c\nnext'),
  ).toEqual(['https://example.org/a', 'https://example.org/b', 'https://example.org/c'])
})

test('punctuation at the end of an address does not belong to it', () => {
  expect(
    links('Read https://example.org/a. Or https://example.org/b, or https://example.org/c!'),
  ).toEqual(['https://example.org/a', 'https://example.org/b', 'https://example.org/c'])
  expect(links("Is it https://example.org/d?' Yes: https://example.org/e;")).toEqual([
    'https://example.org/d',
    'https://example.org/e',
  ])
  expect(links('https://example.org/f?q=1&r=2.')).toEqual(['https://example.org/f?q=1&r=2'])
})

test('a closing bracket belongs to the address only with its opening bracket', () => {
  expect(links('(see https://example.org/a)')).toEqual(['https://example.org/a'])
  expect(links('https://example.org/wiki/Usenet_(network)')).toEqual([
    'https://example.org/wiki/Usenet_(network)',
  ])
  expect(links('(https://example.org/wiki/Usenet_(network)).')).toEqual([
    'https://example.org/wiki/Usenet_(network)',
  ])
  expect(links('[https://example.org/b] {https://example.org/c}')).toEqual([
    'https://example.org/b',
    'https://example.org/c',
  ])
})

test('an address in angle brackets that is broken over lines is one address', () => {
  const text = 'Look: <https://example.org/a/very/\n  long/address?with=\nparameters> end'

  expect(findLinks(text)).toEqual([
    { kind: 'text', text: 'Look: <' },
    {
      kind: 'link',
      text: 'https://example.org/a/very/\n  long/address?with=\nparameters',
      url: 'https://example.org/a/very/long/address?with=parameters',
    },
    { kind: 'text', text: '> end' },
  ])
})

test('an address in angle brackets is not joined over more than five lines or over other words', () => {
  expect(links('<https://example.org/a\nb\nc\nd\ne\nf>')).toEqual(['https://example.org/a'])
  expect(links('<https://example.org/a\nsome words\nb>')).toEqual(['https://example.org/a'])
  expect(links('<https://example.org/a\nnever closed')).toEqual(['https://example.org/a'])
})

test('an address without angle brackets ends at the line end', () => {
  expect(links('https://example.org/a/very/\nlong/address')).toEqual([
    'https://example.org/a/very/',
  ])
})

test('text that only looks like an address is no link', () => {
  expect(links('http:// and https://. and http://?')).toEqual([])
})

test('only valid http and https addresses can be opened', () => {
  expect(openableUrl('https://example.org/a?b=c#d')?.hostname).toBe('example.org')
  expect(openableUrl('http://example.org')?.hostname).toBe('example.org')
  for (const address of [
    '',
    'example.org',
    'javascript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'file:///etc/passwd',
    'ftp://example.org',
    'https://',
    ' https://example.org',
    '//example.org',
  ]) {
    expect(openableUrl(address), address).toBeNull()
  }
})
