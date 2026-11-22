import { type ReactNode, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useReloadSubscriptions } from '@/groups/subscriptionState'
import { DiscussionCard } from '@/threads/DiscussionCard'
import type { useThreadList } from '@/threads/threadState'

/** Cards of the placeholder that stands in for the list while it loads. */
const SKELETON_CARDS = 3

interface ThreadCardsProps {
  groupId: number
  list: ReturnType<typeof useThreadList>
  /** Shown instead of the cards when the list has none. */
  empty: ReactNode
  /** Shown below the last card once every page is loaded. */
  end: ReactNode
}

/**
 * The cards of a thread list (spec 008, "Paging"): the next page is loaded when the end of the
 * loaded cards scrolls into view, or with the button there.
 */
export function ThreadCards({ groupId, list, empty, end }: ThreadCardsProps) {
  const navigate = useNavigate()
  const reloadSubscriptions = useReloadSubscriptions()
  const { fetchNextPage, hasNextPage, isFetchingNextPage } = list
  const moreButton = useRef<HTMLButtonElement | null>(null)

  useEffect(() => {
    const button = moreButton.current
    if (button === null || !hasNextPage || typeof IntersectionObserver === 'undefined') {
      return
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting) && !isFetchingNextPage) {
          void fetchNextPage()
        }
      },
      // A little before the end is reached, so the owner rarely sees the button.
      { rootMargin: '400px' },
    )
    observer.observe(button)
    return () => {
      observer.disconnect()
    }
  }, [fetchNextPage, hasNextPage, isFetchingNextPage])

  const groupGone = list.error?.status === 404
  useEffect(() => {
    if (groupGone) {
      // The group was unsubscribed while it was open (spec 008, "Error handling").
      void reloadSubscriptions()
      void navigate('/discover')
    }
  }, [groupGone, navigate, reloadSubscriptions])

  if (list.data === undefined) {
    if (list.error !== null && !list.isFetching) {
      return (
        <p role="alert" className="text-sm text-destructive">
          The discussions could not be loaded: {list.error.message}
        </p>
      )
    }
    return (
      <div role="status" aria-label="Loading the discussions" className="flex flex-col gap-3">
        {Array.from({ length: SKELETON_CARDS }, (_, card) => (
          <div key={card} className="rounded-xl border p-4">
            <Skeleton className="h-5 w-3/5" />
            <Skeleton className="mt-3 h-4 w-2/5" />
            <Skeleton className="mt-3 h-4 w-11/12" />
          </div>
        ))}
      </div>
    )
  }
  const cards = list.data.pages.flatMap((page) => page.items)
  return (
    <>
      {cards.length === 0 ? (
        empty
      ) : (
        <ul aria-label="Discussions" className="flex flex-col gap-3">
          {cards.map((card) => (
            <li key={card.rootId}>
              <DiscussionCard groupId={groupId} card={card} />
            </li>
          ))}
        </ul>
      )}
      {hasNextPage ? (
        <div className="mt-6 flex justify-center">
          <Button
            ref={moreButton}
            type="button"
            variant="outline"
            disabled={isFetchingNextPage}
            onClick={() => void fetchNextPage()}
          >
            {isFetchingNextPage ? 'Loading…' : 'Show more discussions'}
          </Button>
        </div>
      ) : (
        end
      )}
    </>
  )
}
