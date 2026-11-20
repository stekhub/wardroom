import { EntryPage } from '@/auth/EntryPage'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

/** Shown when the backend did not answer a request the application cannot start without. */
export function BackendUnreachable({
  onRetry,
  isRetrying,
}: {
  onRetry: () => void
  isRetrying: boolean
}) {
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
          <Button onClick={onRetry} disabled={isRetrying}>
            Try again
          </Button>
        </CardContent>
      </Card>
    </EntryPage>
  )
}
