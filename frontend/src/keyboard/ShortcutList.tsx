import { SHORTCUTS } from '@/keyboard/shortcuts'

/** The keys and what they do (spec 014). */
export function ShortcutList() {
  return (
    <dl className="grid grid-cols-[auto_1fr] items-center gap-x-4 gap-y-2 text-sm">
      {SHORTCUTS.map((shortcut) => (
        <div key={shortcut.keys} className="contents">
          <dt>
            <kbd className="inline-flex min-w-7 justify-center rounded-md border bg-muted px-1.5 py-0.5 font-mono text-xs">
              {shortcut.keys}
            </kbd>
          </dt>
          <dd className="text-muted-foreground">{shortcut.description}</dd>
        </div>
      ))}
    </dl>
  )
}
