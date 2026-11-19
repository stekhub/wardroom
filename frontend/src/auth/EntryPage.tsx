import type { ReactNode } from 'react'
import headerLogo from '@/assets/wardroom-header.svg'

/**
 * The frame of the pages shown before the application itself: login and the first-run wizard.
 * They have no sidebar and show the logo above a centered card (UI concept).
 */
export function EntryPage({ children }: { children: ReactNode }) {
  return (
    <main className="flex min-h-svh flex-col items-center justify-center gap-8 bg-muted/40 p-6">
      {/* 4.4rem keeps the lettering at the size it has in a 3rem high image of the original logo file. */}
      <img src={headerLogo} alt="Wardroom" className="h-[4.4rem] w-auto" />
      <div className="w-full max-w-sm">{children}</div>
    </main>
  )
}
