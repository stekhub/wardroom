import { Plus, Star } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
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
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { useSubscriptionChange } from '@/groups/subscriptionState'

interface SubscribeToggleProps {
  groupId: number
  /** Comes from the server and is shown as text only. */
  groupName: string
  subscribed: boolean
  /** Called after the group was unsubscribed. */
  onUnsubscribed?: () => void
}

/**
 * The subscribe toggle of the UI concept: an outlined plus when not subscribed, a filled star
 * when subscribed. Subscribing takes one click. Unsubscribing deletes data, so it asks first.
 */
export function SubscribeToggle({
  groupId,
  groupName,
  subscribed,
  onUnsubscribed,
}: SubscribeToggleProps) {
  const { subscribe, unsubscribe } = useSubscriptionChange()
  const [confirming, setConfirming] = useState(false)
  const label = subscribed ? `Unsubscribe from ${groupName}` : `Subscribe to ${groupName}`

  async function subscribeNow() {
    if (!(await subscribe(groupId))) {
      toast.error(`Could not subscribe to ${groupName}. Try again.`)
    }
  }

  async function unsubscribeNow() {
    if (await unsubscribe(groupId)) {
      toast.success(`Unsubscribed from ${groupName}`)
      onUnsubscribed?.()
    } else {
      toast.error(UNEXPECTED_ERROR)
    }
  }

  return (
    <>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label={label}
            aria-pressed={subscribed}
            onClick={() => {
              if (subscribed) {
                setConfirming(true)
              } else {
                void subscribeNow()
              }
            }}
          >
            {subscribed ? <Star className="fill-current text-warning" /> : <Plus />}
          </Button>
        </TooltipTrigger>
        <TooltipContent>{subscribed ? 'Unsubscribe' : 'Subscribe'}</TooltipContent>
      </Tooltip>
      <AlertDialog open={confirming} onOpenChange={setConfirming}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Unsubscribe from this group?</AlertDialogTitle>
            <AlertDialogDescription>
              This deletes the cached articles of <span className="break-all">{groupName}</span> and
              what you have read in it. It cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={() => void unsubscribeNow()}>
              Unsubscribe
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
