import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { api } from '@/api/client'
import type { components } from '@/api/schema'

export type Group = components['schemas']['Group']
export type GroupPage = components['schemas']['GroupPage']
export type GroupListRefreshStatus = components['schemas']['GroupListRefreshStatus']

/** The group list page shows this many groups (spec 003). */
export const GROUPS_PER_PAGE = 50

/** How often the status is asked for while a refresh is running. */
const RUNNING_REFRESH_POLL_MILLIS = 1000

function groupsKey(serverId: number) {
  return ['groups', serverId] as const
}

function refreshStatusKey(serverId: number) {
  return ['group-list-refresh', serverId] as const
}

async function fetchGroupPage(serverId: number, query: string, page: number): Promise<GroupPage> {
  const { data, response } = await api.GET('/api/servers/{serverId}/groups', {
    params: { path: { serverId }, query: { q: query, page, size: GROUPS_PER_PAGE } },
  })
  if (data === undefined) {
    throw new Error(`Unexpected answer ${String(response.status)} to the group list request`)
  }
  return data
}

async function fetchRefreshStatus(serverId: number): Promise<GroupListRefreshStatus> {
  const { data, response } = await api.GET('/api/servers/{serverId}/groups/refresh', {
    params: { path: { serverId } },
  })
  if (data === undefined) {
    throw new Error(`Unexpected answer ${String(response.status)} to the refresh status request`)
  }
  return data
}

/**
 * One page of the server's groups, searched by name and description. While another page or
 * another search is loading, the groups shown before stay in place.
 */
export function useGroupPage(serverId: number, query: string, page: number) {
  return useQuery({
    queryKey: [...groupsKey(serverId), query, page],
    queryFn: () => fetchGroupPage(serverId, query, page),
    placeholderData: keepPreviousData,
    retry: false,
  })
}

/**
 * Whether a refresh of the group list is running, and how the last one ended. While one is
 * running, the status is asked for again every second, so the progress is shown.
 */
export function useGroupListRefreshStatus(serverId: number) {
  return useQuery({
    queryKey: refreshStatusKey(serverId),
    queryFn: () => fetchRefreshStatus(serverId),
    refetchInterval: (query) =>
      query.state.data?.running === true ? RUNNING_REFRESH_POLL_MILLIS : false,
    retry: false,
  })
}

/** Loads the groups again, after a refresh has changed them. */
export function useReloadGroups(serverId: number) {
  const queryClient = useQueryClient()
  return useCallback(
    () => queryClient.invalidateQueries({ queryKey: groupsKey(serverId) }),
    [queryClient, serverId],
  )
}

/** Asks for the refresh status again, after a refresh was requested. */
export function useReloadRefreshStatus(serverId: number) {
  const queryClient = useQueryClient()
  return useCallback(
    () => queryClient.invalidateQueries({ queryKey: refreshStatusKey(serverId) }),
    [queryClient, serverId],
  )
}
