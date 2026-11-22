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

type IgnoreRule = components['schemas']['IgnoreRule']
type ThreadArticle = components['schemas']['ThreadArticle']
type ThreadCard = components['schemas']['ThreadCard']

const BOB = { from: 'Bob <bob@example.org>', authorKey: 'bob@example.org' }
const ADDED = new Date(2026, 10, 18, 12).getTime()

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

interface Start {
  cards?: ThreadCard[]
  articles?: ThreadArticle[]
  rules?: IgnoreRule[]
  /** The rules that ignore the thread of the thread page. */
  threadRuleIds?: number[]
}

/**
 * A backend with group 5, its thread list, one thread and the ignore rules. A new rule gets the
 * ID 40 and hides what the test says it hides; removing a rule shows it again.
 */
function backendWithRules(start: Start = {}) {
  backendLoggedIn()
  const state = {
    cards: start.cards ?? [],
    hiddenCards: [] as ThreadCard[],
    articles: start.articles ?? [],
    rules: start.rules ?? [],
    threadRuleIds: start.threadRuleIds ?? [],
    created: [] as unknown[],
    removed: [] as string[],
    read: [] as string[],
    /** What the backend does with a rule that is created. */
    onCreated: (() => undefined) as (rule: IgnoreRule) => void,
  }
  server.use(
    http.get('/api/subscriptions', () => HttpResponse.json([testSubscription(5, 'example.talk')])),
    http.get('/api/groups/:groupId/threads', () => HttpResponse.json(testThreadPage(state.cards))),
    http.get('/api/groups/:groupId/threads/:articleId', () =>
      HttpResponse.json({
        rootId: state.articles[0]?.id ?? 0,
        ignored: state.threadRuleIds.length > 0,
        ignoreRuleIds: state.threadRuleIds,
        articles: state.articles,
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
    http.put('/api/articles/:articleId/read', ({ params }) => {
      state.read.push(String(params.articleId))
      return new HttpResponse(null, { status: 204 })
    }),
    http.get('/api/ignore-rules', () => HttpResponse.json(state.rules)),
    http.post('/api/ignore-rules', async ({ request }) => {
      const body = (await request.json()) as { kind: IgnoreRule['kind'] }
      state.created.push(body)
      const rule: IgnoreRule = { id: 40, kind: body.kind, label: 'Label', createdAt: ADDED }
      state.rules = [rule, ...state.rules]
      state.onCreated(rule)
      return HttpResponse.json(rule, { status: 201 })
    }),
    http.delete('/api/ignore-rules/:ruleId', ({ params }) => {
      state.removed.push(String(params.ruleId))
      state.rules = state.rules.filter((rule) => String(rule.id) !== params.ruleId)
      state.threadRuleIds = state.threadRuleIds.filter((ruleId) => String(ruleId) !== params.ruleId)
      state.cards = [...state.cards, ...state.hiddenCards]
      state.hiddenCards = []
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

/** Clicks "Undo" in the toast of the last action. */
async function undo() {
  const buttons = await screen.findAllByRole('button', { name: 'Undo' })
  await userEvent.click(buttons.at(-1) as HTMLElement)
}

// Ignoring a thread

test('"Ignore thread" on a card hides the thread, and "Undo" brings it back', async () => {
  const backend = backendWithRules({
    cards: [testThreadCard(31, 'Kept'), testThreadCard(32, 'Off topic', { cardArticleId: 33 })],
  })
  backend.onCreated = () => {
    backend.hiddenCards = backend.cards.filter((card) => card.rootId === 32)
    backend.cards = backend.cards.filter((card) => card.rootId !== 32)
  }
  openAt('/groups/5')
  const card = (await screen.findByRole('link', { name: 'Off topic' })).closest('article')

  await userEvent.click(within(card as HTMLElement).getByRole('button', { name: 'Ignore thread' }))

  expect(await screen.findByText('Thread ignored')).toBeVisible()
  await waitFor(() => {
    expect(screen.queryByRole('link', { name: 'Off topic' })).not.toBeInTheDocument()
  })
  expect(screen.getByRole('link', { name: 'Kept' })).toBeVisible()
  // The thread is named by its card article, whose subject the card shows.
  expect(backend.created).toEqual([{ kind: 'THREAD', articleId: 33 }])

  await undo()

  expect(await screen.findByRole('link', { name: 'Off topic' })).toBeVisible()
  expect(backend.removed).toEqual(['40'])
})

test('"Ignore thread" on the thread page shows the notice, and "Undo" removes it', async () => {
  const backend = backendWithRules({ articles: [testThreadArticle(31, null, 0)] })
  openAt('/groups/5/threads/31')
  await screen.findByText('Text of 31')
  expect(screen.queryByText('You ignore this thread')).not.toBeInTheDocument()

  await userEvent.click(screen.getByRole('button', { name: 'Ignore thread' }))

  expect(await screen.findByText('You ignore this thread')).toBeVisible()
  expect(screen.queryByRole('button', { name: 'Ignore thread' })).not.toBeInTheDocument()
  expect(backend.created).toEqual([{ kind: 'THREAD', articleId: 31 }])
  // Ignoring hides nothing on the page of the thread itself.
  expect(screen.getByText('Text of 31')).toBeVisible()

  await undo()

  await waitFor(() => {
    expect(screen.queryByText('You ignore this thread')).not.toBeInTheDocument()
  })
  expect(screen.getByRole('button', { name: 'Ignore thread' })).toBeVisible()
  expect(backend.removed).toEqual(['40'])
})

test('an ignored thread that is opened says so and can be shown again', async () => {
  const backend = backendWithRules({
    articles: [testThreadArticle(31, null, 0)],
    threadRuleIds: [7, 9],
  })
  openAt('/groups/5/threads/31')

  expect(await screen.findByText('You ignore this thread')).toBeVisible()
  expect(await screen.findByText('Text of 31')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: 'Stop ignoring' }))

  await waitFor(() => {
    expect(screen.queryByText('You ignore this thread')).not.toBeInTheDocument()
  })
  // Every rule that ignores the thread is removed.
  expect(backend.removed.sort()).toEqual(['7', '9'])
})

test('an ignore that the backend refuses says why', async () => {
  backendWithRules({ articles: [testThreadArticle(31, null, 0)] })
  server.use(http.post('/api/ignore-rules', () => problem(404, 'This article does not exist.')))
  openAt('/groups/5/threads/31')

  await userEvent.click(await screen.findByRole('button', { name: 'Ignore thread' }))

  expect(await screen.findByText('This article does not exist.')).toBeVisible()
  expect(screen.queryByText('You ignore this thread')).not.toBeInTheDocument()
})

// Ignored authors

test('an article by an ignored author is one line that can be shown without marking it read', async () => {
  const backend = backendWithRules({
    articles: [
      testThreadArticle(31, null, 0),
      testThreadArticle(32, 31, 1, { ...BOB, ignoredAuthor: true, read: false }),
      testThreadArticle(33, 32, 2, { read: false }),
    ],
  })
  openAt('/groups/5/threads/31')

  // The reply to the ignored article stays visible below it.
  expect(await screen.findByText('Text of 33')).toBeVisible()
  const line = screen.getByRole('article', { name: 'Article by ignored author Bob' })
  expect(within(line).getByText('Ignored author · Bob')).toBeVisible()
  expect(screen.queryByText('Text of 32')).not.toBeInTheDocument()
  expect(line.closest('li')).toHaveAttribute('data-indent', '1')

  await userEvent.click(within(line).getByRole('button', { name: 'Show' }))

  expect(await screen.findByText('Text of 32')).toBeVisible()
  expect(within(rowOf('Text of 32')).getByText('Ignored author')).toBeVisible()
  await waitFor(() => {
    expect(backend.read).toContain('33')
  })
  expect(backend.read).not.toContain('32')
})

test('"Ignore author" in the menu of an article collapses every article of the author', async () => {
  const backend = backendWithRules({
    articles: [
      testThreadArticle(31, null, 0),
      testThreadArticle(32, 31, 1, BOB),
      testThreadArticle(33, 31, 1, BOB),
    ],
  })
  openAt('/groups/5/threads/31')
  await screen.findByText('Text of 33')

  await userEvent.click(within(rowOf('Text of 32')).getByRole('button', { name: 'More actions' }))
  await userEvent.click(await screen.findByRole('menuitem', { name: 'Ignore author' }))

  expect(await screen.findByText('Author ignored')).toBeVisible()
  expect(screen.getAllByText('Ignored author · Bob')).toHaveLength(2)
  expect(screen.queryByText('Text of 32')).not.toBeInTheDocument()
  expect(screen.queryByText('Text of 33')).not.toBeInTheDocument()
  expect(screen.getByText('Text of 31')).toBeVisible()
  expect(backend.created).toEqual([{ kind: 'AUTHOR', articleId: 32 }])

  await undo()

  expect(await screen.findByText('Text of 32')).toBeVisible()
  expect(screen.getByText('Text of 33')).toBeVisible()
  expect(backend.removed).toEqual(['40'])
})

test('a stub has no "Ignore author"', async () => {
  backendWithRules({
    articles: [
      testThreadArticle(30, null, 0, { isStub: true, subject: null, from: null, authorKey: null }),
      testThreadArticle(31, 30, 1),
    ],
  })
  openAt('/groups/5/threads/31')
  await screen.findByText('Text of 31')

  expect(screen.getAllByRole('button', { name: 'More actions' })).toHaveLength(1)
})

// Settings

test('the settings section lists authors and threads and removes a rule', async () => {
  const backend = backendWithRules({
    rules: [
      { id: 3, kind: 'THREAD', label: 'Off topic again', createdAt: ADDED },
      { id: 2, kind: 'AUTHOR', label: 'Bob <b>', createdAt: ADDED },
    ],
  })
  openAt('/settings/ignored')

  const authors = within(await screen.findByRole('list', { name: 'Authors' }))
  // A label comes from an article and is shown as text.
  expect(authors.getByText('Bob <b>')).toBeVisible()
  expect(authors.getByText('Added November 18, 2026')).toBeVisible()
  expect(
    within(screen.getByRole('list', { name: 'Threads' })).getByText('Off topic again'),
  ).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: 'Stop ignoring Bob <b>' }))

  expect(await screen.findByText('You ignore no authors.')).toBeVisible()
  expect(screen.getByText('Off topic again')).toBeVisible()
  expect(backend.removed).toEqual(['2'])
})

test('the settings section says when nothing is ignored', async () => {
  backendWithRules()
  openAt('/settings/ignored')

  expect(await screen.findByText('You ignore no authors.')).toBeVisible()
  expect(screen.getByText('You ignore no threads.')).toBeVisible()
})
