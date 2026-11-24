import { useSyncExternalStore } from 'react'

/** What the owner chose in the settings section "Preferences" (spec 013). */
export type ThemeChoice = 'light' | 'dark' | 'system'

/** The theme the page is drawn in. */
export type Theme = 'light' | 'dark'

/** Where the choice is kept in the browser. `public/theme.js` reads the same key. */
export const THEME_STORAGE_KEY = 'wardroom.theme'

const DARK_QUERY = '(prefers-color-scheme: dark)'

interface ThemeState {
  choice: ThemeChoice
  theme: Theme
}

const listeners = new Set<() => void>()
let systemSetting: MediaQueryList | undefined
let state: ThemeState = { choice: 'system', theme: 'light' }

/** @returns the stored choice; System when nothing, something unknown or nothing readable is stored */
export function readThemeChoice(): ThemeChoice {
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY)
    return stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system'
  } catch {
    return 'system'
  }
}

/**
 * Takes over from `public/theme.js`, which set the theme before the page was drawn: reads the
 * choice and, while System is chosen, follows the setting of the operating system.
 *
 * @returns a function that stops following the setting of the operating system
 */
export function startTheme(): () => void {
  systemSetting = window.matchMedia(DARK_QUERY)
  systemSetting.addEventListener('change', apply)
  applyChoice(readThemeChoice())
  const followed = systemSetting
  return () => {
    followed.removeEventListener('change', apply)
  }
}

/** Applies a choice at once and keeps it in this browser. */
export function chooseTheme(choice: ThemeChoice) {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, choice)
  } catch {
    // The browser blocks storage: the choice applies until the page is reloaded.
  }
  applyChoice(choice)
}

/** The current choice and the theme it leads to. */
export function useTheme(): ThemeState {
  return useSyncExternalStore(subscribe, currentState)
}

function applyChoice(choice: ThemeChoice) {
  state = { ...state, choice }
  apply()
}

function apply() {
  const theme: Theme =
    state.choice === 'system' ? (systemSetting?.matches === true ? 'dark' : 'light') : state.choice
  document.documentElement.classList.toggle('dark', theme === 'dark')
  state = { ...state, theme }
  for (const listener of listeners) {
    listener()
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function currentState() {
  return state
}
