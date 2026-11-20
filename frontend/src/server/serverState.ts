import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { api } from '@/api/client'
import type { components } from '@/api/schema'

export type Server = components['schemas']['Server']
export type ConnectionTestResult = components['schemas']['ConnectionTestResult']

export const SERVERS_KEY = ['servers'] as const

async function fetchServers(): Promise<Server[]> {
  const { data, response } = await api.GET('/api/servers')
  if (data === undefined) {
    throw new Error(`Unexpected answer ${String(response.status)} to the server request`)
  }
  return data
}

/**
 * The configured servers: none or one in the MVP (spec 002). Without a server the application
 * shows the server form instead of itself.
 */
export function useServers() {
  return useQuery({
    queryKey: SERVERS_KEY,
    queryFn: fetchServers,
    staleTime: Infinity,
    retry: false,
  })
}

/** Puts the servers in place after one was saved or removed, without asking the backend again. */
export function useSetServers() {
  const queryClient = useQueryClient()
  return useCallback(
    (servers: Server[]) => {
      queryClient.setQueryData<Server[]>(SERVERS_KEY, servers)
    },
    [queryClient],
  )
}

/** Asks the backend again, when its answer showed that the servers have changed. */
export function useRefreshServers() {
  const queryClient = useQueryClient()
  return useCallback(() => queryClient.invalidateQueries({ queryKey: SERVERS_KEY }), [queryClient])
}
