import { EyeOff, IdCard, Info, KeyRound, type LucideIcon, RefreshCw, Server } from 'lucide-react'

export type SettingsSection = {
  /** The last part of the section's URL, `/settings/{path}`. */
  path: string
  title: string
  icon: LucideIcon
}

/**
 * The sections of the settings page, in the order of the UI concept. A section without content
 * yet is filled by the implementation step that builds its feature.
 */
export const SETTINGS_SECTIONS: readonly [SettingsSection, ...SettingsSection[]] = [
  { path: 'server', title: 'Server', icon: Server },
  { path: 'synchronization', title: 'Synchronization', icon: RefreshCw },
  { path: 'posting-identity', title: 'Posting identity', icon: IdCard },
  { path: 'ignored', title: 'Ignored', icon: EyeOff },
  { path: 'account', title: 'Account', icon: KeyRound },
  { path: 'about', title: 'About', icon: Info },
]
