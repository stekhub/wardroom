import { ChevronDown, ChevronRight } from 'lucide-react'
import { useRef, useState } from 'react'
import { ArticleContent } from '@/articles/ArticleContent'
import { AuthorIcon } from '@/articles/AuthorIcon'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import type { Thread, ThreadArticle } from '@/threads/threadState'
import { Time } from '@/threads/Time'
import { authorName } from '@/threads/times'

/** Indentation grows up to this level; deeper replies are shown at it (spec 008). */
export const MAX_INDENT = 6

interface Row {
  article: ThreadArticle
  /** How far the article is indented: its depth, capped. */
  indent: number
  /** The articles whose lines run past this one, outermost first: one per level of indentation. */
  lineOwners: ThreadArticle[]
  /** How many articles are below it in the thread. */
  replyCount: number
}

/** Lays the articles, which come in display order with their depth, out as rows. */
function rowsOf(articles: ThreadArticle[]): Row[] {
  const replyCounts = new Array<number>(articles.length).fill(0)
  const open: number[] = []
  const close = (until: number, depth: number) => {
    let last = open.at(-1)
    while (last !== undefined && (articles[last]?.depth ?? 0) >= depth) {
      replyCounts[last] = until - last - 1
      open.pop()
      last = open.at(-1)
    }
  }
  const path: ThreadArticle[] = []
  const rows: Row[] = []
  articles.forEach((article, index) => {
    close(index, article.depth)
    open.push(index)
    path.length = Math.min(path.length, article.depth)
    const indent = Math.min(article.depth, MAX_INDENT)
    rows.push({ article, indent, lineOwners: path.slice(0, indent), replyCount: 0 })
    path[article.depth] = article
  })
  close(articles.length, 0)
  rows.forEach((row, index) => {
    row.replyCount = replyCounts[index] ?? 0
  })
  return rows
}

interface ThreadArticlesProps {
  thread: Thread
  /** Called once for every unread article of this group whose text was displayed. */
  onArticleRead: (article: ThreadArticle) => void
  /** Called when a stub was fetched: the thread knows more about it now. */
  onStubFilled: () => void
}

/**
 * The articles of a thread, replies nested below what they answer (spec 008, "Thread page"). Each
 * level is indented and marked with a vertical line; a click on a line, or on the button of an
 * article, collapses the article and its replies to one line. What is collapsed is not remembered.
 */
export function ThreadArticles({ thread, onArticleRead, onStubFilled }: ThreadArticlesProps) {
  const [collapsedIds, setCollapsedIds] = useState<ReadonlySet<number>>(() => new Set())
  const reportedIds = useRef(new Set<number>())

  function toggle(articleId: number) {
    setCollapsedIds((collapsed) => {
      const changed = new Set(collapsed)
      if (!changed.delete(articleId)) {
        changed.add(articleId)
      }
      return changed
    })
  }

  function displayed(article: ThreadArticle) {
    // An article that is collapsed and opened again is displayed twice, but read once.
    if (reportedIds.current.has(article.id)) {
      return
    }
    reportedIds.current.add(article.id)
    if (article.isStub) {
      onStubFilled()
    } else if (!article.read) {
      onArticleRead(article)
    }
  }

  const visibleRows: Row[] = []
  let hiddenBelowDepth: number | null = null
  for (const row of rowsOf(thread.articles)) {
    if (hiddenBelowDepth !== null) {
      if (row.article.depth > hiddenBelowDepth) {
        continue
      }
      hiddenBelowDepth = null
    }
    if (collapsedIds.has(row.article.id)) {
      hiddenBelowDepth = row.article.depth
    }
    visibleRows.push(row)
  }

  return (
    <ol aria-label="Articles" className="flex flex-col">
      {visibleRows.map((row) => (
        <li key={row.article.id} className="flex" data-indent={row.indent}>
          {row.lineOwners.map((owner) => (
            // The button of the article does the same and is the one the keyboard reaches.
            <button
              key={owner.id}
              type="button"
              tabIndex={-1}
              aria-hidden="true"
              title="Collapse"
              className="group/line flex w-5 shrink-0 cursor-pointer justify-center"
              onClick={() => {
                toggle(owner.id)
              }}
            >
              <span className="h-full w-px bg-border transition-colors duration-150 group-hover/line:w-0.5 group-hover/line:bg-primary motion-reduce:transition-none" />
            </button>
          ))}
          <ArticleRow
            row={row}
            collapsed={collapsedIds.has(row.article.id)}
            onToggle={() => {
              toggle(row.article.id)
            }}
            onDisplayed={() => {
              displayed(row.article)
            }}
          />
        </li>
      ))}
    </ol>
  )
}

interface ArticleRowProps {
  row: Row
  collapsed: boolean
  onToggle: () => void
  onDisplayed: () => void
}

/** One article: who wrote it and when, and its text. Names come from the article and are text. */
function ArticleRow({ row, collapsed, onToggle, onDisplayed }: ArticleRowProps) {
  const { article, replyCount } = row
  const author = article.isStub ? 'Not available locally' : authorName(article.from)
  return (
    <article className="min-w-0 flex-1 py-3" aria-label={`Article by ${author}`}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
        <Button
          type="button"
          variant="ghost"
          size="icon-xs"
          aria-expanded={!collapsed}
          aria-label={collapsed ? 'Expand' : 'Collapse'}
          className="text-muted-foreground"
          onClick={onToggle}
        >
          {collapsed ? <ChevronRight /> : <ChevronDown />}
        </Button>
        {!collapsed && <AuthorIcon authorKey={article.authorKey ?? null} />}
        <span
          className={
            article.isStub
              ? 'text-muted-foreground italic'
              : 'max-w-72 truncate font-medium text-foreground'
          }
        >
          {author}
        </span>
        {collapsed ? (
          <span className="text-muted-foreground">
            {replyCount === 0
              ? 'hidden'
              : `${String(replyCount)} ${replyCount === 1 ? 'reply' : 'replies'} hidden`}
          </span>
        ) : (
          <>
            {!article.isStub && (
              <span className="text-muted-foreground">
                <Time millis={article.date} />
              </span>
            )}
            {!article.isStub && !article.read && <Badge>new</Badge>}
            {article.otherGroup !== null && article.otherGroup !== undefined && (
              <Badge variant="outline">Posted to {article.otherGroup}</Badge>
            )}
          </>
        )}
      </div>
      {!collapsed && (
        <div className="mt-2 animate-in pl-8 duration-150 fade-in motion-reduce:animate-none">
          <ArticleContent articleId={article.id} onRead={onDisplayed} />
        </div>
      )}
    </article>
  )
}
