import { CloudOff, LoaderCircle, TriangleAlert } from 'lucide-react'
import { Link, useLocation } from 'react-router'
import {
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSkeleton,
} from '@/components/ui/sidebar'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { GroupIcon } from '@/groups/GroupIcon'
import { type Subscription, useSubscriptions } from '@/groups/subscriptionState'

const COUNT = new Intl.NumberFormat('en')

/** Rows of the placeholder that stands in for the list while it loads. */
const SKELETON_ROWS = 3

/**
 * The subscribed groups in the sidebar (spec 004), by name: group icon, name, status icons and
 * the unread count. With the sidebar collapsed, only the group icons stay.
 */
export function SubscriptionList() {
  const subscriptions = useSubscriptions()
  const location = useLocation()

  if (subscriptions.data === undefined) {
    if (subscriptions.isError) {
      return (
        <p className="px-2 py-1 text-sm text-destructive group-data-[collapsible=icon]:hidden">
          The subscribed groups could not be loaded.
        </p>
      )
    }
    return (
      <SidebarMenu aria-hidden="true">
        {Array.from({ length: SKELETON_ROWS }, (_, row) => (
          <SidebarMenuItem key={row}>
            <SidebarMenuSkeleton showIcon />
          </SidebarMenuItem>
        ))}
      </SidebarMenu>
    )
  }
  if (subscriptions.data.length === 0) {
    return (
      <p className="px-2 py-1 text-sm text-muted-foreground group-data-[collapsible=icon]:hidden">
        No subscribed groups yet.
      </p>
    )
  }
  return (
    <SidebarMenu aria-label="Subscribed groups">
      {subscriptions.data.map((subscription) => (
        <SidebarMenuItem key={subscription.groupId}>
          <SidebarMenuButton
            asChild
            tooltip={subscription.name}
            isActive={location.pathname === `/groups/${String(subscription.groupId)}`}
            className="group-data-[collapsible=icon]:p-1.5! [&_[data-symbol]_svg]:size-3"
          >
            <Link to={`/groups/${String(subscription.groupId)}`}>
              <GroupIcon name={subscription.name} description={subscription.description} />
              <span className="min-w-0 flex-1 truncate" title={subscription.name}>
                {subscription.name}
              </span>
              <StatusIcons subscription={subscription} />
              {subscription.unreadCount > 0 && (
                <span
                  className="shrink-0 text-xs font-medium tabular-nums"
                  aria-label={`${COUNT.format(subscription.unreadCount)} unread`}
                >
                  {COUNT.format(subscription.unreadCount)}
                </span>
              )}
            </Link>
          </SidebarMenuButton>
        </SidebarMenuItem>
      ))}
    </SidebarMenu>
  )
}

/** Synchronization running, last synchronization failed (with the error), removed from the server. */
function StatusIcons({ subscription }: { subscription: Subscription }) {
  const error = subscription.sync.error ?? ''
  return (
    <>
      {subscription.sync.running && (
        <StatusIcon label="Synchronizing">
          <LoaderCircle className="animate-spin text-muted-foreground" />
        </StatusIcon>
      )}
      {!subscription.sync.running && error !== '' && (
        <StatusIcon label={`The last synchronization failed: ${error}`}>
          <TriangleAlert className="text-destructive" />
        </StatusIcon>
      )}
      {subscription.removed && (
        <StatusIcon label="The server no longer has this group">
          <CloudOff className="text-muted-foreground" />
        </StatusIcon>
      )}
    </>
  )
}

/** The error text comes from the backend and is shown as text only. */
function StatusIcon({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span role="img" aria-label={label} className="shrink-0">
          {children}
        </span>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  )
}
