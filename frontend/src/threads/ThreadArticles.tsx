import { ChevronDown, ChevronRight, Ellipsis, EyeOff, Mail, Reply } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { UNEXPECTED_ERROR } from '@/api/problem'
import { ArticleContent } from '@/articles/ArticleContent'
import { useLoadedContent } from '@/articles/contentState'
import { AuthorIcon } from '@/articles/AuthorIcon'
import { markArticleRead, markArticleUnread } from '@/articles/readState'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { scrollBehavior, useShortcuts } from '@/keyboard/shortcuts'
import { cn } from '@/lib/utils'
import { ArticleEditor } from '@/posting/ArticleEditor'
import type { PostedArticle } from '@/posting/postingState'
import {
  quotedReply,
  repliesByEmailOnly,
  replySubject,
  targetGroups,
  textToQuote,
} from '@/posting/quoting'
import { isUnreadInGroup, type Thread, type ThreadArticle } from '@/threads/threadState'
import { Time } from '@/threads/Time'
import { authorName } from '@/threads/times'

/** Indentation grows up to this level; deeper replies are shown at it (spec 008). */
export const MAX_INDENT = 6

/** How long an article that was scrolled to stays highlighted (spec 014). */
const HIGHLIGHT_MILLIS = 2000

/** What stays visible of the screen before when `Space` scrolls down, so the owner keeps the place. */
const SPACE_OVERLAP_PIXELS = 48

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
  /** The article of `?article=`, which the page scrolls to and highlights when it opens (spec 014). */
  initialArticleId?: number
  /**
   * Called when `N` finds no unread article after the current one: the next thread follows. Says
   * whether nothing was opened because only this thread still has unread articles.
   */
  onNoUnreadLeft: () => Promise<boolean>
  /** "Mark thread as read" of the page, for `C`; says whether the backend did it. */
  onMarkThreadRead: () => Promise<boolean>
  /** Called after an article was marked as unread (spec 014). */
  onArticleUnread: (article: ThreadArticle) => void
  /** Called when the backend no longer has an article of the page: the thread is asked for again. */
  onArticleGone: () => void
}

/**
 * The articles of a thread, replies nested below what they answer (spec 008, "Thread page"). Each
 * level is indented and marked with a vertical line; a click on a line, or on the button of an
 * article, collapses the article and its replies to one line. What is collapsed is not remembered.
 *
 * An article by an ignored author is one line in its place, and its replies stay visible (spec
 * 010). The owner can show it for this page view, which does not mark it as read.
 *
 * The keys of spec 014 move through the articles in the order of the page: `N` and `Space` to the
 * next unread one, `J` and `K` to the next and the previous one, `R` replies to the current one and
 * `C` marks the thread as read. An article marked as unread stays unread while the page is open,
 * unless one of these keys reaches it.
 */
export function ThreadArticles({
  thread,
  replies,
  onArticleRead,
  onStubFilled,
  onIgnoreAuthor,
  initialArticleId,
  onNoUnreadLeft,
  onMarkThreadRead,
  onArticleUnread,
  onArticleGone,
}: ThreadArticlesProps) {
  const [collapsedIds, setCollapsedIds] = useState<ReadonlySet<number>>(() => new Set())
  const [shownIgnoredIds, setShownIgnoredIds] = useState<ReadonlySet<number>>(() => new Set())
  // One reply is written at a time.
  const [replyingToId, setReplyingToId] = useState<number | null>(null)
  const reportedIds = useRef(new Set<number>())
  // The read state as it changed on this page; `thread` keeps it as the page opened (spec 008).
  const [readNowIds, setReadNowIds] = useState<ReadonlySet<number>>(() => new Set())
  const [markedUnreadIds, setMarkedUnreadIds] = useState<ReadonlySet<number>>(() => new Set())
  // The page opens at the article of `?article=`, highlighted (spec 014). It is the current article
  // until N, J or K reach another one.
  const openedAtId = thread.articles.some((article) => article.id === initialArticleId)
    ? (initialArticleId ?? null)
    : null
  const currentId = useRef<number | null>(openedAtId)
  const [highlightedId, setHighlightedId] = useState<number | null>(openedAtId)
  const [replyRequestId, setReplyRequestId] = useState<number | null>(null)
  const articleList = useRef<HTMLOListElement | null>(null)

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
      return
    }
    setReadNowIds((read) => new Set(read).add(article.id))
    if (!article.read) {
      onArticleRead(article)
    }
  }

  function isRead(article: ThreadArticle) {
    return (article.read || readNowIds.has(article.id)) && !markedUnreadIds.has(article.id)
  }

  async function markUnread(article: ThreadArticle) {
    const status = await markArticleUnread(article.id)
    if (status === 404) {
      onArticleGone()
      return
    }
    if (status !== 204) {
      toast.error(UNEXPECTED_ERROR)
      return
    }
    setMarkedUnreadIds((marked) => new Set(marked).add(article.id))
    setReadNowIds((read) => {
      const changed = new Set(read)
      changed.delete(article.id)
      return changed
    })
    onArticleUnread(article)
  }

  /**
   * A key reached the article: it is marked as read at once (spec 014), also when it was marked as
   * unread on this page. An article by an ignored author stays as it is (spec 010).
   */
  async function markReadByKey(article: ThreadArticle) {
    const wasMarkedUnread = markedUnreadIds.has(article.id)
    if (!wasMarkedUnread && (article.isStub || article.ignoredAuthor || isRead(article))) {
      return
    }
    // Displaying it afterwards does not count it a second time.
    reportedIds.current.add(article.id)
    setMarkedUnreadIds((marked) => {
      const changed = new Set(marked)
      changed.delete(article.id)
      return changed
    })
    if (await markArticleRead(article.id)) {
      setReadNowIds((read) => new Set(read).add(article.id))
      if (wasMarkedUnread || !article.read) {
        onArticleRead(article)
      }
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
  const visibleArticles = visibleRows.map((row) => row.article)

  function elementOf(articleId: number) {
    return articleList.current?.querySelector<HTMLElement>(
      `[data-article-id="${String(articleId)}"]`,
    )
  }

  /** The current article: the one a key reached last, otherwise the first at the top of the screen. */
  function currentIndex(): number {
    const reached = visibleArticles.findIndex((article) => article.id === currentId.current)
    if (reached !== -1) {
      return reached
    }
    const atTop = visibleArticles.findIndex(
      (article) => (elementOf(article.id)?.getBoundingClientRect().bottom ?? 0) > 0,
    )
    return atTop === -1 ? 0 : atTop
  }

  /** Scrolls to the article, highlights it briefly and marks it as read. */
  function reach(article: ThreadArticle) {
    currentId.current = article.id
    elementOf(article.id)?.scrollIntoView({ block: 'start', behavior: scrollBehavior() })
    setHighlightedId(article.id)
    void markReadByKey(article)
  }

  function nextUnread() {
    const target = visibleArticles
      .slice(currentIndex() + 1)
      .find((article) => isUnreadInGroup(article, isRead(article)))
    if (target === undefined) {
      goOn()
    } else {
      reach(target)
    }
  }

  /**
   * Goes on to the next thread. When only this thread still has unread articles, above the current
   * one, it goes to the first of them.
   */
  function goOn() {
    void onNoUnreadLeft().then((onlyHere) => {
      if (!onlyHere) {
        return
      }
      const target = visibleArticles.find((article) => isUnreadInGroup(article, isRead(article)))
      if (target === undefined) {
        toast('No unread articles')
      } else {
        reach(target)
      }
    })
  }

  function step(by: 1 | -1) {
    const target = visibleArticles[currentIndex() + by]
    if (target !== undefined) {
      reach(target)
    }
  }

  useShortcuts({
    n: nextUnread,
    ' ': () => {
      const root = document.documentElement
      if (window.innerHeight + window.scrollY >= root.scrollHeight - 2) {
        nextUnread()
      } else {
        window.scrollBy({
          top: window.innerHeight - SPACE_OVERLAP_PIXELS,
          behavior: scrollBehavior(),
        })
      }
    },
    j: () => {
      step(1)
    },
    k: () => {
      step(-1)
    },
    r: () => {
      const current = visibleArticles[currentIndex()]
      if (current !== undefined && !collapsedIds.has(current.id)) {
        setReplyRequestId(current.id)
      }
    },
    c: () => {
      void onMarkThreadRead().then((done) => {
        if (done) {
          void onNoUnreadLeft()
        }
      })
    },
  })

  // The address `?article=` scrolls to that article once, when the page opens.
  const scrolledToOpenedAt = useRef(false)
  useEffect(() => {
    if (scrolledToOpenedAt.current || openedAtId === null) {
      return
    }
    scrolledToOpenedAt.current = true
    articleList.current
      ?.querySelector(`[data-article-id="${String(openedAtId)}"]`)
      ?.scrollIntoView({ block: 'start' })
  }, [openedAtId])

  useEffect(() => {
    if (highlightedId === null) {
      return
    }
    const timer = window.setTimeout(() => {
      setHighlightedId(null)
    }, HIGHLIGHT_MILLIS)
    return () => {
      window.clearTimeout(timer)
    }
  }, [highlightedId])

  return (
    <ol ref={articleList} aria-label="Articles" className="flex flex-col">
      {visibleRows.map((row) => (
        <li
          key={row.article.id}
          className={cn(
            'flex scroll-mt-4 rounded-lg transition-colors duration-500 motion-reduce:transition-none',
            highlightedId === row.article.id && 'bg-primary/10',
          )}
          data-indent={row.indent}
          data-article-id={row.article.id}
        >
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
            markedUnread={markedUnreadIds.has(row.article.id)}
            canMarkUnread={!row.article.isStub && isRead(row.article)}
            onMarkUnread={() => void markUnread(row.article)}
            replies={replies}
            replyingToId={replyingToId}
            onReplying={setReplyingToId}
            replyRequested={replyRequestId === row.article.id}
            onReplyRequestHandled={() => {
              setReplyRequestId(null)
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
  /** Whether the owner asked to see this article although its author is ignored. */
  ignoredShown: boolean
  onShowIgnored: () => void
  onIgnoreAuthor: () => void
  /** Whether the owner marked the article as unread on this page; it stays unread while shown. */
  markedUnread: boolean
  /** Whether "Mark as unread" is offered: the article is read (spec 014). */
  canMarkUnread: boolean
  onMarkUnread: () => void
  replies: ReplyContext
  /** The article whose reply is being written, or `null`. */
  replyingToId: number | null
  onReplying: (articleId: number | null) => void
  /** Whether `R` asked to reply to this article. */
  replyRequested: boolean
  onReplyRequestHandled: () => void
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
  markedUnread,
  canMarkUnread,
  onMarkUnread,
  replies,
  replyingToId,
  onReplying,
  replyRequested,
  onReplyRequestHandled,
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
            {!article.isStub && (!article.read || markedUnread) && <Badge>new</Badge>}
            {article.otherGroup !== null && article.otherGroup !== undefined && (
              <Badge variant="outline">Posted to {article.otherGroup}</Badge>
            )}
            {article.sent && <Badge variant="outline">Sent</Badge>}
            {article.ignoredAuthor && <Badge variant="outline">Ignored author</Badge>}
            {(canIgnoreAuthor || canMarkUnread) && (
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
                  {canMarkUnread && (
                    <DropdownMenuItem onSelect={onMarkUnread}>
                      <Mail />
                      Mark as unread
                    </DropdownMenuItem>
                  )}
                  {canIgnoreAuthor && (
                    <DropdownMenuItem onSelect={onIgnoreAuthor}>
                      <EyeOff />
                      Ignore author
                    </DropdownMenuItem>
                  )}
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
          marksRead={!article.ignoredAuthor && !markedUnread}
          replies={replies}
          replyingToId={replyingToId}
          onReplying={onReplying}
          replyRequested={replyRequested}
          onReplyRequestHandled={onReplyRequestHandled}
        />
      )}
    </article>
  )
}

interface ArticleBodyProps {
  article: ThreadArticle
  author: string
  onDisplayed: () => void
  marksRead: boolean
  replies: ReplyContext
  replyingToId: number | null
  onReplying: (articleId: number | null) => void
  replyRequested: boolean
  onReplyRequestHandled: () => void
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
  marksRead,
  replies,
  replyingToId,
  onReplying,
  replyRequested,
  onReplyRequestHandled,
}: ArticleBodyProps) {
  const content = useLoadedContent(article.id)
  const textBox = useRef<HTMLDivElement | null>(null)
  const [quotedText, setQuotedText] = useState('')
  const replying = replyingToId === article.id
  const rawHeaders = content?.rawHeaders ?? ''
  const canReply =
    content !== undefined &&
    !article.isStub &&
    replies.allowed &&
    !replying &&
    replyingToId === null &&
    !repliesByEmailOnly(rawHeaders)

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

  // `R` replies where the button would; elsewhere it does nothing (spec 014).
  const replyNow = useRef(reply)
  useEffect(() => {
    replyNow.current = reply
  })
  useEffect(() => {
    if (!replyRequested) {
      return
    }
    onReplyRequestHandled()
    if (canReply) {
      replyNow.current()
    }
  }, [replyRequested, canReply, onReplyRequestHandled])

  return (
    <div className="mt-2 animate-in pl-8 duration-150 fade-in motion-reduce:animate-none">
      <div ref={textBox}>
        <ArticleContent articleId={article.id} onRead={onDisplayed} marksRead={marksRead} />
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
