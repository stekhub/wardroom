import { useQuery, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import { Button } from '@/components/ui/button'
import type { Subscription } from '@/groups/subscriptionState'
import { cn } from '@/lib/utils'
import { ThreadCards } from '@/threads/ThreadCards'
import { DEFAULT_SORT, fetchThreadPage, type Sort, useThreadList } from '@/threads/threadState'

const SORT_TABS: { sort: Sort; label: string }[] = [
  { sort: 'newest', label: 'Newest' },
  { sort: 'activity', label: 'Latest activity' },
  { sort: 'discussed', label: 'Most discussed' },
]

const COUNT = new Intl.NumberFormat('en')

/**
 * The thread list of a group in the normal view (spec 008): sort tabs, cards, "Load older
 * discussions" at the end, and the button that says that a synchronization brought something new.
 */
export function ThreadList({ subscription, sort }: { subscription: Subscription; sort: Sort }) {
  const groupId = subscription.groupId
  const queryClient = useQueryClient()
  const list = useThreadList(groupId, `sort:${sort}`, () => ({ sort }))
  const [loadingOlder, setLoadingOlder] = useState(false)

  const pages = list.data?.pages
  const shownCards = pages?.flatMap((page) => page.items) ?? []
  const lastPage = pages?.at(-1)
  const syncedAt = subscription.sync.lastSyncedAt ?? null
  // A synchronization has finished since the list was loaded.
  const outdated =
    pages !== undefined && !list.isFetching && syncedAt !== null && syncedAt !== pages[0]?.syncedAt
  const listIsEmpty = shownCards.length === 0

  // The list itself stays as it is; what is new is counted from a first page asked for aside.
  const newest = useQuery({
    queryKey: ['threads-aside', groupId, sort, syncedAt],
    queryFn: () => fetchThreadPage(groupId, { sort }),
    enabled: outdated && !listIsEmpty,
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
  })
  const shownMessageCounts = new Map(shownCards.map((card) => [card.rootId, card.messageCount]))
  const changedCount = outdated
    ? (newest.data?.items.filter(
        (card) => (shownMessageCounts.get(card.rootId) ?? 0) < card.messageCount,
      ).length ?? 0)
    : 0

  const { refetch } = list
  useEffect(() => {
    // An empty list has no order to keep: it is loaded again at once, as after the first
    // synchronization.
    if (outdated && listIsEmpty) {
      void refetch()
    }
  }, [outdated, listIsEmpty, refetch])

  async function showNew() {
    await queryClient.resetQueries({ queryKey: ['threads', groupId, `sort:${sort}`], exact: true })
    window.scrollTo({ top: 0 })
  }

  async function loadOlder(rangeId: number, again = true) {
    setLoadingOlder(true)
    const { data, error, response } = await api.POST(
      '/api/groups/{groupId}/ranges/{rangeId}/extend',
      { params: { path: { groupId, rangeId } } },
    )
    if (data === undefined) {
      // A jump to date can join the newest range with its own, which gives it another ID
      // (ADR 0021). The list is asked for its range as it is now, and the step is tried once more.
      const rangeNow =
        response.status === 404 && again ? (await refetch()).data?.pages.at(-1)?.rangeId : undefined
      if (typeof rangeNow === 'number' && rangeNow !== rangeId) {
        await loadOlder(rangeNow, false)
        return
      }
      if (response.status !== 401) {
        toast.error(problemMessage(error, UNEXPECTED_ERROR))
      }
    } else {
      await refetch()
    }
    setLoadingOlder(false)
  }

  const firstSynchronization = syncedAt === null && (subscription.sync.error ?? '') === ''
  const rangeId = lastPage?.rangeId ?? null
  return (
    <section aria-label="Discussions">
      <nav aria-label="Sort order" className="mb-4 flex gap-1 border-b">
        {SORT_TABS.map((tab) => (
          <Link
            key={tab.sort}
            to={tab.sort === DEFAULT_SORT ? '.' : `?sort=${tab.sort}`}
            aria-current={tab.sort === sort ? 'page' : undefined}
            className={cn(
              '-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors duration-150 outline-none focus-visible:ring-2 focus-visible:ring-ring motion-reduce:transition-none',
              tab.sort === sort
                ? 'border-primary text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground',
            )}
          >
            {tab.label}
          </Link>
        ))}
      </nav>
      {changedCount > 0 && (
        <div className="sticky top-2 z-10 mb-3 flex justify-center">
          <Button type="button" size="sm" className="shadow-md" onClick={() => void showNew()}>
            {COUNT.format(changedCount)} new {changedCount === 1 ? 'discussion' : 'discussions'} ·
            show
          </Button>
        </div>
      )}
      <ThreadCards
        groupId={groupId}
        list={list}
        listQuery={{ sort }}
        empty={
          firstSynchronization ? (
            <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
              <LoaderCircle
                aria-hidden="true"
                className="size-4 animate-spin motion-reduce:animate-none"
              />
              First synchronization running…
            </p>
          ) : (
            <p className="text-sm text-muted-foreground">No discussions yet</p>
          )
        }
        end={
          rangeId !== null &&
          lastPage?.lowWatermarkReached === false && (
            <div className="mt-6 flex justify-center">
              <Button
                type="button"
                variant="outline"
                disabled={loadingOlder}
                onClick={() => void loadOlder(rangeId)}
              >
                {loadingOlder ? 'Loading…' : 'Load older discussions'}
              </Button>
            </div>
          )
        }
      />
    </section>
  )
}
