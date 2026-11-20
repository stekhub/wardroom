import { useMemo, useState } from 'react'
import {
  findLinks,
  leavingPath,
  parseArticleText,
  unwrapFlowed,
  type QuoteBlock,
  type QuoteLine,
} from '@/articles/textRules'
import { cn } from '@/lib/utils'

/** A quote with more lines than this is collapsed (spec 007). */
const QUOTE_LINES_SHOWN = 5

/**
 * The bar colors of the quote levels; deeper levels start again with the first. The class names
 * are written out so that Tailwind finds them.
 */
const QUOTE_BARS = ['border-sky-600', 'border-emerald-600', 'border-amber-600'] as const

interface ArticleTextProps {
  /** The plain text of the article. Untrusted. */
  body: string
  flowed: boolean
  delSp: boolean
  /** Shows the text in a monospace font, for tables and drawings made of characters. */
  fixedWidth?: boolean
}

/**
 * The text of an article (spec 007, "Display"): line breaks kept, quotes with a bar per level,
 * the signature dimmed, `http` and `https` addresses as links to the leaving page.
 *
 * The text is untrusted. It is only ever put into the page as text nodes.
 */
export function ArticleText({ body, flowed, delSp, fixedWidth = false }: ArticleTextProps) {
  const parsed = useMemo(
    () => parseArticleText(flowed ? unwrapFlowed(body, delSp) : body),
    [body, flowed, delSp],
  )
  return (
    <div
      className={cn(
        'text-sm leading-relaxed break-words whitespace-pre-wrap',
        fixedWidth && 'overflow-x-auto font-mono text-[0.8125rem] whitespace-pre',
      )}
      data-fixed-width={fixedWidth}
    >
      {parsed.blocks.map((block, index) =>
        block.kind === 'text' ? (
          <div key={index}>
            <LinkedText text={block.text} />
          </div>
        ) : (
          <Quote key={index} block={block} />
        ),
      )}
      {parsed.signature !== null && (
        <div className="text-muted-foreground" data-signature>
          <LinkedText text={parsed.signature} />
        </div>
      )}
    </div>
  )
}

function Quote({ block }: { block: QuoteBlock }) {
  const collapsible = block.lines.length > QUOTE_LINES_SHOWN
  const [expanded, setExpanded] = useState(false)
  if (collapsible && !expanded) {
    return (
      <button
        type="button"
        className="my-1 rounded-md border border-border px-2 py-0.5 font-sans text-xs text-muted-foreground hover:bg-muted"
        onClick={() => {
          setExpanded(true)
        }}
      >
        Show quoted text
      </button>
    )
  }
  return (
    <blockquote className="my-1 text-muted-foreground">
      {runsOfOneLevel(block.lines).map((run, index) => (
        <QuoteRun key={index} level={run.level} text={run.text} />
      ))}
      {collapsible && (
        <button
          type="button"
          className="my-1 block rounded-md border border-border px-2 py-0.5 font-sans text-xs hover:bg-muted"
          onClick={() => {
            setExpanded(false)
          }}
        >
          Hide quoted text
        </button>
      )}
    </blockquote>
  )
}

/** Lines of one quote level that follow each other are shown together, so a link can span them. */
function runsOfOneLevel(lines: QuoteLine[]): { level: number; text: string }[] {
  const runs: { level: number; text: string }[] = []
  for (const line of lines) {
    const last = runs.at(-1)
    if (last !== undefined && last.level === line.level) {
      last.text += `\n${line.text}`
    } else {
      runs.push({ level: line.level, text: line.text })
    }
  }
  return runs
}

/** One bar per quote level, the outermost on the left. */
function QuoteRun({ level, text }: { level: number; text: string }) {
  let content = <LinkedText text={text} />
  for (let bar = level; bar >= 1; bar--) {
    content = (
      <div
        className={cn('border-l-2 pl-2', QUOTE_BARS[(bar - 1) % QUOTE_BARS.length])}
        data-quote-level={bar}
      >
        {content}
      </div>
    )
  }
  return content
}

/**
 * A link does not open its target but the leaving page, in a new tab, so the article keeps its
 * reading position. The target gets no handle on this tab and no referrer.
 */
function LinkedText({ text }: { text: string }) {
  return (
    <>
      {findLinks(text).map((segment, index) =>
        segment.kind === 'text' ? (
          segment.text
        ) : (
          <a
            key={index}
            href={leavingPath(segment.url)}
            target="_blank"
            rel="noopener noreferrer"
            className="text-primary underline underline-offset-2 hover:no-underline"
          >
            {segment.text}
          </a>
        ),
      )}
    </>
  )
}
