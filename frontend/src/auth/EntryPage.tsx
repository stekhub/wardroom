import type { ReactNode } from 'react'
import { MAIN_CONTENT_ID } from '@/shell/pageFocus'
import { SkipLink } from '@/shell/SkipLink'
import { HeaderLogo } from '@/theme/WardroomLogo'

/**
 * The frame of the pages shown before the application itself: login and the first-run wizard.
 * They have no sidebar and show the logo above a centered card (UI concept).
 */
export function EntryPage({ children, wide = false }: { children: ReactNode; wide?: boolean }) {
  return (
    <main
      id={MAIN_CONTENT_ID}
      tabIndex={-1}
      className="flex min-h-svh flex-col items-center justify-center gap-8 bg-muted/40 p-6 outline-none"
    >
      <SkipLink />
      {/* 4.4rem keeps the lettering at the size it has in a 3rem high image of the original logo file. */}
      <HeaderLogo alt="Wardroom" className="h-[4.4rem] w-auto" />
      <div className={wide ? 'w-full max-w-md' : 'w-full max-w-sm'}>{children}</div>
    </main>
  )
}
