import type { ReactNode } from 'react'

/** The frame every settings section shares: its title and, below it, its content. */
export function SettingsSectionFrame({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-labelledby="settings-section-title" className="grid gap-6">
      <h2 id="settings-section-title" className="text-base font-medium">
        {title}
      </h2>
      {children}
    </section>
  )
}
