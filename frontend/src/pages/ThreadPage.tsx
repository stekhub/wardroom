import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCheck, EyeOff } from 'lucide-react'
import { type MouseEvent } from 'react'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { UNEXPECTED_ERROR } from '@/api/problem'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import {
  type Subscription,
  useReloadSubscriptions,
  useSubscriptions,
} from '@/groups/subscriptionState'
import { type IgnoreRule, useIgnoring } from '@/ignoring/ignoreState'
import { useNextUnread } from '@/keyboard/nextUnread'
import { ReadingColumn } from '@/pages/ReadingColumn'
import { ThreadArticles } from '@/threads/ThreadArticles'
import {
  DEFAULT_SORT,
  fetchThread,
  type RequestFailure,
  type Thread,
  type ThreadArticle,
  type ThreadPageState,
  threadKey,
  useShownReadState,
} from '@/threads/threadState'

/**
 * The thread page (spec 008): one thread with its replies nested, in the place of the thread list
 * of its group. The address can name any article of the thread; `?article=` scrolls to one of
 * them (spec 014).
 */
export function ThreadPage() {
  const parameters = useParams()
  const groupId = Number(parameters.groupId)
  const articleId = Number(parameters.articleId)
  const subscriptions = useSubscriptions()

  if (subscriptions.data === undefined) {
    return (
      <ReadingColumn>
        {subscriptions.isError ? (
          <p className="text-sm text-destructive">The discussion could not be loaded.</p>
        ) : (
          <ThreadSkeleton />
        )}
      </ReadingColumn>
    )
  }
  const subscription = subscriptions.data.find((candidate) => candidate.groupId === groupId)
  if (subscription === undefined) {
    return (
      <ReadingColumn>
        <h1 className="mb-2 text-xl font-semibold tracking-tight">Not a subscribed group</h1>
        <p className="text-sm text-muted-foreground">
          You are not subscribed to the group of this discussion. Find it in{' '}
          <Link to="/discover" className="font-medium text-foreground underline underline-offset-4">
            Discover groups
          </Link>{' '}
          and subscribe to it.
        </p>
      </ReadingColumn>
    )
  }
  return (
    <ReadingColumn>
      <BackToGroup subscription={subscription} />
      <OpenThread key={articleId} subscription={subscription} articleId={articleId} />
    </ReadingColumn>
  )
}

/**
 * The way back to the thread list. Coming from the list, it goes back in the history, so the list
 * is at the scroll position it was left at (UI concept).
 */
function BackToGroup({ subscription }: { subscription: Subscription }) {
  const navigate = useNavigate()
  const location = useLocation()
  const cameFromList = (location.state as ThreadPageState | null)?.fromList === true

  function goBack(event: MouseEvent) {
    if (cameFromList) {
      event.preventDefault()
      void navigate(-1)
    }
  }

  return (
    <Link
      to={`/groups/${String(subscription.groupId)}`}
      onClick={goBack}
      aria-label={`Back to ${subscription.name}`}
      className="mb-4 inline-flex max-w-full items-center gap-1.5 rounded-sm text-sm text-muted-foreground outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
    >
      <ArrowLeft aria-hidden="true" className="size-4 shrink-0" />
      {/* The group name comes from the server and is shown as text only. */}
      <span className="truncate">{subscription.name}</span>
    </Link>
  )
}

function OpenThread({
  subscription,
  articleId,
}: {
  subscription: Subscription
  articleId: number
}) {
  const groupId = subscription.groupId
  const queryClient = useQueryClient()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const pageState = location.state as ThreadPageState | null
  // "Next unread" follows the list the owner came from (spec 014).
  const listQuery = pageState?.listQuery ?? { sort: DEFAULT_SORT }
  const initialArticleId = Number(searchParams.get('article') ?? Number.NaN)
  const goToNextUnread = useNextUnread()
  const reloadSubscriptions = useReloadSubscriptions()
  const { showArticleRead, showArticleUnread, markThreadRead } = useShownReadState()
  const { ignore, stopIgnoring } = useIgnoring()
  const key = threadKey(groupId, articleId)
  const thread = useQuery<Thread, RequestFailure>({
    queryKey: key,
    queryFn: () => fetchThread(groupId, articleId),
    // The "new" markers stay while the thread is read; opening it again asks again.
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  if (thread.data === undefined) {
    if (thread.error === null || thread.isFetching) {
      return <ThreadSkeleton />
    }
    if (thread.error.status === 404) {
      return (
        <>
          <h1 className="mb-2 text-xl font-semibold tracking-tight">
            This discussion is no longer available
          </h1>
          <p className="text-sm text-muted-foreground">
            <Link
              to={`/groups/${String(groupId)}`}
              className="font-medium text-foreground underline underline-offset-4"
            >
              Back to the group
            </Link>
          </p>
        </>
      )
    }
    return (
      <p role="alert" className="text-sm text-destructive">
        The discussion could not be loaded: {thread.error.message}
      </p>
    )
  }

  const shownThread = thread.data
  const subject = shownThread.articles.find((article) => !article.isStub)?.subject ?? '(no subject)'
  const unreadInGroup = shownThread.articles.some(
    (article) => !article.isStub && !article.read && (article.otherGroup ?? null) === null,
  )

  async function markRead(): Promise<boolean> {
    if (!(await markThreadRead(groupId, articleId, shownThread.rootId))) {
      toast.error(UNEXPECTED_ERROR)
      return false
    }
    queryClient.setQueryData<Thread>(key, (shown) =>
      shown === undefined
        ? shown
        : {
            ...shown,
            articles: shown.articles.map((article) =>
              article.isStub || (article.otherGroup ?? null) !== null
                ? article
                : { ...article, read: true },
            ),
          },
    )
    return true
  }

  /** Shows the thread as ignored by the rule, or as not ignored, without asking for it again. */
  function showThreadIgnored(rule: IgnoreRule | null) {
    queryClient.setQueryData<Thread>(key, (shown) =>
      shown === undefined
        ? shown
        : { ...shown, ignored: rule !== null, ignoreRuleIds: rule === null ? [] : [rule.id] },
    )
  }

  async function stopIgnoringThread() {
    if (await stopIgnoring(shownThread.ignoreRuleIds)) {
      showThreadIgnored(null)
    } else {
      toast.error(UNEXPECTED_ERROR)
    }
  }

  /** Every article of the author on this page is shown as ignored, or as before. */
  function ignoreAuthorOf(article: ThreadArticle) {
    const authorKey = article.authorKey ?? null
    void ignore('AUTHOR', article.id, (rule) => {
      queryClient.setQueryData<Thread>(key, (shown) =>
        shown === undefined
          ? shown
          : {
              ...shown,
              articles: shown.articles.map((candidate) =>
                (candidate.authorKey ?? null) === authorKey
                  ? { ...candidate, ignoredAuthor: rule !== null }
                  : candidate,
              ),
            },
      )
    })
  }

  /**
   * Asks for the thread again and keeps the "new" markers: after a stub was fetched, whose author,
   * date and maybe place in the thread are known now, and after a reply was sent.
   */
  async function showThreadAgain() {
    let current: Thread
    try {
      current = await fetchThread(groupId, articleId)
    } catch {
      // The thread stays as it is shown.
      return
    }
    queryClient.setQueryData<Thread>(key, (shown) => {
      // An article that was new when the page opened keeps its marker.
      const shownAsNew = new Set(
        shown?.articles.filter((article) => !article.isStub && !article.read).map(({ id }) => id),
      )
      return {
        ...current,
        articles: current.articles.map((article) =>
          shownAsNew.has(article.id) ? { ...article, read: false } : article,
        ),
      }
    })
  }

  return (
    <>
      <header className="mb-4 flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
        {/* The subject comes from an article and is shown as text only. */}
        <h1 className="min-w-0 flex-1 text-xl font-semibold tracking-tight break-words">
          {subject}
        </h1>
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={!unreadInGroup}
            onClick={() => void markRead()}
          >
            <CheckCheck />
            Mark thread as read
          </Button>
          {!shownThread.ignored && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => void ignore('THREAD', articleId, showThreadIgnored)}
            >
              <EyeOff />
              Ignore thread
            </Button>
          )}
        </div>
      </header>
      {shownThread.ignored && (
        <div
          role="status"
          className="mb-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-2 rounded-lg border bg-muted/50 px-3 py-2 text-sm"
        >
          <p className="flex items-center gap-2">
            <EyeOff aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
            You ignore this thread
          </p>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => void stopIgnoringThread()}
          >
            Stop ignoring
          </Button>
        </div>
      )}
      <ThreadArticles
        thread={shownThread}
        onArticleRead={(article) => {
          // The unread count of a card leaves out ignored authors (spec 010).
          if ((article.otherGroup ?? null) === null && !article.ignoredAuthor) {
            showArticleRead(groupId, shownThread.rootId)
          }
        }}
        replies={{
          groupId,
          groupName: subscription.name,
          allowed: subscription.postingStatus !== 'NOT_ALLOWED',
          moderated: subscription.postingStatus === 'MODERATED',
          onPosted: () => {
            toast.success('Your post was sent')
            void showThreadAgain()
          },
        }}
        onStubFilled={() => void showThreadAgain()}
        onIgnoreAuthor={ignoreAuthorOf}
        initialArticleId={Number.isInteger(initialArticleId) ? initialArticleId : undefined}
        onNoUnreadLeft={() =>
          goToNextUnread(groupId, listQuery, shownThread.rootId, pageState?.fromList === true)
        }
        onMarkThreadRead={markRead}
        onArticleUnread={(article) => {
          if ((article.otherGroup ?? null) === null && !article.ignoredAuthor) {
            showArticleUnread(groupId, shownThread.rootId)
          }
          // The unread counts in the sidebar change.
          void reloadSubscriptions()
        }}
        onArticleGone={() => void thread.refetch()}
      />
    </>
  )
}

function ThreadSkeleton() {
  return (
    <div role="status" aria-label="Loading the discussion">
      <Skeleton className="h-6 w-2/3" />
      <div className="mt-6 flex items-center gap-2">
        <Skeleton className="size-8 rounded-full" />
        <Skeleton className="h-4 w-48" />
      </div>
      <Skeleton className="mt-3 h-4 w-11/12" />
      <Skeleton className="mt-2 h-4 w-4/5" />
    </div>
  )
}
