import { Outlet } from 'react-router'
import { BackendUnreachable } from '@/auth/BackendUnreachable'
import { EntryPageSkeleton } from '@/auth/EntryPageSkeleton'
import { useServers } from '@/server/serverState'
import { ServerSetupPage } from '@/server/ServerSetupPage'

/**
 * While no server exists, every URL shows the server form: directly after the account was
 * created, at the next login when the browser was closed in between, and after the server was
 * removed (spec 002). With a server, the application is shown.
 */
export function ServerGate() {
  const servers = useServers()

  if (servers.isPending) {
    return <EntryPageSkeleton />
  }
  if (servers.isError) {
    return (
      <BackendUnreachable onRetry={() => void servers.refetch()} isRetrying={servers.isFetching} />
    )
  }
  if (servers.data.length === 0) {
    return <ServerSetupPage />
  }
  return <Outlet />
}
