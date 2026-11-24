import { type QueryClient, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { api } from '@/api/client'
import type { components } from '@/api/schema'
import type { GroupPage } from '@/groups/groupListState'

export type Subscription = components['schemas']['Subscription']

/** The subscription list is asked for again this often (spec 004). */
const POLL_MILLIS = 30_000

/** And this often while a synchronization is running. */
const RUNNING_SYNC_POLL_MILLIS = 2_000

const SUBSCRIPTIONS_KEY = ['subscriptions'] as const

/** The first part of the key under which the pages of the group list are kept. */
const GROUP_PAGES_KEY = ['groups'] as const

async function fetchSubscriptions(): Promise<Subscription[]> {
  const { data, response } = await api.GET('/api/subscriptions')
  if (data === undefined) {
    throw new Error(`Unexpected answer ${String(response.status)} to the subscriptions request`)
  }
  return data
}

/**
 * The subscribed groups by name, with their unread counts and synchronization status.
 *
 * The list is also asked for while the tab is hidden (spec 011): these requests keep the session
 * of an open page alive, so only a closed browser is logged out.
 */
export function useSubscriptions() {
  return useQuery({
    queryKey: SUBSCRIPTIONS_KEY,
    queryFn: fetchSubscriptions,
    refetchInterval: (query) =>
      query.state.data?.some((subscription) => subscription.sync.running) === true
        ? RUNNING_SYNC_POLL_MILLIS
        : POLL_MILLIS,
    refetchIntervalInBackground: true,
    retry: false,
  })
}

/** Loads the subscription list again, after something has changed it. */
export function useReloadSubscriptions() {
  const queryClient = useQueryClient()
  return useCallback(
    () => queryClient.invalidateQueries({ queryKey: SUBSCRIPTIONS_KEY }),
    [queryClient],
  )
}

/** Changes the flag of the group in every page of the group list that is loaded. */
function showSubscribed(queryClient: QueryClient, groupId: number, subscribed: boolean) {
  queryClient.setQueriesData<GroupPage>({ queryKey: GROUP_PAGES_KEY }, (page) =>
    page === undefined
      ? page
      : {
          ...page,
          items: page.items.map((group) =>
            group.id === groupId ? { ...group, subscribed } : group,
          ),
        },
  )
}

/**
 * Subscribing and unsubscribing (spec 004). Both say with their result whether the backend did
 * it; an ended session counts as not done and is handled by the API client.
 */
export function useSubscriptionChange() {
  const queryClient = useQueryClient()

  /** The toggle changes at once and goes back when the backend refuses. */
  const subscribe = useCallback(
    async (groupId: number): Promise<boolean> => {
      showSubscribed(queryClient, groupId, true)
      const { response } = await api.PUT('/api/groups/{groupId}/subscription', {
        params: { path: { groupId } },
      })
      if (!response.ok) {
        showSubscribed(queryClient, groupId, false)
      }
      await queryClient.invalidateQueries({ queryKey: SUBSCRIPTIONS_KEY })
      return response.ok
    },
    [queryClient],
  )

  const unsubscribe = useCallback(
    async (groupId: number): Promise<boolean> => {
      const { response } = await api.DELETE('/api/groups/{groupId}/subscription', {
        params: { path: { groupId } },
      })
      if (response.ok) {
        showSubscribed(queryClient, groupId, false)
        queryClient.setQueryData<Subscription[]>(SUBSCRIPTIONS_KEY, (subscriptions) =>
          subscriptions?.filter((subscription) => subscription.groupId !== groupId),
        )
        // The articles and ranges of the group are gone; a kept list or jump would refer to them.
        queryClient.removeQueries({ queryKey: ['threads', groupId] })
        queryClient.removeQueries({ queryKey: ['jump', groupId] })
      }
      await queryClient.invalidateQueries({ queryKey: SUBSCRIPTIONS_KEY })
      return response.ok
    },
    [queryClient],
  )

  return { subscribe, unsubscribe }
}

/**
 * The time of the last successful synchronization of the group as the subscription list has it
 * now, or `null` when the group was never synchronized or the list is not loaded.
 */
export function lastSyncedAtOf(queryClient: QueryClient, groupId: number): number | null {
  const subscriptions = queryClient.getQueryData<Subscription[]>(SUBSCRIPTIONS_KEY)
  return (
    subscriptions?.find((subscription) => subscription.groupId === groupId)?.sync.lastSyncedAt ??
    null
  )
}
