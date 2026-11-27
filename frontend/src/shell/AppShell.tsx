import { Outlet, ScrollRestoration } from 'react-router'
import { SidebarInset, SidebarProvider, SidebarTrigger } from '@/components/ui/sidebar'
import { ShortcutsDialog } from '@/keyboard/ShortcutsDialog'
import { AppSidebar } from '@/shell/AppSidebar'
import { CredentialsNotice } from '@/shell/CredentialsNotice'
import { MAIN_CONTENT_ID, useCloseMenuOnPageChange, useFocusOnPageChange } from '@/shell/pageFocus'
import { SkipLink } from '@/shell/SkipLink'
import { SynchronizationAnnouncer } from '@/shell/SynchronizationAnnouncer'

/** The sidebar component remembers in this cookie whether the owner collapsed it. */
function sidebarWasCollapsed(): boolean {
  return document.cookie.split('; ').includes('sidebar_state=false')
}

/** The frame of the application once logged in: the sidebar and, next to it, the current view. */
export function AppShell() {
  return (
    <SidebarProvider defaultOpen={!sidebarWasCollapsed()}>
      <SkipLink />
      <AppSidebar />
      <MainArea />
    </SidebarProvider>
  )
}

/** The main area. It is a component of its own because closing the menu needs the sidebar state. */
function MainArea() {
  useFocusOnPageChange()
  useCloseMenuOnPageChange()

  return (
    <SidebarInset id={MAIN_CONTENT_ID} tabIndex={-1} className="min-w-0 outline-none">
      {/* In a narrow window the sidebar is hidden and opens over the page with this button. */}
      <div className="p-2 md:hidden">
        <SidebarTrigger aria-label="Open the menu" />
      </div>
      <CredentialsNotice />
      <Outlet />
      {/* A new view opens at the top; going back returns to where the view was left. */}
      <ScrollRestoration />
      {/* The list of keys, opened with `?` on every page (spec 014). */}
      <ShortcutsDialog />
      {/* The synchronization status as a status message for screen readers (spec 015). */}
      <SynchronizationAnnouncer />
    </SidebarInset>
  )
}
