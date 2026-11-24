import { useSyncExternalStore } from 'react'

/** Where the setting "Keyboard shortcuts" is kept in the browser (spec 014). */
export const KEYBOARD_SHORTCUTS_STORAGE_KEY = 'wardroom.keyboardShortcuts'

const listeners = new Set<() => void>()
let enabled = readKeyboardShortcutsEnabled()

/** @returns the stored setting; on when nothing, something unknown or nothing readable is stored */
export function readKeyboardShortcutsEnabled(): boolean {
  try {
    return window.localStorage.getItem(KEYBOARD_SHORTCUTS_STORAGE_KEY) !== 'off'
  } catch {
    return true
  }
}

/** Turns the keys on or off at once and keeps the setting in this browser. */
export function setKeyboardShortcutsEnabled(on: boolean) {
  try {
    window.localStorage.setItem(KEYBOARD_SHORTCUTS_STORAGE_KEY, on ? 'on' : 'off')
  } catch {
    // The browser blocks storage: the setting applies until the page is reloaded.
  }
  enabled = on
  for (const listener of listeners) {
    listener()
  }
}

/** Reads the stored setting again, as when the page loads. */
export function reloadKeyboardShortcutsSetting() {
  enabled = readKeyboardShortcutsEnabled()
  for (const listener of listeners) {
    listener()
  }
}

/** Whether the keyboard shortcuts are on. */
export function useKeyboardShortcutsEnabled(): boolean {
  return useSyncExternalStore(subscribe, () => enabled)
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
