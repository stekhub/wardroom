import { toast } from 'sonner'
import { Skeleton } from '@/components/ui/skeleton'
import { IdentityForm } from '@/posting/IdentityForm'
import { usePostingIdentity } from '@/posting/postingState'
import { useServers } from '@/server/serverState'
import { SettingsSectionFrame } from '@/settings/SettingsSectionFrame'

/**
 * The settings section "Posting identity" (spec 009): the name, the email address and the
 * signature the owner posts with on the server.
 */
export function PostingIdentitySection() {
  const serverId = useServers().data?.[0]?.id
  const identity = usePostingIdentity(serverId)

  return (
    <SettingsSectionFrame title="Posting identity">
      <p className="text-sm text-muted-foreground">
        Your posts are sent with this name and email address. Reading needs no identity.
      </p>
      {identity.data === undefined || serverId === undefined ? (
        identity.isError ? (
          <p role="alert" className="text-sm text-destructive">
            The posting identity could not be loaded.
          </p>
        ) : (
          <div role="status" aria-label="Loading" className="grid max-w-md gap-4">
            <Skeleton className="h-14 w-full" />
            <Skeleton className="h-14 w-full" />
          </div>
        )
      ) : (
        <IdentityForm
          serverId={serverId}
          identity={identity.data}
          saveLabel="Save"
          onSaved={() => {
            toast.success('Posting identity saved')
          }}
        />
      )}
    </SettingsSectionFrame>
  )
}
