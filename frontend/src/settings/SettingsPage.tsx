import { useQueryClient } from '@tanstack/react-query'
import { LogOut } from 'lucide-react'
import { NavLink, Outlet } from 'react-router'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { UNEXPECTED_ERROR } from '@/api/problem'
import { AUTH_STATE_KEY, type AuthState } from '@/auth/authState'
import { PageHeader } from '@/pages/PageHeader'
import { SETTINGS_SECTIONS } from '@/settings/sections'

const navigationEntry =
  'flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm outline-none transition-colors hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring [&_svg]:size-4 [&_svg]:shrink-0'

/**
 * The settings page of the UI concept: a section navigation on the left, the selected section on
 * the right. The section is part of the URL. "Log out" is an action at the end, not a section.
 */
export function SettingsPage() {
  const queryClient = useQueryClient()

  async function logOut() {
    const { response } = await api.POST('/api/auth/logout')
    if (response.ok || response.status === 401) {
      // The login page is shown, and nothing that was loaded for the account stays in the browser's memory.
      queryClient.setQueryData<AuthState>(AUTH_STATE_KEY, { status: 'anonymous' })
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== AUTH_STATE_KEY[0] })
      return
    }
    toast.error(UNEXPECTED_ERROR)
  }

  return (
    <div className="p-6">
      <PageHeader title="Settings" />
      <div className="flex flex-col gap-8 md:flex-row">
        <nav aria-label="Settings sections" className="shrink-0 md:w-52">
          <ul className="grid gap-1">
            {SETTINGS_SECTIONS.map((section) => (
              <li key={section.path}>
                <NavLink
                  to={`/settings/${section.path}`}
                  className={({ isActive }) =>
                    `${navigationEntry} ${isActive ? 'bg-muted font-medium' : 'text-muted-foreground hover:text-foreground'}`
                  }
                >
                  <section.icon />
                  {section.title}
                </NavLink>
              </li>
            ))}
            <li className="mt-2 border-t pt-3">
              <button
                type="button"
                className={`${navigationEntry} text-muted-foreground hover:text-foreground`}
                onClick={() => void logOut()}
              >
                <LogOut />
                Log out
              </button>
            </li>
          </ul>
        </nav>
        <div className="max-w-xl min-w-0 flex-1">
          <Outlet />
        </div>
      </div>
    </div>
  )
}
