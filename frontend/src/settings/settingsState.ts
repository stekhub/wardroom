import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { api } from '@/api/client'
import type { components } from '@/api/schema'

export type Settings = components['schemas']['Settings']

export const SETTINGS_KEY = ['settings'] as const

async function fetchSettings(): Promise<Settings> {
  const { data, response } = await api.GET('/api/settings')
  if (data === undefined) {
    throw new Error(`Unexpected answer ${String(response.status)} to the settings request`)
  }
  return data
}

/** The instance settings (spec 005, spec 022). Several settings sections show a part of them. */
export function useSettings() {
  return useQuery({ queryKey: SETTINGS_KEY, queryFn: fetchSettings, retry: false })
}

/**
 * Saves a part of the settings. The backend takes all settings at once, so the values of the other
 * sections are sent as they were loaded last.
 */
export function useSaveSettings() {
  const queryClient = useQueryClient()
  return useCallback(
    async (loaded: Settings, changed: Partial<Settings>) => {
      const current = queryClient.getQueryData<Settings>(SETTINGS_KEY) ?? loaded
      const answer = await api.PUT('/api/settings', {
        body: {
          syncIntervalMinutes: current.syncIntervalMinutes,
          initialDepth: current.initialDepth,
          groupListIntervalHours: current.groupListIntervalHours,
          cacheLimitEnabled: current.cacheLimitEnabled,
          maxCacheSizeGb: current.maxCacheSizeGb,
          hideBinaryGroups: current.hideBinaryGroups,
          ...changed,
        },
      })
      if (answer.data !== undefined) {
        queryClient.setQueryData<Settings>(SETTINGS_KEY, answer.data)
      }
      return answer
    },
    [queryClient],
  )
}
