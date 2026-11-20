import { CheckCheck } from 'lucide-react'
import { toast } from 'sonner'
import { Link, useNavigate, useParams } from 'react-router'
import { api } from '@/api/client'
import { UNEXPECTED_ERROR } from '@/api/problem'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { GroupIcon } from '@/groups/GroupIcon'
import { SubscribeToggle } from '@/groups/SubscribeToggle'
import {
  type Subscription,
  useReloadSubscriptions,
  useSubscriptions,
} from '@/groups/subscriptionState'
import { ReadingColumn } from '@/pages/ReadingColumn'

/**
 * The group view (UI concept): for now its header, with the large group icon, the name, the
 * badges, the subscribe toggle and "Mark all as read" (spec 004). The threads of the group are
 * added by the discussion view.
 */
export function GroupPage() {
  const groupId = Number(useParams().groupId)
  const subscriptions = useSubscriptions()

  if (subscriptions.data === undefined) {
    if (subscriptions.isError) {
      return (
        <ReadingColumn>
          <p className="text-sm text-destructive">The group could not be loaded.</p>
        </ReadingColumn>
      )
    }
    return (
      <ReadingColumn>
        <div aria-hidden="true" className="flex items-center gap-4">
          <Skeleton className="size-16 rounded-full" />
          <Skeleton className="h-6 w-64 max-w-full" />
        </div>
      </ReadingColumn>
    )
  }
  const subscription = subscriptions.data.find((candidate) => candidate.groupId === groupId)
  if (subscription === undefined) {
    return (
      <ReadingColumn>
        <h1 className="mb-2 text-xl font-semibold tracking-tight">Not a subscribed group</h1>
        <p className="text-sm text-muted-foreground">
          You are not subscribed to this group. Find it in{' '}
          <Link to="/discover" className="font-medium text-foreground underline underline-offset-4">
            Discover groups
          </Link>{' '}
          and subscribe to it.
        </p>
      </ReadingColumn>
    )
  }
  return (
    <ReadingColumn>
      <GroupHeader subscription={subscription} />
      <p className="text-sm text-muted-foreground">No articles yet.</p>
    </ReadingColumn>
  )
}

/** The group name comes from the server and is shown as text only. */
function GroupHeader({ subscription }: { subscription: Subscription }) {
  const navigate = useNavigate()
  const reloadSubscriptions = useReloadSubscriptions()

  async function markAllRead() {
    const { response } = await api.POST('/api/groups/{groupId}/mark-all-read', {
      params: { path: { groupId: subscription.groupId } },
    })
    if (!response.ok && response.status !== 401) {
      toast.error(UNEXPECTED_ERROR)
    }
    await reloadSubscriptions()
  }

  return (
    <header className="mb-6 flex flex-wrap items-center gap-4">
      <GroupIcon name={subscription.name} description={subscription.description} size="large" />
      <div className="min-w-0 flex-1">
        <h1 className="truncate text-xl font-semibold tracking-tight" title={subscription.name}>
          {subscription.name}
        </h1>
        <div className="mt-1 flex flex-wrap gap-2 empty:hidden">
          {subscription.postingStatus === 'MODERATED' && <Badge variant="outline">Moderated</Badge>}
          {subscription.postingStatus === 'NOT_ALLOWED' && (
            <Badge variant="outline">No posting</Badge>
          )}
          {subscription.removed && <Badge variant="outline">Removed from the server</Badge>}
        </div>
      </div>
      <div className="flex items-center gap-2">
        <SubscribeToggle
          groupId={subscription.groupId}
          groupName={subscription.name}
          subscribed
          onUnsubscribed={() => {
            // Without a subscription the group has no view (spec 004).
            void navigate('/discover')
          }}
        />
        <Button type="button" variant="outline" size="sm" onClick={() => void markAllRead()}>
          <CheckCheck />
          Mark all as read
        </Button>
      </div>
    </header>
  )
}
