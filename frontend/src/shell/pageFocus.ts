import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router'
import { useSidebar } from '@/components/ui/sidebar'

/** The ID of the main area of every page; "Skip to content" leads there. */
export const MAIN_CONTENT_ID = 'main-content'

/** How long a page may take to show its heading and still get the focus there. */
const HEADING_WAIT_MILLIS = 10_000

/**
 * After a change of page, moves the focus to the main heading of the new page, so a screen reader
 * announces it (spec 015). A change of the settings section moves it to the heading of the section.
 * A page that loads first gets the focus when its heading appears, unless the owner has moved the
 * focus meanwhile.
 */
export function useFocusOnPageChange() {
  const { pathname } = useLocation()
  const previousPathname = useRef<string | null>(null)

  useEffect(() => {
    const before = previousPathname.current
    previousPathname.current = pathname
    const main = document.getElementById(MAIN_CONTENT_ID)
    // The first page starts at the top, as every page a browser loads.
    if (before === null || main === null) {
      return
    }
    const withinSettings = before.startsWith('/settings/') && pathname.startsWith('/settings/')
    const focusedAtChange = document.activeElement

    function focusHeading(): boolean {
      // The menu of a narrow screen keeps the focus inside while it closes; the heading waits for it.
      if (document.querySelector('[data-slot="sidebar"][data-mobile="true"]') !== null) {
        return false
      }
      const heading = main?.querySelector<HTMLElement>(withinSettings ? 'h2' : 'h1')
      if (heading == null) {
        return false
      }
      const focused = document.activeElement
      // The owner has moved into the page meanwhile: the focus stays where they put it.
      const movedIntoPage =
        focused !== null && focused !== focusedAtChange && main?.contains(focused) === true
      if (!movedIntoPage) {
        heading.tabIndex = -1
        heading.focus({ preventScroll: true })
      }
      return true
    }

    if (focusHeading()) {
      return
    }
    const observer = new MutationObserver(() => {
      if (focusHeading()) {
        observer.disconnect()
      }
    })
    // The whole page is watched: the menu of a narrow screen is outside the main area.
    observer.observe(document.body, { childList: true, subtree: true })
    const timer = window.setTimeout(() => {
      observer.disconnect()
    }, HEADING_WAIT_MILLIS)
    return () => {
      observer.disconnect()
      window.clearTimeout(timer)
    }
  }, [pathname])
}

/**
 * On a narrow screen the sidebar opens over the page as a menu. It closes when a group or another
 * page was chosen (spec 015).
 */
export function useCloseMenuOnPageChange() {
  const { pathname } = useLocation()
  const { setOpenMobile } = useSidebar()

  useEffect(() => {
    setOpenMobile(false)
  }, [pathname, setOpenMobile])
}
