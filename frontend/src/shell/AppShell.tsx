import { Outlet } from 'react-router'
import { SidebarInset, SidebarProvider, SidebarTrigger } from '@/components/ui/sidebar'
import { AppSidebar } from '@/shell/AppSidebar'
import { CredentialsNotice } from '@/shell/CredentialsNotice'

/** The sidebar component remembers in this cookie whether the owner collapsed it. */
function sidebarWasCollapsed(): boolean {
  return document.cookie.split('; ').includes('sidebar_state=false')
}

/** The frame of the application once logged in: the sidebar and, next to it, the current view. */
export function AppShell() {
  return (
    <SidebarProvider defaultOpen={!sidebarWasCollapsed()}>
      <AppSidebar />
      <SidebarInset>
        {/* In a narrow window the sidebar is hidden and opens over the page with this button. */}
        <div className="p-2 md:hidden">
          <SidebarTrigger />
        </div>
        <CredentialsNotice />
        <Outlet />
      </SidebarInset>
    </SidebarProvider>
  )
}
