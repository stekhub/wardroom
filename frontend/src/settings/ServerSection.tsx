import { toast } from 'sonner'
import { api } from '@/api/client'
import { UNEXPECTED_ERROR } from '@/api/problem'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { ServerForm } from '@/server/ServerForm'
import { type Server, useServers, useSetServers } from '@/server/serverState'
import { SettingsSectionFrame } from '@/settings/SettingsSectionFrame'

/** Removing the server deletes data, so it asks first (UI concept). */
function RemoveServerButton({ server }: { server: Server }) {
  const setServers = useSetServers()

  async function removeServer() {
    const { response } = await api.DELETE('/api/servers/{serverId}', {
      params: { path: { serverId: server.id } },
    })
    if (response.ok || response.status === 404) {
      toast.success('Server removed')
      // Without a server the server form is shown again (spec 002).
      setServers([])
      return
    }
    if (response.status !== 401) {
      toast.error(UNEXPECTED_ERROR)
    }
  }

  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button type="button" variant="ghost" className="text-destructive">
          Remove server
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Remove this server?</AlertDialogTitle>
          <AlertDialogDescription>
            This deletes the server and everything that belongs to it: its groups, your
            subscriptions, the cached articles and what you have read. It cannot be undone.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={() => void removeServer()}>
            Remove server
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

/**
 * The settings section "Server": the server form with the connection test, and the removal
 * (spec 002). Changing the server keeps groups, subscriptions, cache and read state.
 */
export function ServerSection() {
  const setServers = useSetServers()
  const server = useServers().data?.[0]

  if (server === undefined) {
    return null
  }
  return (
    <SettingsSectionFrame title="Server">
      <ServerForm
        server={server}
        saveLabel="Save"
        onSaved={(saved) => {
          setServers([saved.server])
          if (saved.connectionTest.postingServerFailure != null) {
            toast.warning('Settings saved, but the posting server cannot be used.')
          } else if (saved.connectionTest.success) {
            toast.success('Settings saved')
          } else {
            toast.warning('Settings saved, but the connection test failed.')
          }
        }}
      >
        <RemoveServerButton server={server} />
      </ServerForm>
    </SettingsSectionFrame>
  )
}
