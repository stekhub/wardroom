import { Navigate, type RouteObject } from 'react-router'
import { AuthGate } from '@/auth/AuthGate'
import { DiscoverPage } from '@/pages/DiscoverPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { AccountSection } from '@/settings/AccountSection'
import { SETTINGS_SECTIONS } from '@/settings/sections'
import { EmptySettingsSection } from '@/settings/SettingsSectionFrame'
import { SettingsPage } from '@/settings/SettingsPage'
import { AppShell } from '@/shell/AppShell'

/**
 * Every view of the UI concept has its own URL. The views are added here by the implementation
 * steps that build them.
 */
export const routes: RouteObject[] = [
  {
    element: <AuthGate />,
    children: [
      {
        element: <AppShell />,
        children: [
          { index: true, element: <Navigate to="/discover" replace /> },
          { path: 'discover', element: <DiscoverPage /> },
          {
            path: 'settings',
            element: <SettingsPage />,
            children: [
              {
                index: true,
                element: <Navigate to={`/settings/${SETTINGS_SECTIONS[0].path}`} replace />,
              },
              ...SETTINGS_SECTIONS.map((section) => ({
                path: section.path,
                element:
                  section.path === 'account' ? (
                    <AccountSection />
                  ) : (
                    <EmptySettingsSection title={section.title} />
                  ),
              })),
            ],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]
