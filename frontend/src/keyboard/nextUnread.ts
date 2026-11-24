import { useCallback, useRef } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { UNEXPECTED_ERROR } from '@/api/problem'
import { type Subscription, useSubscriptions } from '@/groups/subscriptionState'
import {
  fetchThread,
  isUnreadInGroup,
  type ThreadListQuery,
  type ThreadPageState,
} from '@/threads/threadState'

/** The answer of the backend: the next thread, none in this group, or the group is gone. */
type NextThread = number | 'none' | 'gone'

async function fetchNextThread(
  groupId: number,
  listQuery: ThreadListQuery,
  afterRootId: number | undefined,
): Promise<NextThread> {
  const { data, response } = await api.GET('/api/groups/{groupId}/threads/next-unread', {
    params: { path: { groupId }, query: { ...listQuery, after: afterRootId } },
  })
  if (response.status === 204) {
    return 'none'
  }
  if (data !== undefined) {
    return data.threadArticleId
  }
  if (response.status === 404) {
    return 'gone'
  }
  throw new Error(`Unexpected answer ${String(response.status)} to the next unread request`)
}

/**
 * The next subscribed group with unread articles after the group, in the order of the sidebar,
 * starting again at the top after the last one (spec 014). The group itself is not one.
 */
export function nextGroupWithUnread(
  subscriptions: readonly Subscription[],
  groupId: number,
): Subscription | undefined {
  const index = subscriptions.findIndex((subscription) => subscription.groupId === groupId)
  const inOrder = [...subscriptions.slice(index + 1), ...subscriptions.slice(0, Math.max(index, 0))]
  return inOrder.find(
    (subscription) => subscription.groupId !== groupId && subscription.unreadCount > 0,
  )
}

/**
 * "Next unread" across threads and groups (spec 014). The thread page finds the next unread
 * article within its thread itself and calls this only when there is none.
 */
export function useNextUnread() {
  const navigate = useNavigate()
  const subscriptions = useSubscriptions().data
  // A key that is held or pressed quickly does not start a second search.
  const searching = useRef(false)

  /** Opens the thread at its first unread article, as `?article=` (spec 014). */
  const openThread = useCallback(
    async (
      groupId: number,
      threadArticleId: number,
      listQuery: ThreadListQuery,
      replace: boolean,
      fromList: boolean,
    ) => {
      let firstUnreadId: number | undefined
      try {
        const thread = await fetchThread(groupId, threadArticleId)
        firstUnreadId = thread.articles.find((article) =>
          isUnreadInGroup(article, article.read),
        )?.id
      } catch {
        // The thread page says what is wrong with the thread.
      }
      const address = `/groups/${String(groupId)}/threads/${String(threadArticleId)}`
      await navigate(
        firstUnreadId === undefined ? address : `${address}?article=${String(firstUnreadId)}`,
        { state: { fromList, listQuery } satisfies ThreadPageState, replace },
      )
    },
    [navigate],
  )

  /**
   * Opens the next thread of the list with an unread article. At the end of the group, it opens
   * the next group with unread articles; when there is none, it says so.
   *
   * @returns whether nothing was opened because only the thread the owner is in still has unread
   *          articles; the thread page then goes to them itself
   *
   * @param afterRootId the thread the owner is in, or `undefined` in the group view
   * @param fromList    whether going back from the next thread returns to the list; a thread
   *                    follows the one before in the history, so going back skips it
   */
  const goToNextUnread = useCallback(
    async (groupId: number, listQuery: ThreadListQuery, afterRootId?: number, fromList = true) => {
      if (searching.current) {
        return false
      }
      searching.current = true
      const fromThread = afterRootId !== undefined
      try {
        const next = await fetchNextThread(groupId, listQuery, afterRootId)
        if (typeof next === 'number') {
          await openThread(groupId, next, listQuery, fromThread, fromList)
          return false
        }
        const nextGroup = nextGroupWithUnread(subscriptions ?? [], groupId)
        if (nextGroup !== undefined) {
          // The owner sees where they are before reading on.
          await navigate(`/groups/${String(nextGroup.groupId)}`)
          return false
        }
        if (next === 'none' && fromThread) {
          // Threads above the current one may still have unread articles.
          const fromTop = await fetchNextThread(groupId, listQuery, undefined)
          if (fromTop === afterRootId) {
            return true
          }
          if (typeof fromTop === 'number') {
            await openThread(groupId, fromTop, listQuery, true, fromList)
            return false
          }
        }
        toast('No unread articles')
        return false
      } catch {
        toast.error(UNEXPECTED_ERROR)
        return false
      } finally {
        searching.current = false
      }
    },
    [navigate, openThread, subscriptions],
  )

  return goToNextUnread
}
