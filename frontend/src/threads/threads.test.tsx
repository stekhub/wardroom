import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import type { components } from '@/api/schema'
import {
  backendLoggedIn,
  problem,
  testSubscription,
  testThreadArticle,
  testThreadCard,
  testThreadPage,
} from '@/test/backend'
import { server } from '@/test/server'
import { authorName, relativeTime } from '@/threads/times'

type ThreadCard = components['schemas']['ThreadCard']
type ThreadPage = components['schemas']['ThreadPage']
type ThreadArticle = components['schemas']['ThreadArticle']

const HOUR = 3_600_000
const DAY = 24 * HOUR
const SYNCED = 1_795_000_000_000
const MEDIUM_DATE = new Intl.DateTimeFormat('en', { dateStyle: 'medium' })

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

function cardNamed(subject: string) {
  const card = screen.getByRole('link', { name: subject }).closest('article')
  if (card === null) {
    throw new Error(`No card with the subject ${subject}`)
  }
  return card
}

function shownSubjects() {
  return within(screen.getByRole('list', { name: 'Discussions' }))
    .getAllByRole('heading')
    .map((heading) => heading.textContent)
}

/**
 * A backend with the subscribed group 5, which was synchronized, and its thread list. What the
 * list returns can be changed while a test runs.
 */
function backendWithThreads(cards: ThreadCard[], page: Partial<ThreadPage> = {}) {
  backendLoggedIn()
  const state = {
    cards,
    page,
    subscription: testSubscription(5, 'example.talk', {
      sync: { running: false, lastSyncedAt: SYNCED },
    }),
    lists: [] as string[],
    markedRead: [] as string[],
    extensions: [] as string[],
  }
  server.use(
    http.get('/api/subscriptions', () => HttpResponse.json([state.subscription])),
    http.get('/api/groups/:groupId/threads', ({ request }) => {
      state.lists.push(new URL(request.url).searchParams.toString())
      return HttpResponse.json(testThreadPage(state.cards, state.page))
    }),
    http.post('/api/groups/:groupId/threads/:articleId/mark-read', ({ params }) => {
      state.markedRead.push(String(params.articleId))
      return new HttpResponse(null, { status: 204 })
    }),
    http.post('/api/groups/:groupId/sync', () => new HttpResponse(null, { status: 202 })),
  )
  return state
}

// Thread cards

test('a card shows subject, author, time, message count, new articles and the excerpt', async () => {
  const now = Date.now()
  backendWithThreads([
    testThreadCard(1201, 'Records vs. Lombok', {
      from: 'Jane Doe <jane@example.org>',
      date: now - 3 * DAY,
      latestActivity: now - 2 * HOUR,
      messageCount: 14,
      unreadCount: 3,
      excerpt: 'I have been comparing…',
    }),
    testThreadCard(1300, 'An old question', {
      date: now - 30 * DAY,
      latestActivity: now - 30 * DAY,
    }),
    testThreadCard(1400, 'Undated', { date: null, latestActivity: null }),
  ])
  openAt('/groups/5')

  await screen.findByRole('link', { name: 'Records vs. Lombok' })
  const card = within(cardNamed('Records vs. Lombok'))
  expect(card.getByText('Jane Doe')).toBeVisible()
  expect(card.getByText('3 d ago')).toHaveAttribute('title')
  expect(card.getByText('14 messages')).toBeVisible()
  expect(card.getByText('2 h ago')).toBeVisible()
  expect(card.getByText('3 new')).toBeVisible()
  expect(card.getByText('I have been comparing…')).toBeVisible()
  expect(card.getByRole('heading')).toHaveClass('font-bold')
  expect(card.getByRole('link')).toHaveAttribute('href', '/groups/5/threads/1201')

  const oldCard = within(cardNamed('An old question'))
  expect(oldCard.getByText(MEDIUM_DATE.format(now - 30 * DAY))).toBeVisible()
  expect(oldCard.getByText('1 message')).toBeVisible()
  expect(oldCard.queryByText(/new$/)).not.toBeInTheDocument()
  expect(oldCard.queryByText(/latest/)).not.toBeInTheDocument()
  expect(oldCard.getByRole('heading')).not.toHaveClass('font-bold')
  expect(oldCard.queryByRole('button', { name: 'Mark thread as read' })).not.toBeInTheDocument()
  expect(within(cardNamed('Undated')).getByText('date unknown')).toBeVisible()
})

test('"Mark thread as read" on a card clears its new articles', async () => {
  const backend = backendWithThreads([
    testThreadCard(1201, 'Records vs. Lombok', { messageCount: 14, unreadCount: 3 }),
    testThreadCard(1300, 'Another', { unreadCount: 1 }),
  ])
  openAt('/groups/5')
  await screen.findByRole('link', { name: 'Records vs. Lombok' })

  await userEvent.click(
    within(cardNamed('Records vs. Lombok')).getByRole('button', { name: 'Mark thread as read' }),
  )

  await waitFor(() => {
    expect(within(cardNamed('Records vs. Lombok')).queryByText('3 new')).not.toBeInTheDocument()
  })
  expect(backend.markedRead).toEqual(['1201'])
  expect(within(cardNamed('Records vs. Lombok')).getByRole('heading')).not.toHaveClass('font-bold')
  expect(within(cardNamed('Another')).getByText('1 new')).toBeVisible()
  // The list was not asked for again.
  expect(backend.lists).toHaveLength(1)
})

test('times are relative for 7 days and a date afterwards', () => {
  const now = new Date(2026, 10, 20, 12).getTime()

  expect(relativeTime(now - 20_000, now)).toBe('just now')
  expect(relativeTime(now - 5 * 60_000, now)).toBe('5 min ago')
  expect(relativeTime(now - 2 * HOUR, now)).toBe('2 h ago')
  expect(relativeTime(now - 6 * DAY - 23 * HOUR, now)).toBe('6 d ago')
  expect(relativeTime(now - 7 * DAY, now)).toBe('Nov 13, 2026')
  // The date of an article can lie in the future.
  expect(relativeTime(now + DAY, now)).toBe('Nov 21, 2026')
})

test('the name of an author is the name part of the sender', () => {
  expect(authorName('Jane Doe <jane@example.org>')).toBe('Jane Doe')
  expect(authorName('"Doe, Jane" <jane@example.org>')).toBe('Doe, Jane')
  expect(authorName('jane@example.org (Jane Doe)')).toBe('Jane Doe')
  expect(authorName('<jane@example.org>')).toBe('jane@example.org')
  expect(authorName('jane@example.org')).toBe('jane@example.org')
  expect(authorName(null)).toBe('Unknown author')
})

// Sorting, paging and loading older

test('the sort tabs choose the order, which is part of the address', async () => {
  const backend = backendWithThreads([testThreadCard(1, 'Hello')])
  openAt('/groups/5')

  await screen.findByRole('navigation', { name: 'Sort order' })
  // The list of another order is another list, so the tabs are looked up again each time.
  const tabs = () => within(screen.getByRole('navigation', { name: 'Sort order' }))
  expect(tabs().getByRole('link', { name: 'Latest activity' })).toHaveAttribute(
    'aria-current',
    'page',
  )
  await screen.findByRole('link', { name: 'Hello' })
  expect(backend.lists).toEqual(['sort=activity'])

  await userEvent.click(tabs().getByRole('link', { name: 'Most discussed' }))

  await waitFor(() => {
    expect(backend.lists).toEqual(['sort=activity', 'sort=discussed'])
  })
  expect(window.location.search).toBe('?sort=discussed')
  expect(tabs().getByRole('link', { name: 'Most discussed' })).toHaveAttribute(
    'aria-current',
    'page',
  )

  await userEvent.click(tabs().getByRole('link', { name: 'Latest activity' }))

  expect(window.location.search).toBe('')
})

test('further pages are loaded with the cursor of the page before', async () => {
  const backend = backendWithThreads([testThreadCard(1, 'First')], { nextCursor: 'after-first' })
  openAt('/groups/5?sort=newest')
  await screen.findByRole('link', { name: 'First' })
  expect(screen.queryByRole('button', { name: 'Load older discussions' })).not.toBeInTheDocument()

  backend.cards = [testThreadCard(2, 'Second')]
  backend.page = { nextCursor: null }
  await userEvent.click(screen.getByRole('button', { name: 'Show more discussions' }))

  expect(await screen.findByRole('link', { name: 'Second' })).toBeVisible()
  expect(shownSubjects()).toEqual(['First', 'Second'])
  expect(backend.lists).toEqual(['sort=newest', 'sort=newest&cursor=after-first'])
  expect(screen.queryByRole('button', { name: 'Show more discussions' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Load older discussions' })).toBeVisible()
})

test('"Load older discussions" extends the range of the list and loads the list again', async () => {
  const backend = backendWithThreads([testThreadCard(2, 'Newer')])
  server.use(
    http.post('/api/groups/:groupId/ranges/:rangeId/extend', ({ params }) => {
      backend.extensions.push(String(params.rangeId))
      backend.cards = [testThreadCard(2, 'Newer'), testThreadCard(1, 'Older')]
      backend.page = { lowWatermarkReached: true }
      return HttpResponse.json({
        rangeId: 7,
        firstNumber: 1,
        lastNumber: 900,
        atOldest: true,
        atNewest: true,
      })
    }),
  )
  openAt('/groups/5')

  await userEvent.click(await screen.findByRole('button', { name: 'Load older discussions' }))

  expect(await screen.findByRole('link', { name: 'Older' })).toBeVisible()
  expect(backend.extensions).toEqual(['7'])
  // The low watermark of the server is reached.
  expect(screen.queryByRole('button', { name: 'Load older discussions' })).not.toBeInTheDocument()
})

test('a "Load older discussions" the backend refuses says why', async () => {
  backendWithThreads([testThreadCard(2, 'Newer')])
  server.use(
    http.post('/api/groups/:groupId/ranges/:rangeId/extend', () =>
      problem(409, 'This group is being synchronized.'),
    ),
  )
  openAt('/groups/5')

  await userEvent.click(await screen.findByRole('button', { name: 'Load older discussions' }))

  expect(await screen.findByText('This group is being synchronized.')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Load older discussions' })).toBeEnabled()
})

test('"Load older discussions" continues with the range as it is now when its range is gone', async () => {
  const backend = backendWithThreads([testThreadCard(2, 'Newer')])
  server.use(
    http.post('/api/groups/:groupId/ranges/:rangeId/extend', ({ params }) => {
      backend.extensions.push(String(params.rangeId))
      if (params.rangeId === '7') {
        // A jump to date has joined the range with its own.
        backend.page = { rangeId: 9 }
        return problem(404, 'Not found.')
      }
      backend.cards = [testThreadCard(2, 'Newer'), testThreadCard(1, 'Older')]
      return HttpResponse.json({
        rangeId: 9,
        firstNumber: 1,
        lastNumber: 900,
        atOldest: false,
        atNewest: true,
      })
    }),
  )
  openAt('/groups/5')

  await userEvent.click(await screen.findByRole('button', { name: 'Load older discussions' }))

  expect(await screen.findByRole('link', { name: 'Older' })).toBeVisible()
  expect(backend.extensions).toEqual(['7', '9'])
  expect(screen.queryByText('Not found.')).not.toBeInTheDocument()
})

// Updates and empty states

test('the list is not reordered after a synchronization until "show" is clicked', async () => {
  const backend = backendWithThreads([
    testThreadCard(1, 'First', { messageCount: 2 }),
    testThreadCard(2, 'Second'),
  ])
  openAt('/groups/5')
  await screen.findByRole('link', { name: 'First' })

  // A synchronization brings a new thread and a reply to the second one.
  backend.cards = [
    testThreadCard(3, 'Brand new'),
    testThreadCard(2, 'Second', { messageCount: 2, unreadCount: 1 }),
    testThreadCard(1, 'First', { messageCount: 2 }),
  ]
  backend.subscription = testSubscription(5, 'example.talk', {
    sync: { running: false, lastSyncedAt: SYNCED + 60_000 },
  })
  await userEvent.click(screen.getByRole('button', { name: 'Refresh' }))

  const show = await screen.findByRole('button', { name: '2 new discussions · show' })
  expect(shownSubjects()).toEqual(['First', 'Second'])

  await userEvent.click(show)

  await waitFor(() => {
    expect(shownSubjects()).toEqual(['Brand new', 'Second', 'First'])
  })
  expect(screen.queryByRole('button', { name: /show$/ })).not.toBeInTheDocument()
})

test('a synchronization that brings nothing new shows no button', async () => {
  const backend = backendWithThreads([testThreadCard(1, 'First')])
  openAt('/groups/5')
  await screen.findByRole('link', { name: 'First' })

  backend.subscription = testSubscription(5, 'example.talk', {
    sync: { running: false, lastSyncedAt: SYNCED + 60_000 },
  })
  await userEvent.click(screen.getByRole('button', { name: 'Refresh' }))

  await waitFor(() => {
    expect(backend.lists).toHaveLength(2)
  })
  expect(screen.queryByRole('button', { name: /show$/ })).not.toBeInTheDocument()
})

test('a group in its first synchronization says so and shows its threads when it is done', async () => {
  const backend = backendWithThreads([], { rangeId: null })
  backend.subscription = testSubscription(5, 'example.talk', { sync: { running: true } })
  openAt('/groups/5')

  expect(await screen.findByText('First synchronization running…')).toBeVisible()

  backend.cards = [testThreadCard(1, 'First')]
  backend.subscription = testSubscription(5, 'example.talk', {
    sync: { running: false, lastSyncedAt: SYNCED },
  })
  await userEvent.click(screen.getByRole('button', { name: 'Synchronizing' }))

  expect(await screen.findByRole('link', { name: 'First' })).toBeVisible()
  expect(screen.queryByText('First synchronization running…')).not.toBeInTheDocument()
})

test('a synchronized group without articles has no discussions yet', async () => {
  backendWithThreads([], { rangeId: null })
  openAt('/groups/5')

  expect(await screen.findByText('No discussions yet')).toBeVisible()
  expect(screen.queryByRole('button', { name: 'Load older discussions' })).not.toBeInTheDocument()
})

test('a group that was unsubscribed while it was open leads to the discover view', async () => {
  backendWithThreads([])
  server.use(
    http.get('/api/groups/:groupId/threads', () =>
      problem(404, 'This group does not exist or is not subscribed.'),
    ),
  )
  openAt('/groups/5')

  await waitFor(() => {
    expect(window.location.pathname).toBe('/discover')
  })
})

// Thread page

/** A backend with a thread of group 5 whose articles all have text. */
function backendWithThread(articles: ThreadArticle[], cards: ThreadCard[] = []) {
  const backend = backendWithThreads(cards)
  const state = { ...backend, articles, read: [] as string[], threadRequests: 0 }
  server.use(
    http.get('/api/groups/:groupId/threads/:articleId', () => {
      state.threadRequests++
      return HttpResponse.json({
        rootId: state.articles[0]?.id ?? 0,
        ignored: false,
        ignoreRuleIds: [],
        articles: state.articles,
      })
    }),
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
    http.put('/api/articles/:articleId/read', ({ params }) => {
      state.read.push(String(params.articleId))
      return new HttpResponse(null, { status: 204 })
    }),
  )
  return state
}

function rowOf(text: string) {
  const row = screen.getByText(text).closest('li')
  if (row === null) {
    throw new Error(`No article with the text ${text}`)
  }
  return row
}

test('replies are indented by their depth, at most 6 levels', async () => {
  const articles = Array.from({ length: 9 }, (_, depth) =>
    testThreadArticle(100 + depth, depth === 0 ? null : 99 + depth, depth),
  )
  backendWithThread(articles)
  openAt('/groups/5/threads/103')

  expect(await screen.findByRole('heading', { name: 'Hello' })).toBeVisible()
  await screen.findByText('Text of 108')
  const indents = [100, 101, 102, 103, 104, 105, 106, 107, 108].map((id) =>
    rowOf(`Text of ${String(id)}`).getAttribute('data-indent'),
  )
  expect(indents).toEqual(['0', '1', '2', '3', '4', '5', '6', '6', '6'])
  // One line per level of indentation.
  expect(rowOf('Text of 108').querySelectorAll(':scope > button')).toHaveLength(6)
  expect(rowOf('Text of 100').querySelectorAll(':scope > button')).toHaveLength(0)
})

test('an article collapses with its replies to one line and expands again', async () => {
  backendWithThread([
    testThreadArticle(1, null, 0, { from: 'Ada Lovelace <ada@example.org>' }),
    testThreadArticle(2, 1, 1, { from: 'Grace Hopper <grace@example.org>' }),
    testThreadArticle(3, 2, 2),
    testThreadArticle(4, 2, 2),
    testThreadArticle(5, 1, 1, { from: 'Edsger <edsger@example.org>' }),
  ])
  openAt('/groups/5/threads/1')
  await screen.findByText('Text of 5')

  await userEvent.click(
    within(rowOf('Text of 2')).getByRole('button', { name: 'Collapse', expanded: true }),
  )

  expect(screen.queryByText('Text of 2')).not.toBeInTheDocument()
  expect(screen.queryByText('Text of 3')).not.toBeInTheDocument()
  expect(screen.queryByText('Text of 4')).not.toBeInTheDocument()
  expect(screen.getByText('Text of 5')).toBeVisible()
  const collapsedRow = within(rowOf('2 replies hidden'))
  expect(collapsedRow.getByText('Grace Hopper')).toBeVisible()

  await userEvent.click(collapsedRow.getByRole('button', { name: 'Expand' }))

  expect(await screen.findByText('Text of 3')).toBeVisible()
  expect(screen.getByText('Text of 2')).toBeVisible()
  expect(screen.queryByText('2 replies hidden')).not.toBeInTheDocument()
})

test('a click on the line of an article collapses the article', async () => {
  backendWithThread([
    testThreadArticle(1, null, 0),
    testThreadArticle(2, 1, 1),
    testThreadArticle(3, 2, 2),
  ])
  openAt('/groups/5/threads/1')
  await screen.findByText('Text of 3')

  // The first line next to the third article belongs to the first one.
  const lines = rowOf('Text of 3').querySelectorAll(':scope > button')
  await userEvent.click(lines[1] as Element)

  expect(screen.queryByText('Text of 3')).not.toBeInTheDocument()
  expect(screen.getByText('1 reply hidden')).toBeVisible()
  expect(screen.getByText('Text of 1')).toBeVisible()
})

test('a stub and a crossposted article are marked as such', async () => {
  const backend = backendWithThread([
    testThreadArticle(1, null, 0, {
      isStub: true,
      subject: null,
      from: null,
      authorKey: null,
      date: null,
      read: false,
    }),
    testThreadArticle(2, 1, 1, { subject: 'Re: Lost', otherGroup: 'example.other', read: false }),
    testThreadArticle(3, 1, 1, { subject: 'Re: Lost', read: false }),
  ])
  openAt('/groups/5/threads/2')

  expect(await screen.findByRole('heading', { name: 'Re: Lost' })).toBeVisible()
  const stub = within(rowOf('Not available locally'))
  expect(stub.queryByText('new')).not.toBeInTheDocument()
  // The content of the stub is fetched like that of any article.
  expect(await stub.findByText('Text of 1')).toBeVisible()
  const crosspost = within(rowOf('Text of 2'))
  expect(crosspost.getByText('Posted to example.other')).toBeVisible()
  expect(crosspost.getByText('new')).toBeVisible()
  expect(within(rowOf('Text of 3')).queryByText(/Posted to/)).not.toBeInTheDocument()
  // The thread is asked for again once the stub is filled in.
  await waitFor(() => {
    expect(backend.threadRequests).toBe(2)
  })
})

test('articles are marked as read when they are displayed and keep their marker while the thread is open', async () => {
  const backend = backendWithThread(
    [testThreadArticle(1, null, 0), testThreadArticle(2, 1, 1, { read: false })],
    [testThreadCard(1, 'Hello', { messageCount: 2, unreadCount: 1 })],
  )
  openAt('/groups/5')
  await userEvent.click(await screen.findByRole('link', { name: 'Hello' }))

  expect(await screen.findByText('Text of 2')).toBeVisible()
  await waitFor(() => {
    expect(backend.read.sort()).toEqual(['1', '2'])
  })
  expect(within(rowOf('Text of 2')).getByText('new')).toBeVisible()
  expect(within(rowOf('Text of 1')).queryByText('new')).not.toBeInTheDocument()

  // Back in the list, the card has no new articles left, without the list being asked for again.
  await userEvent.click(screen.getByRole('link', { name: 'Back to example.talk' }))

  expect(await screen.findByRole('link', { name: 'Hello' })).toBeVisible()
  expect(within(cardNamed('Hello')).queryByText('1 new')).not.toBeInTheDocument()
  expect(backend.lists).toHaveLength(1)
})

test('"Mark thread as read" on the thread page clears the markers', async () => {
  const backend = backendWithThread([
    testThreadArticle(1, null, 0, { read: false }),
    testThreadArticle(2, 1, 1, { read: false, otherGroup: 'example.other' }),
  ])
  openAt('/groups/5/threads/2')
  await screen.findByText('Text of 2')
  expect(within(rowOf('Text of 1')).getByText('new')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: 'Mark thread as read' }))

  await waitFor(() => {
    expect(within(rowOf('Text of 1')).queryByText('new')).not.toBeInTheDocument()
  })
  expect(backend.markedRead).toEqual(['2'])
  expect(screen.getByRole('button', { name: 'Mark thread as read' })).toBeDisabled()
})

test('a thread that no longer exists says so and links to the group', async () => {
  backendWithThreads([])
  server.use(
    http.get('/api/groups/:groupId/threads/:articleId', () =>
      problem(404, 'This discussion is no longer available.'),
    ),
  )
  openAt('/groups/5/threads/77')

  expect(
    await screen.findByRole('heading', { name: 'This discussion is no longer available' }),
  ).toBeVisible()
  expect(screen.getByRole('link', { name: 'Back to the group' })).toHaveAttribute(
    'href',
    '/groups/5',
  )
})
