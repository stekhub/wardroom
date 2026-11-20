/**
 * Reads the plain text of an article: quote levels, the signature, links and `format=flowed`
 * (spec 007). Everything here works on text and returns text; nothing is ever turned into HTML.
 */

/** A run of lines that the author wrote. */
export interface TextBlock {
  kind: 'text'
  text: string
}

/** A run of quoted lines, each with the level of its quote. */
export interface QuoteBlock {
  kind: 'quote'
  lines: QuoteLine[]
}

export interface QuoteLine {
  /** How many `>` the line starts with; at least 1. */
  level: number
  text: string
}

export interface ParsedArticleText {
  blocks: (TextBlock | QuoteBlock)[]
  /** The signature with its separator line, or `null` when the article has none. */
  signature: string | null
}

/** A piece of a text: plain text, or an address that can be opened. */
export type TextSegment =
  { kind: 'text'; text: string } | { kind: 'link'; text: string; url: string }

const SIGNATURE_SEPARATOR = '-- '

/** The `>` characters at the start of a quoted line, each with at most one space after it. */
const QUOTE_PREFIX = /^(?:> ?)+/

function quoteLevel(line: string): { level: number; text: string } {
  const prefix = QUOTE_PREFIX.exec(line)?.[0] ?? ''
  return { level: prefix.split('>').length - 1, text: line.slice(prefix.length) }
}

/**
 * Splits the text into what the author wrote, quotes and the signature. The signature is
 * everything from the first line that consists of `-- ` on.
 */
export function parseArticleText(body: string): ParsedArticleText {
  const lines = body.split('\n')
  const separator = lines.indexOf(SIGNATURE_SEPARATOR)
  const textLines = separator < 0 ? lines : lines.slice(0, separator)
  const signature = separator < 0 ? null : lines.slice(separator).join('\n')

  const blocks: (TextBlock | QuoteBlock)[] = []
  let written: string[] = []
  let quoted: QuoteLine[] = []
  const endWritten = () => {
    if (written.length > 0) {
      blocks.push({ kind: 'text', text: written.join('\n') })
      written = []
    }
  }
  const endQuoted = () => {
    if (quoted.length > 0) {
      blocks.push({ kind: 'quote', lines: quoted })
      quoted = []
    }
  }
  for (const line of textLines) {
    if (line.startsWith('>')) {
      endWritten()
      quoted.push(quoteLevel(line))
    } else {
      endQuoted()
      written.push(line)
    }
  }
  endWritten()
  endQuoted()
  return { blocks, signature }
}

/**
 * Undoes the line wrapping of `format=flowed` text (RFC 3676). A line that ends with a space
 * continues on the next line of the same quote level; the signature separator never does. With
 * `delSp`, the space before the line break is not part of the text.
 */
export function unwrapFlowed(body: string, delSp: boolean): string {
  const paragraphs: string[] = []
  // The paragraph that the next line may continue.
  const open = { active: false, level: 0, text: '' }
  const close = () => {
    if (open.active) {
      paragraphs.push(withQuotePrefix(open.level, open.text))
      open.active = false
      open.text = ''
    }
  }
  for (const line of body.split('\n')) {
    const prefix = /^>*/.exec(line)?.[0] ?? ''
    const level = prefix.length
    // A space was put before a line that would otherwise start with a space, ">" or "From ".
    let text = line.slice(level)
    if (text.startsWith(' ')) {
      text = text.slice(1)
    }
    if (open.active && open.level !== level) {
      close()
    }
    if (text === SIGNATURE_SEPARATOR) {
      close()
      paragraphs.push(withQuotePrefix(level, text))
      continue
    }
    const flowed = text.endsWith(' ')
    const part = flowed && delSp ? text.slice(0, -1) : text
    open.active = true
    open.level = level
    open.text += part
    if (!flowed) {
      close()
    }
  }
  close()
  return paragraphs.join('\n')
}

function withQuotePrefix(level: number, text: string): string {
  return level === 0 ? text : `${'>'.repeat(level)} ${text}`
}

/**
 * Whether the address can be opened: a valid `http` or `https` address with a host. Used for
 * links in articles and again by the leaving page, which trusts nothing in its own address.
 */
export function openableUrl(address: string): URL | null {
  if (!/^https?:\/\//i.test(address)) {
    return null
  }
  try {
    const url = new URL(address)
    const isWeb = url.protocol === 'http:' || url.protocol === 'https:'
    return isWeb && url.hostname !== '' ? url : null
  } catch {
    return null
  }
}

const LINK_START = /https?:\/\//gi

/** An address in angle brackets may be broken over this many lines. */
const MAX_LINES_IN_BRACKETS = 5

const TRAILING_PUNCTUATION = '.,;:!?\'"'
const BRACKETS: Record<string, string> = { ')': '(', ']': '[', '}': '{' }

/**
 * Finds the `http` and `https` addresses in a text (spec 007, "Display"):
 *
 * - An address runs to the next whitespace, `<`, `>` or double quote.
 * - Punctuation at its end does not belong to it; neither does a closing bracket without an
 *   opening one inside the address.
 * - An address in angle brackets that is broken over up to five lines is one address.
 */
export function findLinks(text: string): TextSegment[] {
  const segments: TextSegment[] = []
  let plainStart = 0
  const pushPlain = (end: number) => {
    if (end > plainStart) {
      segments.push({ kind: 'text', text: text.slice(plainStart, end) })
    }
  }
  LINK_START.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = LINK_START.exec(text)) !== null) {
    const start = match.index
    const link =
      (start > 0 && text[start - 1] === '<' ? bracketedLink(text, start) : null) ??
      bareLink(text, start)
    if (link === null) {
      continue
    }
    pushPlain(start)
    segments.push({ kind: 'link', text: text.slice(start, link.end), url: link.url })
    plainStart = link.end
    LINK_START.lastIndex = link.end
  }
  pushPlain(text.length)
  return segments
}

/** The address between `<` and `>`, when it is broken over lines and nothing else is in between. */
function bracketedLink(text: string, start: number): { url: string; end: number } | null {
  const close = text.indexOf('>', start)
  if (close < 0) {
    return null
  }
  const inner = text.slice(start, close)
  if (!inner.includes('\n') || /[<"]/.test(inner)) {
    return null
  }
  const parts = inner.split('\n').map((part) => part.trim())
  if (
    parts.length > MAX_LINES_IN_BRACKETS ||
    parts.some((part) => part === '' || /\s/.test(part))
  ) {
    return null
  }
  const url = parts.join('')
  return openableUrl(url) === null ? null : { url, end: close }
}

function bareLink(text: string, start: number): { url: string; end: number } | null {
  let end = start
  while (end < text.length && !/[\s<>"]/.test(text[end] ?? '')) {
    end++
  }
  let url = text.slice(start, end)
  for (;;) {
    const last = url.at(-1) ?? ''
    const opening = BRACKETS[last]
    if (TRAILING_PUNCTUATION.includes(last) && last !== '') {
      url = url.slice(0, -1)
    } else if (opening !== undefined && count(url, opening) < count(url, last)) {
      url = url.slice(0, -1)
    } else {
      break
    }
  }
  return openableUrl(url) === null ? null : { url, end: start + url.length }
}

function count(text: string, character: string): number {
  return text.split(character).length - 1
}

/** The address of the leaving page for a link in an article. */
export function leavingPath(url: string): string {
  return `/leaving?url=${encodeURIComponent(url)}`
}
