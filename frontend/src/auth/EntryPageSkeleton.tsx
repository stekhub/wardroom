import { EntryPage } from '@/auth/EntryPage'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

/** Shown while the application asks the backend whether to show setup, login or itself. */
export function EntryPageSkeleton() {
  return (
    <EntryPage>
      <Card aria-busy="true" aria-label="Loading">
        <CardHeader>
          <Skeleton className="h-5 w-24" />
        </CardHeader>
        <CardContent className="grid gap-4">
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
        </CardContent>
      </Card>
    </EntryPage>
  )
}
