import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { App } from '@/App'
import type { components } from '@/api/schema'
import {
  KEYBOARD_SHORTCUTS_STORAGE_KEY,
  reloadKeyboardShortcutsSetting,
  setKeyboardShortcutsEnabled,
} from '@/keyboard/shortcutSetting'
import {
  backendLoggedIn,
  testSubscription,
  testThreadArticle,
  testThreadCard,
  testThreadPage,
} from '@/test/backend'
import { server } from '@/test/server'

type Subscription = components['schemas']['Subscription']
type ThreadArticle = components['schemas']['ThreadArticle']
type ThreadCard = components['schemas']['ThreadCard']

interface Reading {
  subscriptions?: Subscription[]
  cards?: ThreadCard[]
  /** The articles of each thread, by its root. */
  threads?: Record<number, ThreadArticle[]>
  /** The answer of "next unread" to the parameters it was asked with: a thread, or none. */
  nextUnread?: (parameters: URLSearchParams) => number | null
}

/**
 * A backend with the subscribed group 5 and the threads of a test. It records what the keys asked
 * for: "next unread", read and unread articles and threads marked as read.
 */
function backendForReading(reading: Reading = {}) {
  backendLoggedIn()
  const state = {
    nextUnreadRequests: [] as URLSearchParams[],
    readIds: [] as number[],
    unreadIds: [] as number[],
    threadsMarkedRead: [] as number[],
  }
  const threads = reading.threads ?? {}
  server.use(
    http.get('/api/subscriptions', () =>
      HttpResponse.json(
        reading.subscriptions ?? [testSubscription(5, 'example.talk', { unreadCount: 3 })],
      ),
    ),
    http.get('/api/groups/:groupId/threads', () =>
      HttpResponse.json(testThreadPage(reading.cards ?? [])),
    ),
    // Before the thread itself, whose path would take "next-unread" for an article ID.
    http.get('/api/groups/:groupId/threads/next-unread', ({ request }) => {
      const parameters = new URL(request.url).searchParams
      state.nextUnreadRequests.push(parameters)
      const next = reading.nextUnread?.(parameters) ?? null
      return next === null
        ? new HttpResponse(null, { status: 204 })
        : HttpResponse.json({ threadArticleId: next })
    }),
    http.get('/api/groups/:groupId/threads/:articleId', ({ params }) => {
      const rootId = Number(params.articleId)
      return HttpResponse.json({
        rootId,
        ignored: false,
        ignoreRuleIds: [],
        articles: threads[rootId] ?? [testThreadArticle(rootId, null, 0)],
      })
    }),
    http.post('/api/groups/:groupId/threads/:articleId/mark-read', ({ params }) => {
      state.threadsMarkedRead.push(Number(params.articleId))
      return new HttpResponse(null, { status: 204 })
    }),
    http.get('/api/articles/:articleId/content', ({ params }) =>
      HttpResponse.json({
        articleId: Number(params.articleId),
        body: `Text of ${String(params.articleId)}`,
        flowed: false,
        delSp: false,
        truncated: false,
        omittedParts: [],
        rawHeaders: 'Subject: Hello\r\nNewsgroups: example.talk\r\n',
      }),
    ),
    http.put('/api/articles/:articleId/read', ({ params }) => {
      state.readIds.push(Number(params.articleId))
      return new HttpResponse(null, { status: 204 })
    }),
    http.delete('/api/articles/:articleId/read', ({ params }) => {
      state.unreadIds.push(Number(params.articleId))
      return new HttpResponse(null, { status: 204 })
    }),
    http.get('/api/servers/:serverId/identity', () =>
      HttpResponse.json({ name: 'Jane Doe', email: 'jane@example.org', signature: null }),
    ),
  )
  return state
}

/** A thread of four articles: 1201 and 1203 are read, 1202 and 1204 are not. */
const FOUR_ARTICLES: ThreadArticle[] = [
  testThreadArticle(1201, null, 0),
  testThreadArticle(1202, 1201, 1, { read: false, from: 'Bob <bob@example.org>' }),
  testThreadArticle(1203, 1201, 1),
  testThreadArticle(1204, 1203, 2, { read: false }),
]

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

function rowOf(articleId: number) {
  const row = document.querySelector(`[data-article-id="${String(articleId)}"]`)
  if (row === null) {
    throw new Error(`No article ${String(articleId)} on the page`)
  }
  return row
}

/** The elements that were scrolled into view, in order. */
let scrolledTo: Element[] = []

/** An observer that never sees anything, so the text of an article is only displayed by a key. */
class NothingOnScreen {
  observe() {
    // Nothing scrolls into view by itself.
  }
  unobserve() {
    // See observe.
  }
  disconnect() {
    // See observe.
  }
}

function nothingScrollsIntoViewByItself() {
  vi.stubGlobal('IntersectionObserver', NothingOnScreen)
}

beforeEach(() => {
  scrolledTo = []
  vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(function (this: Element) {
    scrolledTo.push(this)
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  Reflect.deleteProperty(document.documentElement, 'scrollHeight')
  window.localStorage.clear()
  reloadKeyboardShortcutsSetting()
})

// N

test('N in the group view opens the first unread thread at its first unread article', async () => {
  nothingScrollsIntoViewByItself()
  const backend = backendForReading({
    cards: [testThreadCard(1201, 'Hello', { unreadCount: 2 })],
    threads: { 1201: FOUR_ARTICLES },
    nextUnread: () => 1201,
  })
  openAt('/groups/5')
  await screen.findByRole('link', { name: 'Hello' })

  await userEvent.keyboard('n')

  expect(await screen.findByRole('list', { name: 'Articles' })).toBeVisible()
  expect(window.location.pathname).toBe('/groups/5/threads/1201')
  expect(window.location.search).toBe('?article=1202')
  expect(backend.nextUnreadRequests[0]?.get('sort')).toBe('activity')
  expect(backend.nextUnreadRequests[0]?.has('after')).toBe(false)
  await waitFor(() => {
    expect(scrolledTo).toContain(rowOf(1202))
  })
  expect(rowOf(1202)).toHaveClass('bg-primary/10')
})

test('the address ?article= scrolls to the article and highlights it', async () => {
  nothingScrollsIntoViewByItself()
  backendForReading({ threads: { 1201: FOUR_ARTICLES } })
  openAt('/groups/5/threads/1201?article=1203')

  await screen.findByRole('list', { name: 'Articles' })

  await waitFor(() => {
    expect(scrolledTo).toContain(rowOf(1203))
  })
  expect(rowOf(1203)).toHaveClass('bg-primary/10')
  expect(rowOf(1201)).not.toHaveClass('bg-primary/10')
})

test('N on the thread page goes to the next unread article, marks it read at once and then opens the next thread', async () => {
  nothingScrollsIntoViewByItself()
  const backend = backendForReading({
    threads: { 1201: FOUR_ARTICLES, 1300: [testThreadArticle(1300, null, 0, { read: false })] },
    nextUnread: (parameters) => (parameters.get('after') === '1201' ? 1300 : null),
  })
  openAt('/groups/5/threads/1201')
  await screen.findByRole('list', { name: 'Articles' })

  await userEvent.keyboard('n')
  expect(scrolledTo.at(-1)).toBe(rowOf(1202))
  await waitFor(() => {
    expect(backend.readIds).toEqual([1202])
  })

  await userEvent.keyboard('n')
  expect(scrolledTo.at(-1)).toBe(rowOf(1204))
  await waitFor(() => {
    expect(backend.readIds).toEqual([1202, 1204])
  })

  await userEvent.keyboard('n')
  await waitFor(() => {
    expect(window.location.pathname).toBe('/groups/5/threads/1300')
  })
  expect(window.location.search).toBe('?article=1300')
  expect(backend.nextUnreadRequests[0]?.get('sort')).toBe('activity')
})

test('N follows the sort order of the list the thread was opened from', async () => {
  const backend = backendForReading({ cards: [testThreadCard(1201, 'Hello')] })
  openAt('/groups/5?sort=newest')
  await userEvent.click(await screen.findByRole('link', { name: 'Hello' }))
  await screen.findByRole('list', { name: 'Articles' })

  await userEvent.keyboard('n')

  await waitFor(() => {
    expect(backend.nextUnreadRequests.length).toBeGreaterThan(0)
  })
  expect(backend.nextUnreadRequests[0]?.get('sort')).toBe('newest')
  expect(backend.nextUnreadRequests[0]?.get('after')).toBe('1201')
})

test('at the end of a group, N opens the next group with unread articles, starting again at the top', async () => {
  backendForReading({
    subscriptions: [
      testSubscription(4, 'example.aaa', { unreadCount: 2 }),
      testSubscription(5, 'example.talk'),
      testSubscription(6, 'example.zoo'),
    ],
  })
  openAt('/groups/6')
  await screen.findByRole('heading', { name: 'example.zoo' })

  await userEvent.keyboard('n')

  expect(await screen.findByRole('heading', { name: 'example.aaa' })).toBeVisible()
  expect(window.location.pathname).toBe('/groups/4')
})

test('N says so when no group has unread articles', async () => {
  backendForReading({ subscriptions: [testSubscription(5, 'example.talk')] })
  openAt('/groups/5')
  await screen.findByRole('heading', { name: 'example.talk' })

  await userEvent.keyboard('n')

  expect(await screen.findByText('No unread articles')).toBeVisible()
  expect(window.location.pathname).toBe('/groups/5')
})

test('at the end of the last thread, N goes to the unread articles above the current one', async () => {
  nothingScrollsIntoViewByItself()
  const backend = backendForReading({
    subscriptions: [testSubscription(5, 'example.talk', { unreadCount: 2 })],
    threads: { 1201: FOUR_ARTICLES },
    // Nothing after the thread; from the top, the thread itself.
    nextUnread: (parameters) => (parameters.has('after') ? null : 1201),
  })
  openAt('/groups/5/threads/1201?article=1204')
  await screen.findByRole('list', { name: 'Articles' })

  await userEvent.keyboard('n')

  await waitFor(() => {
    expect(scrolledTo.at(-1)).toBe(rowOf(1202))
  })
  expect(window.location.pathname).toBe('/groups/5/threads/1201')
  expect(screen.queryByText('No unread articles')).not.toBeInTheDocument()
  await waitFor(() => {
    expect(backend.readIds).toEqual([1202])
  })
})

// Space

test('Space scrolls the thread page by a screen and, at its end, goes on as N', async () => {
  nothingScrollsIntoViewByItself()
  const scrolledBy = vi.spyOn(window, 'scrollBy')
  backendForReading({ threads: { 1201: FOUR_ARTICLES } })
  openAt('/groups/5/threads/1201')
  await screen.findByRole('list', { name: 'Articles' })
  Object.defineProperty(document.documentElement, 'scrollHeight', {
    configurable: true,
    value: 10 * window.innerHeight,
  })

  await userEvent.keyboard(' ')
  expect(scrolledBy).toHaveBeenCalledTimes(1)
  expect(scrolledTo).toEqual([])

  Object.defineProperty(document.documentElement, 'scrollHeight', {
    configurable: true,
    value: window.innerHeight,
  })
  await userEvent.keyboard(' ')
  expect(scrolledBy).toHaveBeenCalledTimes(1)
  expect(scrolledTo.at(-1)).toBe(rowOf(1202))
})

test('Space in the group view goes on as N', async () => {
  const backend = backendForReading({ cards: [testThreadCard(1201, 'Hello', { unreadCount: 1 })] })
  openAt('/groups/5')
  await screen.findByRole('link', { name: 'Hello' })

  await userEvent.keyboard(' ')

  await waitFor(() => {
    expect(backend.nextUnreadRequests).toHaveLength(1)
  })
})

// J and K

test('J and K go to the next and the previous article on the thread page', async () => {
  nothingScrollsIntoViewByItself()
  const backend = backendForReading({ threads: { 1201: FOUR_ARTICLES } })
  openAt('/groups/5/threads/1201')
  await screen.findByRole('list', { name: 'Articles' })

  await userEvent.keyboard('j')
  expect(scrolledTo.at(-1)).toBe(rowOf(1202))
  await userEvent.keyboard('j')
  expect(scrolledTo.at(-1)).toBe(rowOf(1203))
  await userEvent.keyboard('k')
  expect(scrolledTo.at(-1)).toBe(rowOf(1202))

  // The article a key reached is read; 1203 was read before.
  await waitFor(() => {
    expect(backend.readIds).toEqual([1202])
  })
})

test('J and K move the focus from card to card in the group view, and Enter opens the card', async () => {
  backendForReading({
    cards: [testThreadCard(1201, 'First'), testThreadCard(1300, 'Second')],
  })
  openAt('/groups/5')
  await screen.findByRole('link', { name: 'First' })

  await userEvent.keyboard('j')
  expect(screen.getByRole('link', { name: 'First' })).toHaveFocus()
  await userEvent.keyboard('j')
  expect(screen.getByRole('link', { name: 'Second' })).toHaveFocus()
  await userEvent.keyboard('k')
  expect(screen.getByRole('link', { name: 'First' })).toHaveFocus()

  await userEvent.keyboard('{Enter}')
  await waitFor(() => {
    expect(window.location.pathname).toBe('/groups/5/threads/1201')
  })
})

// R

test('R opens the reply editor below the current article', async () => {
  backendForReading({ threads: { 1201: [testThreadArticle(1201, null, 0)] } })
  openAt('/groups/5/threads/1201')
  await screen.findByText('Text of 1201')

  await userEvent.keyboard('r')

  const reply = await screen.findByRole('form', { name: 'Reply' })
  const text = within(reply).getByRole('textbox', { name: 'Text' })
  expect(text).toHaveFocus()
  // The key itself is not typed into the editor.
  expect((text as HTMLTextAreaElement).value).toContain('Text of 1201')
  expect((text as HTMLTextAreaElement).value).not.toMatch(/r$/)
})

test('R does nothing where replying is not possible', async () => {
  backendForReading({
    subscriptions: [testSubscription(5, 'example.talk', { postingStatus: 'NOT_ALLOWED' })],
    threads: { 1201: [testThreadArticle(1201, null, 0)] },
  })
  openAt('/groups/5/threads/1201')
  await screen.findByText('Text of 1201')

  await userEvent.keyboard('r')

  expect(screen.queryByRole('form', { name: 'Reply' })).not.toBeInTheDocument()
})

// C

test('C marks the thread as read and opens the next thread', async () => {
  nothingScrollsIntoViewByItself()
  const backend = backendForReading({
    threads: { 1201: FOUR_ARTICLES },
    nextUnread: (parameters) => (parameters.get('after') === '1201' ? 1300 : null),
  })
  openAt('/groups/5/threads/1201')
  await screen.findByRole('list', { name: 'Articles' })

  await userEvent.keyboard('c')

  await waitFor(() => {
    expect(window.location.pathname).toBe('/groups/5/threads/1300')
  })
  expect(backend.threadsMarkedRead).toEqual([1201])
})

test('C in the group view marks the thread of the card with the focus as read and goes on', async () => {
  const backend = backendForReading({
    cards: [
      testThreadCard(1201, 'First', { unreadCount: 1 }),
      testThreadCard(1300, 'Second', { unreadCount: 1 }),
    ],
    nextUnread: () => 1300,
  })
  openAt('/groups/5')
  await screen.findByRole('link', { name: 'First' })

  // Without a card with the focus, C does nothing.
  await userEvent.keyboard('c')
  expect(backend.threadsMarkedRead).toEqual([])

  await userEvent.keyboard('j')
  await userEvent.keyboard('c')

  await waitFor(() => {
    expect(window.location.pathname).toBe('/groups/5/threads/1300')
  })
  expect(backend.threadsMarkedRead).toEqual([1201])
})

// ?

test('? shows the list of keys and Escape closes it', async () => {
  backendForReading()
  openAt('/groups/5')
  await screen.findByRole('heading', { name: 'example.talk' })

  await userEvent.keyboard('?')

  const dialog = await screen.findByRole('dialog', { name: 'Keyboard shortcuts' })
  expect(within(dialog).getByText('Next unread article')).toBeVisible()
  expect(within(dialog).getByText('Reply to the current article')).toBeVisible()

  await userEvent.keyboard('{Escape}')
  await waitFor(() => {
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

// When the keys do nothing

test('the keys do nothing with a modifier key held or with the setting turned off', async () => {
  const backend = backendForReading({ cards: [testThreadCard(1201, 'Hello', { unreadCount: 1 })] })
  openAt('/groups/5')
  await screen.findByRole('link', { name: 'Hello' })

  await userEvent.keyboard('{Control>}n{/Control}')
  await userEvent.keyboard('{Alt>}n{/Alt}')
  await userEvent.keyboard('{Shift>}N{/Shift}')
  act(() => {
    setKeyboardShortcutsEnabled(false)
  })
  await userEvent.keyboard('n')
  await userEvent.keyboard('?')
  expect(backend.nextUnreadRequests).toHaveLength(0)
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(window.localStorage.getItem(KEYBOARD_SHORTCUTS_STORAGE_KEY)).toBe('off')

  act(() => {
    setKeyboardShortcutsEnabled(true)
  })
  await userEvent.keyboard('n')
  await waitFor(() => {
    expect(backend.nextUnreadRequests).toHaveLength(1)
  })
})

test('a key that is held counts once', async () => {
  nothingScrollsIntoViewByItself()
  const backend = backendForReading({ threads: { 1201: FOUR_ARTICLES } })
  openAt('/groups/5/threads/1201')
  await screen.findByRole('list', { name: 'Articles' })

  await userEvent.keyboard('j')
  fireEvent.keyDown(document.body, { key: 'j', repeat: true })
  fireEvent.keyDown(document.body, { key: 'n', repeat: true })

  expect(scrolledTo).toEqual([rowOf(1202)])
  await waitFor(() => {
    expect(backend.readIds).toEqual([1202])
  })
})

test('the keys do nothing while the owner types in a field', async () => {
  const backend = backendForReading({ threads: { 1201: [testThreadArticle(1201, null, 0)] } })
  openAt('/groups/5/threads/1201')
  await screen.findByText('Text of 1201')
  await userEvent.click(screen.getByRole('button', { name: 'Reply' }))
  const text = within(await screen.findByRole('form', { name: 'Reply' })).getByRole('textbox', {
    name: 'Text',
  })

  await userEvent.type(text, 'n jkrc?')

  expect((text as HTMLTextAreaElement).value).toContain('n jkrc?')
  expect(backend.nextUnreadRequests).toHaveLength(0)
  expect(backend.threadsMarkedRead).toEqual([])
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

test('a stored setting turns the keys off when the page loads', async () => {
  window.localStorage.setItem(KEYBOARD_SHORTCUTS_STORAGE_KEY, 'off')
  reloadKeyboardShortcutsSetting()
  const backend = backendForReading({ cards: [testThreadCard(1201, 'Hello', { unreadCount: 1 })] })
  openAt('/groups/5')
  await screen.findByRole('link', { name: 'Hello' })

  await userEvent.keyboard('n')

  expect(backend.nextUnreadRequests).toHaveLength(0)
})

test('the section "Preferences" turns the keys on and off and lists them', async () => {
  backendForReading()
  openAt('/settings/preferences')
  const setting = await screen.findByRole('checkbox', { name: 'Keyboard shortcuts' })
  expect(setting).toBeChecked()
  expect(screen.getByText('Next unread article')).toBeVisible()

  await userEvent.click(setting)

  expect(setting).not.toBeChecked()
  expect(window.localStorage.getItem(KEYBOARD_SHORTCUTS_STORAGE_KEY)).toBe('off')
  await userEvent.click(setting)
  expect(window.localStorage.getItem(KEYBOARD_SHORTCUTS_STORAGE_KEY)).toBe('on')
})

// Mark as unread

test('an article marked as unread stays unread while the page is open, until a key reaches it', async () => {
  const backend = backendForReading({
    threads: {
      1201: [
        testThreadArticle(1201, null, 0),
        testThreadArticle(1202, 1201, 1, { from: 'Bob <bob@example.org>' }),
      ],
    },
  })
  openAt('/groups/5/threads/1201')
  await screen.findByText('Text of 1202')
  const article = screen.getByRole('article', { name: 'Article by Bob' })
  expect(within(article).queryByText('new')).not.toBeInTheDocument()
  // Displaying an article marks it as read, also when it was read before.
  await waitFor(() => {
    expect(backend.readIds).toHaveLength(2)
  })
  backend.readIds.length = 0

  await userEvent.click(within(article).getByRole('button', { name: 'More actions' }))
  await userEvent.click(await screen.findByRole('menuitem', { name: 'Mark as unread' }))

  await waitFor(() => {
    expect(backend.unreadIds).toEqual([1202])
  })
  expect(within(article).getByText('new')).toBeVisible()
  // Displaying its text again does not mark it as read.
  await userEvent.click(within(article).getByRole('button', { name: 'Collapse' }))
  await userEvent.click(within(article).getByRole('button', { name: 'Expand' }))
  await screen.findByText('Text of 1202')
  expect(backend.readIds).toEqual([])
  // A read article offers "Mark as unread"; this one does not any more.
  await userEvent.click(within(article).getByRole('button', { name: 'More actions' }))
  expect(screen.queryByRole('menuitem', { name: 'Mark as unread' })).not.toBeInTheDocument()
  await userEvent.keyboard('{Escape}')

  await userEvent.keyboard('j')

  // The key marks it as read; its text, displayed again, may ask for the same.
  await waitFor(() => {
    expect(backend.readIds).toContain(1202)
  })
  expect(new Set(backend.readIds)).toEqual(new Set([1202]))
})

test('an article that was deleted meanwhile reloads the thread when it is marked as unread', async () => {
  const backend = backendForReading({
    threads: {
      1201: [
        testThreadArticle(1201, null, 0),
        testThreadArticle(1202, 1201, 1, { from: 'Bob <bob@example.org>' }),
      ],
    },
  })
  let threadRequests = 0
  server.use(
    http.delete('/api/articles/:articleId/read', () =>
      HttpResponse.json(
        {
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: 'This article does not exist.',
        },
        { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
    http.get('/api/groups/:groupId/threads/:articleId', () => {
      threadRequests++
      return HttpResponse.json({
        rootId: 1201,
        ignored: false,
        ignoreRuleIds: [],
        articles: threadRequests === 1 ? FOUR_ARTICLES.slice(0, 2) : FOUR_ARTICLES.slice(0, 1),
      })
    }),
  )
  openAt('/groups/5/threads/1201')
  const article = await screen.findByRole('article', { name: 'Article by Bob' })
  await screen.findByText('Text of 1202')

  await userEvent.click(within(article).getByRole('button', { name: 'More actions' }))
  await userEvent.click(await screen.findByRole('menuitem', { name: 'Mark as unread' }))

  await waitFor(() => {
    expect(screen.queryByRole('article', { name: 'Article by Bob' })).not.toBeInTheDocument()
  })
  expect(backend.unreadIds).toEqual([])
})
