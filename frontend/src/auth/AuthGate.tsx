import { Outlet } from 'react-router'
import { useAuthState } from '@/auth/authState'
import { BackendUnreachable } from '@/auth/BackendUnreachable'
import { EntryPageSkeleton } from '@/auth/EntryPageSkeleton'
import { LoginPage } from '@/auth/LoginPage'
import { SetupWizard } from '@/auth/SetupWizard'

/**
 * Decides what is shown at every URL (spec 001): the first-run wizard while no account exists,
 * the login page without a session, and the application otherwise. After a login the owner is at
 * the URL that was opened.
 */
export function AuthGate() {
  const authState = useAuthState()

  if (authState.isPending) {
    return <EntryPageSkeleton />
  }
  if (authState.isError) {
    return (
      <BackendUnreachable
        onRetry={() => void authState.refetch()}
        isRetrying={authState.isFetching}
      />
    )
  }
  switch (authState.data.status) {
    case 'setup':
      return <SetupWizard />
    case 'anonymous':
      return <LoginPage />
    case 'authenticated':
      return <Outlet />
  }
}
