import { type ReactNode, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from 'sonner'
import { UNEXPECTED_ERROR } from '@/api/problem'
import { useReloadSubscriptions } from '@/groups/subscriptionState'
import { useNextUnread } from '@/keyboard/nextUnread'
import { useShortcuts } from '@/keyboard/shortcuts'
import { DiscussionCard } from '@/threads/DiscussionCard'
import { type ThreadListQuery, type useThreadList, useShownReadState } from '@/threads/threadState'

/** Cards of the placeholder that stands in for the list while it loads. */
const SKELETON_CARDS = 3

interface ThreadCardsProps {
  groupId: number
  list: ReturnType<typeof useThreadList>
  /** What the list was asked for; "next unread" follows the same order and limits (spec 014). */
  listQuery: ThreadListQuery
  /** Shown instead of the cards when the list has none. */
  empty: ReactNode
  /** Shown below the last card once every page is loaded. */
  end: ReactNode
}

/**
 * The cards of a thread list (spec 008, "Paging"): the next page is loaded when the end of the
 * loaded cards scrolls into view, or with the button there.
 *
 * The keys of spec 014: `N` and `Space` open the next thread with an unread article, `J` and `K`
 * move the focus from card to card, Enter opens the card with the focus, and `C` marks its thread
 * as read and goes on as `N`.
 */
export function ThreadCards({ groupId, list, listQuery, empty, end }: ThreadCardsProps) {
  const navigate = useNavigate()
  const reloadSubscriptions = useReloadSubscriptions()
  const { fetchNextPage, hasNextPage, isFetchingNextPage } = list
  const moreButton = useRef<HTMLButtonElement | null>(null)
  const cardList = useRef<HTMLUListElement | null>(null)
  const goToNextUnread = useNextUnread()
  const { markThreadRead } = useShownReadState()

  function nextUnread() {
    void goToNextUnread(groupId, listQuery)
  }

  /** Moves the focus to the next or the previous card; without a card with the focus, to the first. */
  function moveFocus(step: 1 | -1) {
    const links = Array.from(
      cardList.current?.querySelectorAll<HTMLAnchorElement>('[data-card-link]') ?? [],
    )
    const focused = document.activeElement?.closest('[data-root-id]')
    const index = links.findIndex((link) => link.closest('[data-root-id]') === focused)
    const target = index === -1 ? links[0] : links[index + step]
    target?.focus()
  }

  async function markFocusedThreadRead() {
    const rootId = Number(
      document.activeElement?.closest<HTMLElement>('[data-root-id]')?.dataset.rootId,
    )
    if (!Number.isInteger(rootId) || cardList.current?.contains(document.activeElement) !== true) {
      return
    }
    if (!(await markThreadRead(groupId, rootId, rootId))) {
      toast.error(UNEXPECTED_ERROR)
      return
    }
    nextUnread()
  }

  useShortcuts({
    n: nextUnread,
    ' ': nextUnread,
    j: () => {
      moveFocus(1)
    },
    k: () => {
      moveFocus(-1)
    },
    c: () => void markFocusedThreadRead(),
  })

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
        <ul ref={cardList} aria-label="Discussions" className="flex flex-col gap-3">
          {cards.map((card) => (
            <li key={card.rootId} data-root-id={card.rootId}>
              <DiscussionCard groupId={groupId} card={card} listQuery={listQuery} />
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
