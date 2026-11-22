import { ChevronDown, ChevronRight, Ellipsis, EyeOff, Reply } from 'lucide-react'
import { useRef, useState } from 'react'
import { ArticleContent } from '@/articles/ArticleContent'
import { useLoadedContent } from '@/articles/contentState'
import { AuthorIcon } from '@/articles/AuthorIcon'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { ArticleEditor } from '@/posting/ArticleEditor'
import type { PostedArticle } from '@/posting/postingState'
import {
  quotedReply,
  repliesByEmailOnly,
  replySubject,
  targetGroups,
  textToQuote,
} from '@/posting/quoting'
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

/** What replying needs to know about the group of the page (spec 009). */
export interface ReplyContext {
  groupId: number
  /** Comes from the server and is shown as text only. */
  groupName: string
  /** Whether the group takes posts; without it, no article has a "Reply". */
  allowed: boolean
  moderated: boolean
  /** Called after a reply was sent. */
  onPosted: (posted: PostedArticle) => void
}

interface ThreadArticlesProps {
  thread: Thread
  replies: ReplyContext
  /** Called once for every unread article of this group whose text was displayed. */
  onArticleRead: (article: ThreadArticle) => void
  /** Called when a stub was fetched: the thread knows more about it now. */
  onStubFilled: () => void
  /** Called when the owner chose "Ignore author" in the menu of an article. */
  onIgnoreAuthor: (article: ThreadArticle) => void
}

/**
 * The articles of a thread, replies nested below what they answer (spec 008, "Thread page"). Each
 * level is indented and marked with a vertical line; a click on a line, or on the button of an
 * article, collapses the article and its replies to one line. What is collapsed is not remembered.
 *
 * An article by an ignored author is one line in its place, and its replies stay visible (spec
 * 010). The owner can show it for this page view, which does not mark it as read.
 */
export function ThreadArticles({
  thread,
  replies,
  onArticleRead,
  onStubFilled,
  onIgnoreAuthor,
}: ThreadArticlesProps) {
  const [collapsedIds, setCollapsedIds] = useState<ReadonlySet<number>>(() => new Set())
  const [shownIgnoredIds, setShownIgnoredIds] = useState<ReadonlySet<number>>(() => new Set())
  // One reply is written at a time.
  const [replyingToId, setReplyingToId] = useState<number | null>(null)
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
            ignoredShown={shownIgnoredIds.has(row.article.id)}
            onShowIgnored={() => {
              setShownIgnoredIds((shown) => new Set(shown).add(row.article.id))
            }}
            onIgnoreAuthor={() => {
              onIgnoreAuthor(row.article)
            }}
            replies={replies}
            replyingToId={replyingToId}
            onReplying={setReplyingToId}
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
  /** Whether the owner asked to see this article although its author is ignored. */
  ignoredShown: boolean
  onShowIgnored: () => void
  onIgnoreAuthor: () => void
  replies: ReplyContext
  /** The article whose reply is being written, or `null`. */
  replyingToId: number | null
  onReplying: (articleId: number | null) => void
}

/** One article: who wrote it and when, and its text. Names come from the article and are text. */
function ArticleRow({
  row,
  collapsed,
  onToggle,
  onDisplayed,
  ignoredShown,
  onShowIgnored,
  onIgnoreAuthor,
  replies,
  replyingToId,
  onReplying,
}: ArticleRowProps) {
  const { article, replyCount } = row
  const author = article.isStub ? 'Not available locally' : authorName(article.from)
  if (article.ignoredAuthor && !ignoredShown) {
    return (
      <article className="min-w-0 flex-1 py-2" aria-label={`Article by ignored author ${author}`}>
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground">
          <EyeOff aria-hidden="true" className="size-4 shrink-0" />
          <span className="max-w-full truncate">Ignored author · {author}</span>
          <Button type="button" variant="ghost" size="xs" onClick={onShowIgnored}>
            Show
          </Button>
        </div>
      </article>
    )
  }
  const canIgnoreAuthor =
    !article.isStub && !article.ignoredAuthor && (article.authorKey ?? null) !== null
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
            {article.sent && <Badge variant="outline">Sent</Badge>}
            {article.ignoredAuthor && <Badge variant="outline">Ignored author</Badge>}
            {canIgnoreAuthor && (
              <DropdownMenu>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <DropdownMenuTrigger asChild>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon-xs"
                        aria-label="More actions"
                        className="ml-auto text-muted-foreground"
                      >
                        <Ellipsis />
                      </Button>
                    </DropdownMenuTrigger>
                  </TooltipTrigger>
                  <TooltipContent>More actions</TooltipContent>
                </Tooltip>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem onSelect={onIgnoreAuthor}>
                    <EyeOff />
                    Ignore author
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            )}
          </>
        )}
      </div>
      {!collapsed && (
        <ArticleBody
          article={article}
          author={author}
          onDisplayed={onDisplayed}
          replies={replies}
          replyingToId={replyingToId}
          onReplying={onReplying}
        />
      )}
    </article>
  )
}

interface ArticleBodyProps {
  article: ThreadArticle
  author: string
  onDisplayed: () => void
  replies: ReplyContext
  replyingToId: number | null
  onReplying: (articleId: number | null) => void
}

/**
 * The text of an article and, once it is displayed, "Reply" (spec 009). The editor opens below
 * the article and starts with the quoted text: what is selected in the article, otherwise all of
 * it.
 */
function ArticleBody({
  article,
  author,
  onDisplayed,
  replies,
  replyingToId,
  onReplying,
}: ArticleBodyProps) {
  const content = useLoadedContent(article.id)
  const textBox = useRef<HTMLDivElement | null>(null)
  const [quotedText, setQuotedText] = useState('')
  const replying = replyingToId === article.id
  const rawHeaders = content?.rawHeaders ?? ''

  function reply() {
    if (content === undefined) {
      return
    }
    const selection = window.getSelection()
    const box = textBox.current
    const selected =
      selection !== null &&
      !selection.isCollapsed &&
      box !== null &&
      box.contains(selection.anchorNode) &&
      box.contains(selection.focusNode)
        ? selection.toString().trim()
        : ''
    setQuotedText(
      selected !== '' ? selected : textToQuote(content.body, content.flowed, content.delSp),
    )
    onReplying(article.id)
  }

  return (
    <div className="mt-2 animate-in pl-8 duration-150 fade-in motion-reduce:animate-none">
      <div ref={textBox}>
        <ArticleContent
          articleId={article.id}
          onRead={onDisplayed}
          marksRead={!article.ignoredAuthor}
        />
      </div>
      {content !== undefined &&
        !article.isStub &&
        replies.allowed &&
        !replying &&
        (repliesByEmailOnly(rawHeaders) ? (
          <p className="mt-2 text-xs text-muted-foreground">
            The author asked for replies by email. Wardroom does not send email.
          </p>
        ) : (
          <Button
            type="button"
            variant="ghost"
            size="xs"
            className="mt-2 -ml-2 text-muted-foreground"
            disabled={replyingToId !== null}
            onClick={reply}
          >
            <Reply />
            Reply
          </Button>
        ))}
      {replying && (
        <div className="mt-3 rounded-lg border p-3">
          <ArticleEditor
            groupId={replies.groupId}
            parentId={article.id}
            initialSubject={replySubject(article.subject)}
            initialBody={quotedReply(author, quotedText)}
            targetGroups={targetGroups(rawHeaders, replies.groupName)}
            moderated={replies.moderated}
            onPosted={(posted) => {
              onReplying(null)
              replies.onPosted(posted)
            }}
            onClose={() => {
              onReplying(null)
            }}
          />
        </div>
      )}
    </div>
  )
}
