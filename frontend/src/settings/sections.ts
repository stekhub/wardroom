import { EyeOff, IdCard, Info, KeyRound, type LucideIcon, RefreshCw, Server } from 'lucide-react'

/** The last part of a section's URL, `/settings/{path}`. */
export type SettingsSectionPath =
  'server' | 'synchronization' | 'posting-identity' | 'ignored' | 'account' | 'about'

export type SettingsSection = {
  path: SettingsSectionPath
  title: string
  icon: LucideIcon
}

/** The sections of the settings page, in the order of the UI concept. */
export const SETTINGS_SECTIONS: readonly [SettingsSection, ...SettingsSection[]] = [
  { path: 'server', title: 'Server', icon: Server },
  { path: 'synchronization', title: 'Synchronization', icon: RefreshCw },
  { path: 'posting-identity', title: 'Posting identity', icon: IdCard },
  { path: 'ignored', title: 'Ignored', icon: EyeOff },
  { path: 'account', title: 'Account', icon: KeyRound },
  { path: 'about', title: 'About', icon: Info },
]
