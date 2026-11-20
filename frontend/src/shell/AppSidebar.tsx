import { Search, Settings } from 'lucide-react'
import { Link, useLocation } from 'react-router'
import logo from '@/assets/wardroom-logo.svg'
import { Button } from '@/components/ui/button'
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
  SidebarSeparator,
  SidebarTrigger,
} from '@/components/ui/sidebar'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'

/**
 * The sidebar of the UI concept: logo, name and settings button, the "Discover groups" button and
 * the subscribed groups. Collapsed, it keeps a narrow column of icons.
 */
export function AppSidebar() {
  const location = useLocation()

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <div className="flex items-center gap-1 group-data-[collapsible=icon]:flex-col">
          <Link
            to="/"
            className="flex min-w-0 flex-1 items-center gap-2 rounded-md p-1 outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring"
          >
            <img
              src={logo}
              alt=""
              className="h-auto w-12 shrink-0 group-data-[collapsible=icon]:w-6"
            />
            <span className="truncate text-base font-semibold group-data-[collapsible=icon]:hidden">
              Wardroom
            </span>
          </Link>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button variant="ghost" size="icon-sm" asChild>
                <Link to="/settings" aria-label="Settings">
                  <Settings />
                </Link>
              </Button>
            </TooltipTrigger>
            <TooltipContent>Settings</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger asChild>
              <SidebarTrigger />
            </TooltipTrigger>
            <TooltipContent>Collapse or expand the sidebar</TooltipContent>
          </Tooltip>
        </div>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              asChild
              tooltip="Discover groups"
              isActive={location.pathname.startsWith('/discover')}
            >
              <Link to="/discover">
                <Search />
                <span>Discover groups</span>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarSeparator />
      <SidebarContent>
        <SidebarGroup>
          {/* The subscribed groups are listed here once subscriptions exist (spec 004). */}
          <p className="px-2 py-1 text-sm text-muted-foreground group-data-[collapsible=icon]:hidden">
            No subscribed groups yet.
          </p>
        </SidebarGroup>
      </SidebarContent>
      <SidebarRail />
    </Sidebar>
  )
}
