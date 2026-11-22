import { expect, test } from 'vitest'
import {
  headerValue,
  mostlyQuoted,
  quotedReply,
  repliesByEmailOnly,
  replySubject,
  signatureLines,
  targetGroups,
  textToQuote,
} from '@/posting/quoting'

test('the subject of a reply has one "Re: " before the subject without reply prefixes', () => {
  expect(replySubject('Records vs. Lombok')).toBe('Re: Records vs. Lombok')
  expect(replySubject('Re: Records vs. Lombok')).toBe('Re: Records vs. Lombok')
  expect(replySubject('AW: Re[2]: re^3:  Sv: Antw: Hello')).toBe('Re: Hello')
  expect(replySubject('Regarding this')).toBe('Re: Regarding this')
  expect(replySubject(null)).toBe('Re: ')
})

test('the quoted text has "> " before every line, after the attribution line', () => {
  expect(quotedReply('Jane Doe', '\nFirst line\n\nSecond line\n\n')).toBe(
    'Jane Doe wrote:\n> First line\n>\n> Second line\n\n',
  )
})

test('a line that is a quote already gets one more level', () => {
  expect(quotedReply('Jane', '> older\n>> oldest\nmine')).toBe(
    'Jane wrote:\n>> older\n>>> oldest\n> mine\n\n',
  )
})

test('the text to quote has flowed lines joined and no signature', () => {
  expect(textToQuote('A long \nline\nNext\n-- \nJane\nhttps://example.org', true, false)).toBe(
    'A long line\nNext',
  )
  expect(textToQuote('A long \nline\n-- \nJane', false, false)).toBe('A long \nline')
  expect(textToQuote('No signature here\n--\nnot a separator', false, false)).toBe(
    'No signature here\n--\nnot a separator',
  )
})

test('a header value is found without regard to case and with folded lines joined', () => {
  const headers = 'Subject: Hello\r\nnewsgroups: example.talk,\r\n example.other\r\nX-Other: 1\r\n'

  expect(headerValue(headers, 'Newsgroups')).toBe('example.talk, example.other')
  expect(headerValue(headers, 'Followup-To')).toBeNull()
})

test('a reply goes to the groups of Followup-To, otherwise to those of Newsgroups', () => {
  expect(
    targetGroups('Newsgroups: example.talk,example.other\nFollowup-To: example.followup', 'own'),
  ).toEqual(['example.followup'])
  expect(targetGroups('Newsgroups: example.talk , example.other,example.talk', 'own')).toEqual([
    'example.talk',
    'example.other',
  ])
})

test('entries that are no group names are dropped, and without any the own group is used', () => {
  expect(targetGroups('Newsgroups: example.talk,bad name,example..double', 'own')).toEqual([
    'example.talk',
  ])
  expect(targetGroups('Newsgroups: not valid!', 'example.own')).toEqual(['example.own'])
  expect(targetGroups('', 'example.own')).toEqual(['example.own'])
})

test('Followup-To: poster asks for replies by email', () => {
  expect(repliesByEmailOnly('Newsgroups: example.talk\nFollowup-To:  Poster ')).toBe(true)
  expect(repliesByEmailOnly('Newsgroups: example.talk\nFollowup-To: example.talk')).toBe(false)
  expect(repliesByEmailOnly('Newsgroups: example.talk')).toBe(false)
})

test('a text is mostly quoted when more of its lines are quotes than not', () => {
  expect(mostlyQuoted('Jane wrote:\n> one\n> two\n\n')).toBe(true)
  expect(mostlyQuoted('Jane wrote:\n> one\n\nMy answer\nhas more lines')).toBe(false)
  expect(mostlyQuoted('No quote at all')).toBe(false)
  expect(mostlyQuoted('')).toBe(false)
})

test('the lines of a signature are counted without empty lines at its end', () => {
  expect(signatureLines('')).toBe(0)
  expect(signatureLines('Jane\nhttps://example.org\n\n')).toBe(2)
  expect(signatureLines('1\r\n2\r\n3\r\n4\r\n5')).toBe(5)
})
