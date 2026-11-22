import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { components } from '@/api/schema'
import { ArticleText } from '@/articles/ArticleText'
import { type Content, type ContentError, contentKey, fetchContent } from '@/articles/contentState'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useReloadSubscriptions } from '@/groups/subscriptionState'

type OmittedPart = components['schemas']['OmittedPart']

/** Whether the element has been on screen. Without a way to tell, it counts as on screen. */
function useSeen(): { ref: React.RefObject<HTMLDivElement | null>; seen: boolean } {
  const ref = useRef<HTMLDivElement | null>(null)
  const [seen, setSeen] = useState(() => typeof IntersectionObserver === 'undefined')
  useEffect(() => {
    const element = ref.current
    if (seen || element === null) {
      return
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setSeen(true)
      }
    })
    observer.observe(element)
    return () => {
      observer.disconnect()
    }
  }, [seen])
  return { ref, seen }
}

interface ArticleContentProps {
  articleId: number
  /** Called once when the article was marked as read. */
  onRead?: () => void
  /**
   * Whether displaying the text marks the article as read. An article by an ignored author that
   * the owner looks at stays unread (spec 010).
   */
  marksRead?: boolean
}

/**
 * The content of one article (spec 007). It is fetched when the article scrolls into view, and
 * the article is marked as read once its text is displayed. Content that was fetched is kept, so
 * an article is asked for once.
 */
export function ArticleContent({ articleId, onRead, marksRead = true }: ArticleContentProps) {
  const { ref, seen } = useSeen()
  const queryClient = useQueryClient()
  const [askingAgain, setAskingAgain] = useState(false)
  const content = useQuery<Content, ContentError>({
    queryKey: contentKey(articleId),
    queryFn: () => fetchContent(articleId, false),
    enabled: seen,
    staleTime: Infinity,
    retry: false,
  })

  const tryAgain = async () => {
    setAskingAgain(true)
    try {
      // The server is asked again even when it did not have the article before.
      const fetched = await fetchContent(articleId, true)
      queryClient.setQueryData(contentKey(articleId), fetched)
    } catch {
      await content.refetch()
    } finally {
      setAskingAgain(false)
    }
  }

  return (
    <div ref={ref} className="min-h-6">
      {content.data !== undefined ? (
        <LoadedContent content={content.data} onRead={onRead} marksRead={marksRead} />
      ) : content.error !== null && !content.isFetching ? (
        <div
          className="flex flex-wrap items-center gap-3 text-sm text-muted-foreground"
          role="alert"
        >
          <span>
            {content.error.gone
              ? 'This article is no longer available on the server.'
              : `The text could not be loaded: ${content.error.message}`}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={askingAgain}
            onClick={() => void tryAgain()}
          >
            Try again
          </Button>
        </div>
      ) : (
        <div className="space-y-2" aria-busy="true" aria-label="Loading the text">
          <Skeleton className="h-4 w-11/12" />
          <Skeleton className="h-4 w-4/5" />
          <Skeleton className="h-4 w-2/3" />
        </div>
      )}
    </div>
  )
}

interface LoadedContentProps {
  content: Content
  onRead?: () => void
  marksRead: boolean
}

function LoadedContent({ content, onRead, marksRead }: LoadedContentProps) {
  const [fixedWidth, setFixedWidth] = useState(false)
  const [headersShown, setHeadersShown] = useState(false)
  useMarkRead(content.articleId, marksRead, onRead)

  const attachments = content.omittedParts.filter((part) => part.mediaType !== 'text/html')
  const htmlOnly = content.body === '' && attachments.length < content.omittedParts.length
  return (
    <div className="space-y-2">
      {headersShown && (
        <pre className="overflow-x-auto rounded-md bg-muted p-3 font-mono text-xs whitespace-pre-wrap text-muted-foreground">
          {content.rawHeaders}
        </pre>
      )}
      {htmlOnly && <Notice>HTML message, not displayed</Notice>}
      {content.body !== '' && (
        <ArticleText
          body={content.body}
          flowed={content.flowed}
          delSp={content.delSp}
          fixedWidth={fixedWidth}
        />
      )}
      {content.truncated && (
        <Notice>This article is too long or too complex. Only a part of it is shown.</Notice>
      )}
      {attachments.length > 0 && <Notice>{attachmentNotice(attachments)}</Notice>}
      <div className="flex gap-1">
        <Button
          variant="ghost"
          size="xs"
          aria-pressed={fixedWidth}
          className="text-muted-foreground"
          onClick={() => {
            setFixedWidth((shown) => !shown)
          }}
        >
          Fixed width
        </Button>
        <Button
          variant="ghost"
          size="xs"
          aria-pressed={headersShown}
          className="text-muted-foreground"
          onClick={() => {
            setHeadersShown((shown) => !shown)
          }}
        >
          Show all headers
        </Button>
      </div>
    </div>
  )
}

function Notice({ children }: { children: React.ReactNode }) {
  return <p className="text-sm text-muted-foreground italic">{children}</p>
}

/** "1 attachment omitted (photo.jpg, 245 kB)". Names come from the article and are shown as text. */
function attachmentNotice(attachments: OmittedPart[]): string {
  const described = attachments
    .map((part) => `${part.name ?? part.mediaType}, ${formatSize(part.size)}`)
    .join('; ')
  const noun = attachments.length === 1 ? 'attachment' : 'attachments'
  return `${String(attachments.length)} ${noun} omitted (${described})`
}

function formatSize(bytes: number): string {
  if (bytes < 1000) {
    return `${String(bytes)} B`
  }
  if (bytes < 1_000_000) {
    return `${String(Math.round(bytes / 1000))} kB`
  }
  return `${(bytes / 1_000_000).toFixed(1)} MB`
}

/** Marks the article as read once, when its content is displayed. A failure is not shown. */
function useMarkRead(articleId: number, marksRead: boolean, onRead?: () => void) {
  const reloadSubscriptions = useReloadSubscriptions()
  const marked = useRef(false)
  const whenRead = useRef(onRead)
  useEffect(() => {
    whenRead.current = onRead
  }, [onRead])
  useEffect(() => {
    if (marked.current || !marksRead) {
      return
    }
    marked.current = true
    void api.PUT('/api/articles/{articleId}/read', { params: { path: { articleId } } }).then(
      ({ response }) => {
        if (response.ok) {
          // The unread counts in the sidebar change.
          void reloadSubscriptions()
          whenRead.current?.()
        }
      },
      () => undefined,
    )
  }, [articleId, marksRead, reloadSubscriptions])
}
