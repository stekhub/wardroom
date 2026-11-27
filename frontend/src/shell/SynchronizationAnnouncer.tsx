import { useState } from 'react'
import { useSubscriptions } from '@/groups/subscriptionState'

/**
 * Says the state of the synchronization to screen readers, without moving the focus (spec 015).
 * The sidebar shows it as icons; this is the same as a status message: when a synchronization
 * starts, and when it ends, with the groups it failed for.
 */
export function SynchronizationAnnouncer() {
  const subscriptions = useSubscriptions().data
  const running = subscriptions?.some((subscription) => subscription.sync.running) === true
  // Not known until the subscriptions are loaded: what runs when the page opens is not news.
  const [wasRunning, setWasRunning] = useState<boolean | null>(null)
  const [message, setMessage] = useState('')

  if (subscriptions !== undefined && running !== wasRunning) {
    setWasRunning(running)
    if (wasRunning === null) {
      // The first answer only says where things stand.
    } else if (running) {
      setMessage('Synchronizing')
    } else {
      // Group names come from the server and are shown as text only.
      const failed = subscriptions
        .filter((subscription) => (subscription.sync.error ?? '') !== '')
        .map((subscription) => subscription.name)
      setMessage(
        failed.length === 0
          ? 'Synchronization finished'
          : `Synchronization finished. It failed for ${failed.join(', ')}`,
      )
    }
  }

  return (
    // A polite live region is read like a status message. It has no role of its own, so the
    // status messages of the pages stay the only ones a page has.
    <p aria-live="polite" data-synchronization-announcer="" className="sr-only">
      {message}
    </p>
  )
}
