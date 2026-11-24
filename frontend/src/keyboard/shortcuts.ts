import { useEffect, useRef } from 'react'
import { useKeyboardShortcutsEnabled } from '@/keyboard/shortcutSetting'

/** The keys of spec 014, as `KeyboardEvent.key` names them. */
export type ShortcutKey = 'n' | ' ' | 'j' | 'k' | 'r' | 'c' | '?'

/** The keys as the dialog of `?` and the section "Preferences" list them. */
export const SHORTCUTS: readonly { keys: string; description: string }[] = [
  { keys: 'N', description: 'Next unread article' },
  { keys: 'Space', description: 'Scroll down; at the end, next unread article' },
  { keys: 'J', description: 'Next article' },
  { keys: 'K', description: 'Previous article' },
  { keys: 'R', description: 'Reply to the current article' },
  { keys: 'C', description: 'Mark the thread as read and go on' },
  { keys: '?', description: 'Show the keys' },
]

const SHORTCUT_KEYS: readonly string[] = ['n', ' ', 'j', 'k', 'r', 'c', '?']

/** Elements that take typed text. */
const TEXT_FIELDS = 'input, textarea, select, [contenteditable]:not([contenteditable="false"])'

/** Elements that act on Space themselves. */
const CONTROLS =
  'button, a[href], summary, [role="button"], [role="radio"], [role="checkbox"], [role="switch"], [role="tab"], [role="menuitem"]'

/** A dialog or a menu is open; its keys belong to it. */
const OPEN_LAYERS = '[role="dialog"], [role="alertdialog"], [role="menu"]'

/**
 * The key the event stands for, or `null` when it is no shortcut or must not trigger one: while
 * the owner types in a field, while a dialog or menu is open, or with a modifier key held (spec
 * 014). Shift only counts for `?`, which needs it on most keyboards. Space on a button or link
 * presses it, as it always does. A key that is held counts once, so holding it does not run through
 * the articles and mark them all as read; held Space scrolls as the browser does.
 */
export function shortcutOf(event: KeyboardEvent): ShortcutKey | null {
  if (event.defaultPrevented || event.repeat || event.ctrlKey || event.altKey || event.metaKey) {
    return null
  }
  const key = event.key === '?' ? '?' : event.key.length === 1 ? event.key.toLowerCase() : ''
  if (!SHORTCUT_KEYS.includes(key) || (event.shiftKey && key !== '?')) {
    return null
  }
  const target = event.target instanceof Element ? event.target : null
  if (target?.closest(TEXT_FIELDS) != null || document.querySelector(OPEN_LAYERS) !== null) {
    return null
  }
  if (key === ' ' && target?.closest(CONTROLS) != null) {
    return null
  }
  return key as ShortcutKey
}

export type ShortcutHandlers = Partial<Record<ShortcutKey, () => void>>

/**
 * Calls the handler of a key the owner pressed, while the setting "Keyboard shortcuts" is on. The
 * handlers are read when the key is pressed, so they can change with every render.
 */
export function useShortcuts(handlers: ShortcutHandlers) {
  const enabled = useKeyboardShortcutsEnabled()
  const current = useRef(handlers)
  useEffect(() => {
    current.current = handlers
  })
  useEffect(() => {
    if (!enabled) {
      return
    }
    function pressed(event: KeyboardEvent) {
      const key = shortcutOf(event)
      const handler = key === null ? undefined : current.current[key]
      if (handler !== undefined) {
        event.preventDefault()
        handler()
      }
    }
    window.addEventListener('keydown', pressed)
    return () => {
      window.removeEventListener('keydown', pressed)
    }
  }, [enabled])
}

/** Smooth scrolling, unless the owner asked the system for less motion. */
export function scrollBehavior(): ScrollBehavior {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
}
