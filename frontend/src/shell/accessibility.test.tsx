import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, expect, test } from 'vitest'
import { App } from '@/App'
import {
  backendLoggedIn,
  backendLoggedOut,
  testSubscription,
  testThreadArticle,
  testThreadCard,
  testThreadPage,
} from '@/test/backend'
import { server } from '@/test/server'

/** The width of a window in the tests, which is wide. */
const WIDE = window.innerWidth

afterEach(() => {
  window.innerWidth = WIDE
})

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

/** The application decides by the width of the window whether the mobile layout applies. */
function narrowScreen() {
  window.innerWidth = 375
}

/** A backend with the subscribed group 5 and one thread in it. */
function backendWithGroup() {
  backendLoggedIn()
  server.use(
    http.get('/api/subscriptions', () =>
      HttpResponse.json([testSubscription(5, 'example.talk', { unreadCount: 2 })]),
    ),
    http.get('/api/groups/:groupId/threads', () =>
      HttpResponse.json(testThreadPage([testThreadCard(1201, 'Hello there')])),
    ),
    http.get('/api/groups/:groupId/threads/:articleId', () =>
      HttpResponse.json({
        rootId: 1201,
        ignored: false,
        ignoreRuleIds: [],
        ignoredWord: null,
        articles: [
          testThreadArticle(1201, null, 0, { subject: 'Hello there' }),
          testThreadArticle(1202, 1201, 1, { from: 'Bob <bob@example.org>' }),
          testThreadArticle(1203, 1202, 2, {
            from: 'Eve <eve@example.org>',
            ignoredAuthor: true,
          }),
        ],
      }),
    ),
    http.get('/api/articles/:articleId/content', ({ params }) =>
      HttpResponse.json({
        articleId: Number(params.articleId),
        body: `Text of ${String(params.articleId)}`,
        flowed: false,
        delSp: false,
        truncated: false,
        omittedParts: [],
        rawHeaders: 'Subject: Hello',
      }),
    ),
  )
}

// Skip link and focus (spec 015)

test('"Skip to content" is the first element and moves the focus to the main area', async () => {
  backendWithGroup()
  openAt('/discover')
  await screen.findByRole('heading', { level: 1, name: 'Discover groups' })

  await userEvent.tab()

  const skipLink = screen.getByRole('link', { name: 'Skip to content' })
  expect(skipLink).toHaveFocus()

  await userEvent.keyboard('{Enter}')

  expect(screen.getByRole('main')).toHaveFocus()
  // The address is unchanged: the pages keep their state in it.
  expect(window.location.hash).toBe('')
})

test('the login page has "Skip to content" too', async () => {
  backendLoggedOut()
  openAt('/')
  await screen.findByRole('heading', { level: 1, name: 'Log in' })

  await userEvent.click(screen.getByRole('link', { name: 'Skip to content' }))

  expect(screen.getByRole('main')).toHaveFocus()
})

test('after a change of page, the focus is on the main heading of the new page', async () => {
  backendWithGroup()
  openAt('/discover')
  const firstHeading = await screen.findByRole('heading', { level: 1, name: 'Discover groups' })
  // The first page starts as the browser loads it.
  expect(firstHeading).not.toHaveFocus()

  await userEvent.click(await screen.findByRole('link', { name: /example\.talk/ }))

  await waitFor(() => {
    expect(screen.getByRole('heading', { level: 1, name: 'example.talk' })).toHaveFocus()
  })

  // The thread page loads first; the focus goes to its heading when it appears.
  await userEvent.click(await screen.findByRole('link', { name: /Hello there/ }))

  await waitFor(() => {
    expect(screen.getByRole('heading', { level: 1, name: 'Hello there' })).toHaveFocus()
  })
})

test('a change of the settings section moves the focus to the heading of the section', async () => {
  backendWithGroup()
  openAt('/discover')
  await screen.findByRole('heading', { level: 1, name: 'Discover groups' })

  await userEvent.click(screen.getByRole('link', { name: 'Settings' }))

  await waitFor(() => {
    expect(screen.getByRole('heading', { level: 1, name: 'Settings' })).toHaveFocus()
  })

  await userEvent.click(screen.getByRole('link', { name: 'Preferences' }))

  await waitFor(() => {
    expect(screen.getByRole('heading', { level: 2, name: 'Preferences' })).toHaveFocus()
  })
})

test('every page has one main heading and landmarks for the sidebar and the main area', async () => {
  backendWithGroup()
  openAt('/groups/5')
  await screen.findByRole('heading', { level: 1, name: 'example.talk' })

  expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  expect(screen.getAllByRole('main')).toHaveLength(1)
  expect(screen.getByRole('navigation', { name: 'Sidebar' })).toBeInTheDocument()
})

// Screen reader texts (spec 015)

test('the articles of a thread say their level', async () => {
  backendWithGroup()
  openAt('/groups/5/threads/1201')
  const list = await screen.findByRole('list', { name: 'Articles' })
  const [first, reply, replyToReply] = within(list).getAllByRole('listitem')
  if (first === undefined || reply === undefined || replyToReply === undefined) {
    throw new Error('The thread has fewer than three articles')
  }

  expect(within(first).getByText('Level 1')).toBeInTheDocument()
  expect(within(reply).getByText('Level 2')).toBeInTheDocument()
  // Also the one line of an article by an ignored author.
  expect(within(replyToReply).getByText('Level 3')).toBeInTheDocument()
  expect(within(replyToReply).getByText(/Ignored author/)).toBeVisible()
})

test('the unread count of a group is read out, not only shown', async () => {
  backendWithGroup()
  openAt('/discover')

  expect(await screen.findByLabelText('2 unread')).toBeVisible()
})

test('the start and the end of a synchronization are announced as a status message', async () => {
  backendLoggedIn()
  let sync: { running: boolean; error?: string } = { running: true }
  server.use(
    http.get('/api/subscriptions', () =>
      HttpResponse.json([testSubscription(5, 'example.talk', { sync })]),
    ),
    http.post('/api/groups/:groupId/sync', () => new HttpResponse(null, { status: 202 })),
  )
  openAt('/groups/5')
  await screen.findByRole('heading', { level: 1, name: 'example.talk' })
  const announcement = () => document.querySelector('[data-synchronization-announcer]')
  // A synchronization that runs when the page opens is not news.
  expect(announcement()).toBeEmptyDOMElement()

  sync = { running: false, error: 'The server did not answer in time' }
  // The button asks for the subscriptions again, as the regular poll does.
  await userEvent.click(screen.getByRole('button', { name: 'Synchronizing' }))

  await waitFor(() => {
    expect(announcement()).toHaveTextContent('Synchronization finished. It failed for example.talk')
  })

  sync = { running: true }
  await userEvent.click(screen.getByRole('button', { name: 'Refresh' }))

  await waitFor(() => {
    expect(announcement()).toHaveTextContent('Synchronizing')
  })
})

// Narrow screens (spec 015)

test('on a narrow screen the menu closes after a group was chosen', async () => {
  narrowScreen()
  backendWithGroup()
  openAt('/discover')
  await screen.findByRole('heading', { level: 1, name: 'Discover groups' })
  // The sidebar is not part of the page until the menu is opened.
  expect(screen.queryByRole('link', { name: /example\.talk/ })).not.toBeInTheDocument()

  await userEvent.click(screen.getByRole('button', { name: 'Open the menu' }))
  await userEvent.click(await screen.findByRole('link', { name: /example\.talk/ }))

  expect(await screen.findByRole('heading', { level: 1, name: 'example.talk' })).toBeVisible()
  await waitFor(() => {
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
  // The focus goes to the heading of the new page once the menu has closed.
  await waitFor(() => {
    expect(screen.getByRole('heading', { level: 1, name: 'example.talk' })).toHaveFocus()
  })
})

test('on a narrow screen the menu closes after "Discover groups" and the settings were chosen', async () => {
  narrowScreen()
  backendWithGroup()
  openAt('/groups/5')
  await screen.findByRole('heading', { level: 1, name: 'example.talk' })

  await userEvent.click(screen.getByRole('button', { name: 'Open the menu' }))
  await userEvent.click(await screen.findByRole('link', { name: 'Discover groups' }))

  expect(await screen.findByRole('heading', { level: 1, name: 'Discover groups' })).toBeVisible()
  await waitFor(() => {
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  await userEvent.click(screen.getByRole('button', { name: 'Open the menu' }))
  await userEvent.click(await screen.findByRole('link', { name: 'Settings' }))

  expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeVisible()
  await waitFor(() => {
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

test('the buttons that show only their icon on a narrow screen keep their names', async () => {
  narrowScreen()
  backendWithGroup()
  openAt('/groups/5')
  await screen.findByRole('heading', { level: 1, name: 'example.talk' })

  const markAllRead = screen.getByRole('button', { name: 'Mark all as read' })
  expect(screen.getByRole('link', { name: 'New discussion' })).toBeInTheDocument()
  // The label is in the page for screen readers and hidden on a narrow screen by its class.
  expect(within(markAllRead).getByText('Mark all as read')).toHaveClass('max-md:sr-only')

  await userEvent.hover(markAllRead)

  expect(await screen.findByRole('tooltip', { name: 'Mark all as read' })).toBeInTheDocument()
})

test('the buttons of the thread page keep their names on a narrow screen', async () => {
  narrowScreen()
  backendWithGroup()
  openAt('/groups/5/threads/1201')
  await screen.findByRole('list', { name: 'Articles' })

  expect(screen.getByRole('button', { name: 'Mark thread as read' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Ignore thread' })).toBeInTheDocument()
})
