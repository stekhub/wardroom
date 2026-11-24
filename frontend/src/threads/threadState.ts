import {
  type InfiniteData,
  type QueryClient,
  useInfiniteQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { useCallback } from 'react'
import { api } from '@/api/client'
import { problemFieldErrors, problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import type { components } from '@/api/schema'
import { lastSyncedAtOf, useReloadSubscriptions } from '@/groups/subscriptionState'

export type ThreadCard = components['schemas']['ThreadCard']
export type ThreadPage = components['schemas']['ThreadPage']
export type Thread = components['schemas']['Thread']
export type ThreadArticle = components['schemas']['ThreadArticle']

/** The sort orders of the thread list, as the address and the backend name them (spec 008). */
export const SORTS = ['newest', 'activity', 'discussed'] as const
export type Sort = (typeof SORTS)[number]
export const DEFAULT_SORT: Sort = 'activity'

/** What the backend is asked for, besides the cursor. */
export interface ThreadListQuery {
  sort?: Sort
  until?: string
  rangeId?: number
}

/**
 * A page of thread cards as it is kept.
 *
 * `syncedAt` is the last successful synchronization of the group that was known when the page
 * was asked for. A later one means that the list may be out of date.
 */
export interface LoadedThreadPage extends ThreadPage {
  syncedAt: number | null
}

/**
 * What the thread page is told by the list it was opened from: whether going back can return to
 * it, and its order and limits, which "next unread" follows (spec 014).
 */
export interface ThreadPageState {
  fromList?: boolean
  listQuery?: ThreadListQuery
}

/** A request the backend refused, with its reason for the owner. */
export class RequestFailure extends Error {
  readonly status: number
  /** The parameters a `400` names as invalid. */
  readonly fields: string[]

  constructor(message: string, status: number, fields: string[] = []) {
    super(message)
    this.status = status
    this.fields = fields
  }
}

const THREAD_LISTS_KEY = 'threads'

/** How long a list that is not shown is kept, so that going back shows it as it was left. */
export const LIST_KEPT_MILLIS = 30 * 60_000

/** The first part of the key of every thread list, of every group. */
export function allThreadLists() {
  return [THREAD_LISTS_KEY] as const
}

export function threadListsOfGroup(groupId: number) {
  return [THREAD_LISTS_KEY, groupId] as const
}

export function threadKey(groupId: number, articleId: number) {
  return ['thread', groupId, articleId] as const
}

export async function fetchThreadPage(
  groupId: number,
  query: ThreadListQuery,
  cursor?: string,
): Promise<ThreadPage> {
  const { data, error, response } = await api.GET('/api/groups/{groupId}/threads', {
    params: { path: { groupId }, query: { ...query, cursor } },
  })
  if (data === undefined) {
    throw new RequestFailure(
      problemMessage(error, UNEXPECTED_ERROR),
      response.status,
      problemFieldErrors(error).map((fieldError) => fieldError.field),
    )
  }
  return data
}

export async function fetchThread(groupId: number, articleId: number): Promise<Thread> {
  const { data, error, response } = await api.GET('/api/groups/{groupId}/threads/{articleId}', {
    params: { path: { groupId, articleId } },
  })
  if (data === undefined) {
    throw new RequestFailure(problemMessage(error, UNEXPECTED_ERROR), response.status)
  }
  return data
}

/**
 * Whether the article counts as unread for "next unread" (spec 014): an article of the group that
 * is not read, and whose author is not ignored.
 */
export function isUnreadInGroup(article: ThreadArticle, read: boolean): boolean {
  return !article.isStub && !read && (article.otherGroup ?? null) === null && !article.ignoredAuthor
}

/**
 * The thread list of a group, page by page. A loaded list stays as it is: it is not asked for
 * again behind the owner's back, so it is never reordered while it is read (spec 008, "Updates").
 *
 * @param view  what tells the lists of a group apart: the sort order, or the day of a date view
 * @param query what to ask the backend for; read again for every page
 */
export function useThreadList(groupId: number, view: string, query: () => ThreadListQuery) {
  const queryClient = useQueryClient()
  return useInfiniteQuery<
    LoadedThreadPage,
    RequestFailure,
    InfiniteData<LoadedThreadPage>,
    readonly unknown[],
    string | undefined
  >({
    queryKey: [...threadListsOfGroup(groupId), view],
    queryFn: async ({ pageParam }) => {
      const syncedAt = lastSyncedAtOf(queryClient, groupId)
      return { ...(await fetchThreadPage(groupId, query(), pageParam)), syncedAt }
    },
    initialPageParam: undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    staleTime: Infinity,
    // Kept while a thread is read, so that going back shows the list as it was left.
    gcTime: LIST_KEPT_MILLIS,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
}

function changeCards(
  queryClient: QueryClient,
  groupId: number,
  change: (card: ThreadCard) => ThreadCard,
) {
  queryClient.setQueriesData<InfiniteData<LoadedThreadPage>>(
    { queryKey: threadListsOfGroup(groupId) },
    (list) =>
      list === undefined
        ? list
        : {
            ...list,
            pages: list.pages.map((page) => ({ ...page, items: page.items.map(change) })),
          },
  )
}

/**
 * Changes of the read state, shown in the loaded thread lists without asking for them again.
 */
export function useShownReadState() {
  const queryClient = useQueryClient()
  const reloadSubscriptions = useReloadSubscriptions()

  /** One article of the thread was read. */
  const showArticleRead = useCallback(
    (groupId: number, rootId: number) => {
      changeCards(queryClient, groupId, (card) =>
        card.rootId === rootId ? { ...card, unreadCount: Math.max(0, card.unreadCount - 1) } : card,
      )
    },
    [queryClient],
  )

  /** One article of the thread was marked as unread (spec 014). */
  const showArticleUnread = useCallback(
    (groupId: number, rootId: number) => {
      changeCards(queryClient, groupId, (card) =>
        card.rootId === rootId ? { ...card, unreadCount: card.unreadCount + 1 } : card,
      )
    },
    [queryClient],
  )

  /** Every article of the group was marked as read. */
  const showGroupRead = useCallback(
    (groupId: number) => {
      changeCards(queryClient, groupId, (card) => ({ ...card, unreadCount: 0 }))
    },
    [queryClient],
  )

  /**
   * "Mark thread as read" (spec 008): asks the backend and shows the result.
   *
   * @param articleId any article of the thread
   * @returns whether the backend did it
   */
  const markThreadRead = useCallback(
    async (groupId: number, articleId: number, rootId: number): Promise<boolean> => {
      const { response } = await api.POST('/api/groups/{groupId}/threads/{articleId}/mark-read', {
        params: { path: { groupId, articleId } },
      })
      if (response.ok) {
        changeCards(queryClient, groupId, (card) =>
          card.rootId === rootId ? { ...card, unreadCount: 0 } : card,
        )
      }
      // The unread counts in the sidebar change.
      await reloadSubscriptions()
      return response.ok
    },
    [queryClient, reloadSubscriptions],
  )

  return { showArticleRead, showArticleUnread, showGroupRead, markThreadRead }
}
