import { useState } from 'react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { ShortcutList } from '@/keyboard/ShortcutList'
import { useShortcuts } from '@/keyboard/shortcuts'

/** The list of keys, opened with `?` anywhere in the application and closed with Escape. */
export function ShortcutsDialog() {
  const [open, setOpen] = useState(false)
  useShortcuts({
    '?': () => {
      setOpen(true)
    },
  })

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Keyboard shortcuts</DialogTitle>
          <DialogDescription>
            In a group and in a discussion. They can be turned off in the settings, section
            "Preferences".
          </DialogDescription>
        </DialogHeader>
        <ShortcutList />
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              setOpen(false)
            }}
          >
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
