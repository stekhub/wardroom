import { Outlet } from 'react-router'
import { useAuthState } from '@/auth/authState'
import { EntryPage } from '@/auth/EntryPage'
import { EntryPageSkeleton } from '@/auth/EntryPageSkeleton'
import { LoginPage } from '@/auth/LoginPage'
import { SetupWizard } from '@/auth/SetupWizard'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

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
      <EntryPage>
        <Card>
          <CardHeader>
            <CardTitle>
              <h1>Wardroom cannot be reached</h1>
            </CardTitle>
          </CardHeader>
          <CardContent className="grid gap-4">
            <p role="alert" className="text-sm text-muted-foreground">
              The server did not answer as expected. Check that Wardroom is running.
            </p>
            <Button onClick={() => void authState.refetch()} disabled={authState.isFetching}>
              Try again
            </Button>
          </CardContent>
        </Card>
      </EntryPage>
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
