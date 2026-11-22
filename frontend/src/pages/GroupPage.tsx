import { CheckCheck, RefreshCw, SquarePen } from 'lucide-react'
import { toast } from 'sonner'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { api } from '@/api/client'
import { problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { DateView } from '@/groups/DateView'
import { endOfDay } from '@/groups/days'
import { GroupIcon } from '@/groups/GroupIcon'
import { JumpToDate } from '@/groups/JumpToDate'
import { SubscribeToggle } from '@/groups/SubscribeToggle'
import {
  type Subscription,
  useReloadSubscriptions,
  useSubscriptions,
} from '@/groups/subscriptionState'
import { ReadingColumn } from '@/pages/ReadingColumn'
import { ThreadList } from '@/threads/ThreadList'
import { DEFAULT_SORT, SORTS, useShownReadState } from '@/threads/threadState'

/**
 * The group view (UI concept): its header, with the large group icon, the name, the badges, the
 * subscribe toggle, the refresh button, the date picker (spec 005) and "Mark all as read"
 * (spec 004), and below it the threads of the group (spec 008). With `?until=YYYY-MM-DD` it is the
 * date view; otherwise `?sort=` chooses the order.
 */
export function GroupPage() {
  const groupId = Number(useParams().groupId)
  const [searchParams] = useSearchParams()
  const subscriptions = useSubscriptions()
  const day = searchParams.get('until')
  const until = day === null ? undefined : endOfDay(day)
  const sortInAddress = searchParams.get('sort')
  const sort = SORTS.find((candidate) => candidate === sortInAddress) ?? DEFAULT_SORT

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
      {day !== null && until !== undefined ? (
        <DateView key={day} groupId={groupId} day={day} until={until} />
      ) : (
        <ThreadList key={`${String(groupId)}:${sort}`} subscription={subscription} sort={sort} />
      )}
    </ReadingColumn>
  )
}

/** The group name comes from the server and is shown as text only. */
function GroupHeader({ subscription }: { subscription: Subscription }) {
  const navigate = useNavigate()
  const reloadSubscriptions = useReloadSubscriptions()
  const { showGroupRead } = useShownReadState()

  async function markAllRead() {
    const { response } = await api.POST('/api/groups/{groupId}/mark-all-read', {
      params: { path: { groupId: subscription.groupId } },
    })
    if (response.ok) {
      showGroupRead(subscription.groupId)
    } else if (response.status !== 401) {
      toast.error(UNEXPECTED_ERROR)
    }
    await reloadSubscriptions()
  }

  /** The synchronization runs in the background; the subscription list says how it goes. */
  async function synchronize() {
    const { error, response } = await api.POST('/api/groups/{groupId}/sync', {
      params: { path: { groupId: subscription.groupId } },
    })
    if (!response.ok && response.status !== 401) {
      toast.error(problemMessage(error, UNEXPECTED_ERROR))
    }
    await reloadSubscriptions()
  }

  const synchronizing = subscription.sync.running

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
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label={synchronizing ? 'Synchronizing' : 'Refresh'}
              onClick={() => void synchronize()}
            >
              <RefreshCw
                className={synchronizing ? 'animate-spin motion-reduce:animate-none' : undefined}
              />
            </Button>
          </TooltipTrigger>
          <TooltipContent>{synchronizing ? 'Synchronizing…' : 'Refresh'}</TooltipContent>
        </Tooltip>
        <JumpToDate groupId={subscription.groupId} />
        {subscription.postingStatus !== 'NOT_ALLOWED' && (
          <Button asChild variant="outline" size="sm">
            <Link to={`/groups/${String(subscription.groupId)}/new`}>
              <SquarePen />
              New discussion
            </Link>
          </Button>
        )}
        <Button type="button" variant="outline" size="sm" onClick={() => void markAllRead()}>
          <CheckCheck />
          Mark all as read
        </Button>
      </div>
    </header>
  )
}
