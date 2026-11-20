import { RefreshCw, TriangleAlert } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import {
  useGroupListRefreshStatus,
  useReloadGroups,
  useReloadRefreshStatus,
} from '@/groups/groupListState'
import { cn } from '@/lib/utils'
import { useRefreshServers, useServers } from '@/server/serverState'

const COUNT = new Intl.NumberFormat('en')
const TIME = new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeStyle: 'short' })

/**
 * The state of the group list of a server and the button that refreshes it (spec 003): whether
 * a refresh is running and how many groups it has received, when the list was last refreshed,
 * and why the last refresh failed. A refresh never blocks the page (UI concept).
 */
export function GroupListRefresh({ serverId }: { serverId: number }) {
  const status = useGroupListRefreshStatus(serverId).data
  const reloadGroups = useReloadGroups(serverId)
  const reloadRefreshStatus = useReloadRefreshStatus(serverId)
  const refreshServers = useRefreshServers()
  const running = status?.running === true
  // The backend sends null where the generated types say that the field is absent.
  const lastError = status?.error ?? ''

  // When a refresh ends, the list on the page is out of date.
  const wasRunning = useRef(false)
  useEffect(() => {
    if (wasRunning.current && !running) {
      void reloadGroups()
    }
    wasRunning.current = running
  }, [running, reloadGroups])

  // A list that was never fetched is fetched when the page is opened, without a click. Not after
  // a failure and not while the login is known not to work: then the owner decides.
  const credentialsInvalid =
    useServers().data?.find((server) => server.id === serverId)?.credentialsInvalid === true
  const neverFetched =
    status !== undefined && !running && (status.refreshedAt ?? 0) === 0 && lastError === ''
  const fetchedOnOpening = useRef(false)
  useEffect(() => {
    if (neverFetched && !credentialsInvalid && !fetchedOnOpening.current) {
      fetchedOnOpening.current = true
      void requestRefresh()
    }
  })

  async function requestRefresh() {
    const { error, response } = await api.POST('/api/servers/{serverId}/groups/refresh', {
      params: { path: { serverId } },
    })
    if (response.status === 202) {
      void reloadRefreshStatus()
    } else if (response.status === 409) {
      // The login of the server does not work; the notice on every view leads to the settings.
      toast.error(problemMessage(error, UNEXPECTED_ERROR))
      void refreshServers()
    } else if (response.status !== 401) {
      toast.error(UNEXPECTED_ERROR)
    }
  }

  return (
    <div className="flex items-center gap-2 text-sm text-muted-foreground">
      <span role="status">{statusText(status)}</span>
      {status !== undefined && !running && lastError !== '' && (
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              className="text-destructive"
              aria-label={`The last refresh failed: ${lastError}`}
            >
              <TriangleAlert />
            </Button>
          </TooltipTrigger>
          <TooltipContent>The last refresh failed: {lastError}</TooltipContent>
        </Tooltip>
      )}
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            type="button"
            variant="outline"
            size="icon"
            aria-label="Refresh the group list"
            disabled={running}
            onClick={() => void requestRefresh()}
          >
            <RefreshCw className={cn(running && 'animate-spin motion-reduce:animate-none')} />
          </Button>
        </TooltipTrigger>
        <TooltipContent>Refresh the group list</TooltipContent>
      </Tooltip>
    </div>
  )
}

function statusText(status: ReturnType<typeof useGroupListRefreshStatus>['data']): string {
  if (status === undefined) {
    return ''
  }
  if (status.running) {
    const received = status.groupsReceived
    return `Fetching the group list… ${COUNT.format(received)} ${received === 1 ? 'group' : 'groups'} received`
  }
  const refreshedAt = status.refreshedAt ?? 0
  return refreshedAt === 0 ? 'Not fetched yet' : `Last refreshed ${TIME.format(refreshedAt)}`
}
