import type { ReactNode } from 'react'
import { Navigate, type RouteObject } from 'react-router'
import { AuthGate } from '@/auth/AuthGate'
import { DiscoverPage } from '@/pages/DiscoverPage'
import { GroupPage } from '@/pages/GroupPage'
import { LeavingPage } from '@/pages/LeavingPage'
import { NewDiscussionPage } from '@/pages/NewDiscussionPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { ThreadPage } from '@/pages/ThreadPage'
import { ServerGate } from '@/server/ServerGate'
import { AboutSection } from '@/settings/AboutSection'
import { AccountSection } from '@/settings/AccountSection'
import { IgnoredSection } from '@/settings/IgnoredSection'
import { PostingIdentitySection } from '@/settings/PostingIdentitySection'
import { PreferencesSection } from '@/settings/PreferencesSection'
import { SETTINGS_SECTIONS, type SettingsSectionPath } from '@/settings/sections'
import { ServerSection } from '@/settings/ServerSection'
import { SettingsPage } from '@/settings/SettingsPage'
import { SynchronizationSection } from '@/settings/SynchronizationSection'
import { AppShell } from '@/shell/AppShell'

/** The content of each settings section, by its path. */
const SECTION_CONTENT: Record<SettingsSectionPath, ReactNode> = {
  server: <ServerSection />,
  synchronization: <SynchronizationSection />,
  'posting-identity': <PostingIdentitySection />,
  ignored: <IgnoredSection />,
  preferences: <PreferencesSection />,
  account: <AccountSection />,
  about: <AboutSection />,
}

/**
 * Every view of the UI concept has its own URL. The views are added here by the implementation
 * steps that build them.
 */
export const routes: RouteObject[] = [
  {
    element: <AuthGate />,
    children: [
      // A link in an article opens this page in a new tab; it has no sidebar (spec 007).
      { path: 'leaving', element: <LeavingPage /> },
      {
        element: <ServerGate />,
        children: [
          {
            element: <AppShell />,
            children: [
              { index: true, element: <Navigate to="/discover" replace /> },
              { path: 'discover', element: <DiscoverPage /> },
              { path: 'groups/:groupId', element: <GroupPage /> },
              { path: 'groups/:groupId/new', element: <NewDiscussionPage /> },
              { path: 'groups/:groupId/threads/:articleId', element: <ThreadPage /> },
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
                    element: SECTION_CONTENT[section.path],
                  })),
                ],
              },
              { path: '*', element: <NotFoundPage /> },
            ],
          },
        ],
      },
    ],
  },
]
