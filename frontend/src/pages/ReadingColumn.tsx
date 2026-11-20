import type { ReactNode } from 'react'

/**
 * The frame of a reading view (UI concept): one column of at most 768 px, centered in the area
 * next to the sidebar. The width stays the same whether the sidebar is collapsed or not.
 */
export function ReadingColumn({ children }: { children: ReactNode }) {
  return (
    <div className="p-6">
      <div className="mx-auto w-full max-w-3xl">{children}</div>
    </div>
  )
}
