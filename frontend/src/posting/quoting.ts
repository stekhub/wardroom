/**
 * What a reply starts from (spec 009): its subject, the quoted text of the article it answers, and
 * the groups it goes to. Everything here works on text and returns text.
 */
import { parseArticleText, unwrapFlowed } from '@/articles/textRules'

/** The reply prefixes of spec 006, also with a counter such as `Re[2]:` or `Re^2:`. */
const REPLY_PREFIXES = /^(?:\s*(?:re|aw|sv|antw)(?:\[\d+\]|\^\d+)?\s*:)+\s*/i

/** A group name as the backend accepts it (spec 009, "Security considerations"). */
const GROUP_NAME = /^[A-Za-z0-9+_-]+(?:\.[A-Za-z0-9+_-]+)*$/

const POSTER = 'poster'

/** The subject of a reply: "Re: " and the subject of the parent without its reply prefixes. */
export function replySubject(parentSubject: string | null | undefined): string {
  return `Re: ${(parentSubject ?? '').replace(REPLY_PREFIXES, '').trim()}`
}

/**
 * The text of an article as it is read: the line wrapping of `format=flowed` undone, and without
 * the signature.
 */
export function textToQuote(body: string, flowed: boolean, delSp: boolean): string {
  const text = flowed ? unwrapFlowed(body, delSp) : body
  const separator = text.split('\n').indexOf('-- ')
  return separator < 0 ? text : text.split('\n').slice(0, separator).join('\n')
}

/**
 * The beginning of a reply: the attribution line and the text with `> ` before every line, then
 * an empty line to write below.
 *
 * @param authorName who wrote the quoted text
 * @param text       the text of the parent, or the part of it that was selected
 */
export function quotedReply(authorName: string, text: string): string {
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  while (lines.length > 0 && (lines.at(-1) ?? '').trim() === '') {
    lines.pop()
  }
  while (lines.length > 0 && (lines[0] ?? '').trim() === '') {
    lines.shift()
  }
  // A line that is a quote already gets one more level, without a space in between.
  const quoted = lines.map((line) => (line.startsWith('>') ? `>${line}` : `> ${line}`.trimEnd()))
  return `${authorName} wrote:\n${quoted.join('\n')}\n\n`
}

/**
 * The value of the first header of this name in a header block, with folded lines joined; `null`
 * when the block has no such header.
 */
export function headerValue(rawHeaders: string, name: string): string | null {
  const prefix = `${name.toLowerCase()}:`
  let value: string | null = null
  for (const line of rawHeaders.split(/\r\n|\r|\n/)) {
    const continuation = line.startsWith(' ') || line.startsWith('\t')
    if (value !== null) {
      if (!continuation) {
        break
      }
      value += ` ${line.trim()}`
    } else if (!continuation && line.toLowerCase().startsWith(prefix)) {
      value = line.slice(prefix.length).trim()
    }
  }
  return value === null ? null : value.trim()
}

function groupNames(value: string | null): string[] {
  const names: string[] = []
  for (const entry of (value ?? '').split(',')) {
    const name = entry.trim()
    if (GROUP_NAME.test(name) && name.toLowerCase() !== POSTER && !names.includes(name)) {
      names.push(name)
    }
  }
  return names
}

/**
 * The groups a reply is posted to: those of `Followup-To` of the parent, otherwise those of its
 * `Newsgroups`. The backend decides the same way when it builds the article; this is what the
 * editor shows.
 *
 * @param ownGroup the group the reply is written in, used when the parent names no valid group
 */
export function targetGroups(rawHeaders: string, ownGroup: string): string[] {
  const ofFollowupTo = groupNames(headerValue(rawHeaders, 'Followup-To'))
  const groups =
    ofFollowupTo.length > 0 ? ofFollowupTo : groupNames(headerValue(rawHeaders, 'Newsgroups'))
  return groups.length > 0 ? groups : [ownGroup]
}

/** Whether the author asked for replies by email, with `Followup-To: poster`. */
export function repliesByEmailOnly(rawHeaders: string): boolean {
  return (headerValue(rawHeaders, 'Followup-To') ?? '').trim().toLowerCase() === POSTER
}

/**
 * Whether most of the text is quoted: more lines with text are quotes than not. Some servers
 * reject such posts (spec 009, "Checks before sending").
 */
export function mostlyQuoted(text: string): boolean {
  const parsed = parseArticleText(text.replace(/\r\n?/g, '\n'))
  let quoted = 0
  let written = 0
  for (const block of parsed.blocks) {
    if (block.kind === 'quote') {
      quoted += block.lines.filter((line) => line.text.trim() !== '').length
    } else {
      written += block.text.split('\n').filter((line) => line.trim() !== '').length
    }
  }
  return quoted > 0 && quoted > written
}

/** How many lines a signature has. More than 4 are frowned upon on Usenet. */
export function signatureLines(signature: string): number {
  const text = signature.replace(/\r\n?/g, '\n').trimEnd()
  return text === '' ? 0 : text.split('\n').length
}
