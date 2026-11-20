import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { api } from '@/api/client'

/**
 * What the application shows depends on this state (spec 001):
 * - `setup`: no account exists yet; only the first-run wizard is shown.
 * - `anonymous`: an account exists but this browser is not logged in; the login page is shown.
 * - `authenticated`: the application itself is shown.
 */
export type AuthState =
  { status: 'setup' } | { status: 'anonymous' } | { status: 'authenticated'; username: string }

export const AUTH_STATE_KEY = ['auth-state'] as const

async function fetchAuthState(): Promise<AuthState> {
  // The logged-in case is the usual one, so it is asked first and needs one request only.
  const account = await api.GET('/api/auth/me')
  if (account.data !== undefined) {
    return { status: 'authenticated', username: account.data.username }
  }
  if (account.response.status !== 401) {
    throw new Error(`Unexpected answer ${String(account.response.status)} to the account request`)
  }
  const setup = await api.GET('/api/auth/setup')
  if (setup.response.ok) {
    return { status: 'setup' }
  }
  if (setup.response.status === 404) {
    return { status: 'anonymous' }
  }
  throw new Error(`Unexpected answer ${String(setup.response.status)} to the setup request`)
}

export function useAuthState() {
  return useQuery({
    queryKey: AUTH_STATE_KEY,
    queryFn: fetchAuthState,
    staleTime: Infinity,
    retry: false,
  })
}

/**
 * Shows the login page after the backend has ended the session, and forgets everything that was
 * loaded for the account.
 */
export function useShowLoginPage() {
  const queryClient = useQueryClient()
  return useCallback(() => {
    queryClient.setQueryData<AuthState>(AUTH_STATE_KEY, { status: 'anonymous' })
    queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== AUTH_STATE_KEY[0] })
  }, [queryClient])
}

/** Asks the backend again, after a login or a session that ended. */
export function useRefreshAuthState() {
  const queryClient = useQueryClient()
  return useCallback(
    () => queryClient.invalidateQueries({ queryKey: AUTH_STATE_KEY }),
    [queryClient],
  )
}
