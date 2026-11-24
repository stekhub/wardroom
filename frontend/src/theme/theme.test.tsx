import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { App } from '@/App'
import { backendLoggedIn } from '@/test/backend'
import page from '../../index.html?raw'
import themeScript from '../../public/theme.js?raw'
import { chooseTheme, startTheme, THEME_STORAGE_KEY } from '@/theme/themeState'

/** The setting of the operating system, which a test can change. */
class SystemSetting {
  matches = false
  private readonly listeners = new Set<() => void>()

  readonly media = {
    get matches() {
      return systemSetting.matches
    },
    addEventListener: (_type: string, listener: () => void) => this.listeners.add(listener),
    removeEventListener: (_type: string, listener: () => void) => this.listeners.delete(listener),
  }

  change(dark: boolean) {
    this.matches = dark
    for (const listener of this.listeners) {
      listener()
    }
  }
}

let systemSetting = new SystemSetting()
let stopTheme: () => void = () => undefined
const originalMatchMedia = window.matchMedia.bind(window)

/** Starts the theme as the page does when it loads, with a stored choice or none. */
function loadPage(storedChoice?: string) {
  stopTheme()
  if (storedChoice !== undefined) {
    window.localStorage.setItem(THEME_STORAGE_KEY, storedChoice)
  }
  stopTheme = startTheme()
}

function pageIsDark() {
  return document.documentElement.classList.contains('dark')
}

beforeEach(() => {
  systemSetting = new SystemSetting()
  window.matchMedia = () => systemSetting.media as unknown as MediaQueryList
  window.localStorage.clear()
  document.documentElement.classList.remove('dark')
})

afterEach(() => {
  stopTheme()
  stopTheme = () => undefined
  vi.restoreAllMocks()
  window.localStorage.clear()
  window.matchMedia = originalMatchMedia
  // Later tests start without a choice and in the light theme.
  loadPage()
  stopTheme()
  document.documentElement.classList.remove('dark')
})

test('choosing Light, Dark and System sets the theme and stores the choice', async () => {
  loadPage()
  backendLoggedIn()
  window.history.replaceState(null, '', '/settings/preferences')
  render(<App />)
  await screen.findByRole('heading', { name: 'Preferences' })
  expect(screen.getByRole('radio', { name: 'System' })).toBeChecked()

  await userEvent.click(screen.getByRole('radio', { name: 'Dark' }))
  expect(pageIsDark()).toBe(true)
  expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')

  await userEvent.click(screen.getByRole('radio', { name: 'Light' }))
  expect(pageIsDark()).toBe(false)
  expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')

  systemSetting.matches = true
  await userEvent.click(screen.getByRole('radio', { name: 'System' }))
  expect(pageIsDark()).toBe(true)
  expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('system')
})

test('with System, a change of the setting of the operating system changes the theme at once', () => {
  loadPage()
  expect(pageIsDark()).toBe(false)

  act(() => {
    systemSetting.change(true)
  })
  expect(pageIsDark()).toBe(true)

  act(() => {
    systemSetting.change(false)
  })
  expect(pageIsDark()).toBe(false)
})

test('a chosen theme does not follow the operating system', () => {
  loadPage('light')

  act(() => {
    systemSetting.change(true)
  })

  expect(pageIsDark()).toBe(false)
})

test('a stored choice is applied when the page loads', () => {
  loadPage('dark')

  expect(pageIsDark()).toBe(true)
})

test('an unknown stored value falls back to System', () => {
  systemSetting.matches = true

  loadPage('purple')

  expect(pageIsDark()).toBe(true)
})

test('a browser that blocks storage uses System, and a choice applies until the page is reloaded', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new DOMException('Blocked', 'SecurityError')
  })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('Blocked', 'SecurityError')
  })
  systemSetting.matches = true

  loadPage()
  expect(pageIsDark()).toBe(true)

  act(() => {
    chooseTheme('light')
  })
  expect(pageIsDark()).toBe(false)
})

test('the logo shows the variant of the theme', async () => {
  loadPage('dark')
  backendLoggedIn()
  window.history.replaceState(null, '', '/settings/about')
  render(<App />)

  const logo = await screen.findByRole('img', { name: 'Wardroom' })
  expect(logo.getAttribute('src')).toContain('wardroom-header-dark')

  act(() => {
    chooseTheme('light')
  })

  expect(screen.getByRole('img', { name: 'Wardroom' }).getAttribute('src')).not.toContain('dark')
})

test('the theme script sets the theme before the application starts', () => {
  expect(page.indexOf('<script src="/theme.js"></script>')).toBeGreaterThan(-1)
  expect(page.indexOf('<script src="/theme.js"></script>')).toBeLessThan(page.indexOf('</head>'))
  expect(page.indexOf('<script src="/theme.js"></script>')).toBeLessThan(
    page.indexOf('src="/src/main.tsx"'),
  )

  // The test runs the file exactly as the browser loads it, before any module of the application.
  // eslint-disable-next-line @typescript-eslint/no-implied-eval
  const runThemeScript = new Function(themeScript) as () => void

  window.localStorage.setItem(THEME_STORAGE_KEY, 'dark')
  runThemeScript()
  expect(pageIsDark()).toBe(true)

  document.documentElement.classList.remove('dark')
  window.localStorage.setItem(THEME_STORAGE_KEY, 'system')
  systemSetting.matches = true
  runThemeScript()
  expect(pageIsDark()).toBe(true)

  document.documentElement.classList.remove('dark')
  window.localStorage.setItem(THEME_STORAGE_KEY, 'light')
  runThemeScript()
  expect(pageIsDark()).toBe(false)
})
