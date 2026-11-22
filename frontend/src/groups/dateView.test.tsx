import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import type { components } from '@/api/schema'
import { dayOf, endOfDay, startOfDay } from '@/groups/days'
import {
  backendLoggedIn,
  problem,
  testSubscription,
  testThreadArticle,
  testThreadCard,
  testThreadPage,
} from '@/test/backend'
import { server } from '@/test/server'

/** A jump result as the backend sends it: with `null` where the generated type says absent. */
type JumpAnswer = { range: Range | null; oldestDate: number | null }
type Range = components['schemas']['Range']

const RANGE: Range = {
  rangeId: 7,
  firstNumber: 120400,
  lastNumber: 120899,
  atOldest: false,
  atNewest: false,
}

const NEW_YEAR_2015 = new Date(2015, 0, 1).getTime()
const END_OF_NEW_YEAR_2015 = new Date(2015, 0, 2).getTime()

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

/**
 * A backend with one subscribed group that answers jumps and remembers what it was asked. The
 * group has one thread up to the day.
 */
function backendWithJump(jumpResult: JumpAnswer = { range: RANGE, oldestDate: null }) {
  backendLoggedIn()
  const state = {
    jumps: [] as unknown[],
    extensions: [] as string[],
    lists: [] as string[],
    cards: [testThreadCard(31, 'Up to that day')],
    lowWatermarkReached: false,
  }
  server.use(
    http.get('/api/groups/:groupId/threads', ({ request }) => {
      const query = new URL(request.url).searchParams
      state.lists.push(query.toString())
      return HttpResponse.json(
        query.has('until')
          ? testThreadPage(state.cards, {
              highWatermarkReached: false,
              lowWatermarkReached: state.lowWatermarkReached,
            })
          : testThreadPage(),
      )
    }),
    http.get('/api/subscriptions', () => HttpResponse.json([testSubscription(5, 'example.talk')])),
    http.post('/api/groups/:groupId/jump', async ({ request }) => {
      state.jumps.push(await request.json())
      return HttpResponse.json(jumpResult)
    }),
  )
  return state
}

// Jump to date (spec 005) and the date view (spec 008)

test('choosing a day in the group header opens the date view and asks for the jump', async () => {
  const backend = backendWithJump()
  openAt('/groups/5')

  await userEvent.click(await screen.findByRole('button', { name: 'Jump to date' }))
  const field = await screen.findByLabelText('Show the group up to')
  expect(screen.getByRole('button', { name: 'Jump' })).toBeDisabled()
  expect(field).toHaveAttribute('max', dayOf(Date.now()))
  fireEvent.change(field, { target: { value: '2015-01-01' } })
  await userEvent.click(screen.getByRole('button', { name: 'Jump' }))

  expect(await screen.findByText('Showing up to January 1, 2015')).toBeVisible()
  expect(await screen.findByRole('link', { name: 'Up to that day' })).toBeVisible()
  // The threads of the range of the jump, up to the end of the day.
  expect(backend.lists.at(-1)).toBe(
    new URLSearchParams({
      until: new Date(END_OF_NEW_YEAR_2015).toISOString(),
      rangeId: '7',
    }).toString(),
  )
  expect(screen.queryByRole('navigation', { name: 'Sort order' })).not.toBeInTheDocument()
  expect(window.location.pathname + window.location.search).toBe('/groups/5?until=2015-01-01')
  // The end of the day in the time zone of the browser: the chosen day is included.
  expect(backend.jumps).toEqual([{ until: END_OF_NEW_YEAR_2015 }])
  expect(screen.queryByLabelText('Show the group up to')).not.toBeInTheDocument()
})

test('while the server is searched for the day, the view says so', async () => {
  backendWithJump()
  const jumpAnswer: { send: (() => void) | null } = { send: null }
  server.use(
    http.post(
      '/api/groups/:groupId/jump',
      () =>
        new Promise<Response>((resolve) => {
          jumpAnswer.send = () => {
            resolve(HttpResponse.json({ range: RANGE, oldestDate: null }))
          }
        }),
    ),
  )
  openAt('/groups/5?until=2015-01-01')

  expect(await screen.findByRole('status')).toHaveTextContent('Searching the server for this day…')
  await waitFor(() => {
    expect(jumpAnswer.send).not.toBeNull()
  })
  jumpAnswer.send?.()

  expect(await screen.findByRole('link', { name: 'Up to that day' })).toBeVisible()
  expect(screen.queryByText('Searching the server for this day…')).not.toBeInTheDocument()
})

test('"Load older discussions" extends the range downwards and loads the list again', async () => {
  const backend = backendWithJump()
  server.use(
    http.post('/api/groups/:groupId/ranges/:rangeId/extend', ({ params }) => {
      backend.extensions.push(String(params.rangeId))
      backend.cards = [testThreadCard(31, 'Up to that day'), testThreadCard(30, 'Before that')]
      backend.lowWatermarkReached = true
      return HttpResponse.json({ ...RANGE, firstNumber: 1, atOldest: true })
    }),
  )
  openAt('/groups/5?until=2015-01-01')

  await userEvent.click(await screen.findByRole('button', { name: 'Load older discussions' }))

  expect(await screen.findByRole('link', { name: 'Before that' })).toBeVisible()
  expect(backend.extensions).toEqual(['7'])
  expect(backend.jumps).toHaveLength(1)
  // The low watermark of the server is reached.
  expect(screen.queryByRole('button', { name: 'Load older discussions' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
})

test('going back from a thread shows the date view without asking for the jump again', async () => {
  const backend = backendWithJump()
  server.use(
    http.get('/api/groups/:groupId/threads/:articleId', () =>
      HttpResponse.json({ rootId: 31, articles: [testThreadArticle(31, null, 0)] }),
    ),
    http.get('/api/articles/:articleId/content', ({ params }) =>
      HttpResponse.json({
        articleId: Number(params.articleId),
        body: 'Text',
        flowed: false,
        delSp: false,
        truncated: false,
        omittedParts: [],
        rawHeaders: 'Subject: Hello',
      }),
    ),
  )
  openAt('/groups/5?until=2015-01-01')

  await userEvent.click(await screen.findByRole('link', { name: 'Up to that day' }))
  await userEvent.click(await screen.findByRole('link', { name: 'Back to example.talk' }))

  expect(await screen.findByRole('link', { name: 'Up to that day' })).toBeVisible()
  expect(window.location.search).toBe('?until=2015-01-01')
  expect(backend.jumps).toHaveLength(1)
  expect(backend.lists.filter((list) => list.includes('until'))).toHaveLength(1)
})

test('after a jump to another day, the first day is asked for again', async () => {
  const backend = backendWithJump()
  openAt('/groups/5?until=2015-01-01')
  expect(await screen.findByRole('link', { name: 'Up to that day' })).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: 'Jump to date' }))
  fireEvent.change(await screen.findByLabelText('Show the group up to'), {
    target: { value: '2016-01-01' },
  })
  await userEvent.click(screen.getByRole('button', { name: 'Jump' }))
  expect(await screen.findByText('Showing up to January 1, 2016')).toBeVisible()
  await waitFor(() => {
    expect(backend.jumps).toHaveLength(2)
  })
  window.history.back()

  expect(await screen.findByText('Showing up to January 1, 2015')).toBeVisible()
  // The range of the first jump may have been joined with the second one.
  await waitFor(() => {
    expect(backend.jumps).toHaveLength(3)
  })
})

test('when the list says that the range is gone, the jump is run again with a new range', async () => {
  const backend = backendWithJump()
  let rangeGone = false
  server.use(
    http.get('/api/groups/:groupId/threads', ({ request }) => {
      const query = new URL(request.url).searchParams
      backend.lists.push(query.toString())
      if (rangeGone && query.get('rangeId') === '7') {
        return HttpResponse.json(
          {
            status: 400,
            detail: 'The group has no such range.',
            errors: [{ field: 'rangeId', message: 'The group has no such range.' }],
          },
          { status: 400 },
        )
      }
      return HttpResponse.json(
        testThreadPage([testThreadCard(31, 'Up to that day')], {
          rangeId: Number(query.get('rangeId')),
          nextCursor: rangeGone ? null : 'next',
        }),
      )
    }),
  )
  openAt('/groups/5?until=2015-01-01')
  expect(await screen.findByRole('link', { name: 'Up to that day' })).toBeVisible()
  // The range was joined with the newest range, which has the ID 9.
  rangeGone = true
  server.use(
    http.post('/api/groups/:groupId/jump', async ({ request }) => {
      backend.jumps.push(await request.json())
      return HttpResponse.json({ range: { ...RANGE, rangeId: 9 }, oldestDate: null })
    }),
  )

  await userEvent.click(screen.getByRole('button', { name: 'Show more discussions' }))

  await waitFor(() => {
    expect(backend.jumps).toHaveLength(2)
  })
  await waitFor(() => {
    expect(backend.lists.at(-1)).toContain('rangeId=9')
  })
  expect(await screen.findByRole('link', { name: 'Up to that day' })).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('when "Load older discussions" finds the range gone, the jump is run again', async () => {
  const backend = backendWithJump()
  server.use(
    http.post('/api/groups/:groupId/ranges/:rangeId/extend', () =>
      problem(404, 'This group has no such range of articles.'),
    ),
  )
  openAt('/groups/5?until=2015-01-01')

  await userEvent.click(await screen.findByRole('button', { name: 'Load older discussions' }))

  await waitFor(() => {
    expect(backend.jumps).toHaveLength(2)
  })
  expect(await screen.findByRole('link', { name: 'Up to that day' })).toBeVisible()
  expect(screen.queryByText('This group has no such range of articles.')).not.toBeInTheDocument()
})

test('a "Load older discussions" the backend refuses says why and keeps what is shown', async () => {
  backendWithJump()
  server.use(
    http.post('/api/groups/:groupId/ranges/:rangeId/extend', () =>
      problem(502, 'The server did not answer in time.'),
    ),
  )
  openAt('/groups/5?until=2015-01-01')

  await userEvent.click(await screen.findByRole('button', { name: 'Load older discussions' }))

  expect(await screen.findByText('The server did not answer in time.')).toBeVisible()
  expect(screen.getByRole('link', { name: 'Up to that day' })).toBeVisible()
  expect(screen.getByRole('button', { name: 'Load older discussions' })).toBeEnabled()
})

test('a day before the oldest article says from when that article is and leads there', async () => {
  const backend = backendWithJump({ range: null, oldestDate: new Date(2019, 2, 5, 14).getTime() })
  openAt('/groups/5?until=2015-01-01')

  expect(
    await screen.findByText(
      /This day is before the oldest article on the server, which is from March 5, 2019/,
    ),
  ).toBeVisible()
  expect(screen.getByText('Showing up to January 1, 2015')).toBeVisible()
  // No list is asked for without a range.
  expect(backend.lists.filter((list) => list.includes('until'))).toEqual([])

  await userEvent.click(screen.getByRole('link', { name: 'Show that day' }))

  expect(await screen.findByText('Showing up to March 5, 2019')).toBeVisible()
  expect(window.location.pathname + window.location.search).toBe('/groups/5?until=2019-03-05')
})

test('a jump the backend refuses says why and shows no list', async () => {
  backendLoggedIn()
  server.use(
    http.get('/api/subscriptions', () => HttpResponse.json([testSubscription(5, 'example.talk')])),
    http.post('/api/groups/:groupId/jump', () =>
      problem(502, 'The dates of the articles in this group could not be read.'),
    ),
  )
  openAt('/groups/5?until=2015-01-01')

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'The dates of the articles in this group could not be read.',
  )
  expect(screen.queryByRole('button', { name: 'Load older discussions' })).not.toBeInTheDocument()
})

test('the link of the date view leads back to the normal view', async () => {
  backendWithJump()
  openAt('/groups/5?until=2015-01-01')

  await userEvent.click(await screen.findByRole('link', { name: 'Back to the newest' }))

  await waitFor(() => {
    expect(screen.queryByText('Showing up to January 1, 2015')).not.toBeInTheDocument()
  })
  expect(window.location.pathname + window.location.search).toBe('/groups/5')
  expect(await screen.findByRole('navigation', { name: 'Sort order' })).toBeVisible()
})

test('an address with something that is no day shows the normal view', async () => {
  const backend = backendWithJump()
  openAt('/groups/5?until=2015-02-31')

  expect(await screen.findByRole('navigation', { name: 'Sort order' })).toBeVisible()
  expect(screen.queryByText(/Showing up to/)).not.toBeInTheDocument()
  expect(backend.jumps).toEqual([])
})

// Days

test('the start of a day is midnight in the time zone of the browser', () => {
  expect(startOfDay('2015-01-01')).toBe(NEW_YEAR_2015)
  expect(startOfDay('2024-02-29')).toBe(new Date(2024, 1, 29).getTime())
  expect(dayOf(new Date(2015, 0, 1, 23, 59).getTime())).toBe('2015-01-01')
})

test('the end of a day is the start of the next day, also when the clock changes', () => {
  expect(endOfDay('2015-01-01')).toBe(END_OF_NEW_YEAR_2015)
  expect(endOfDay('2024-12-31')).toBe(new Date(2025, 0, 1).getTime())
  expect(endOfDay('2024-03-31')).toBe(new Date(2024, 3, 1).getTime())
  expect(endOfDay('2015-02-31')).toBeUndefined()
})

test('a text that is no existing day has no start', () => {
  expect(startOfDay('2015-02-31')).toBeUndefined()
  expect(startOfDay('2015-13-01')).toBeUndefined()
  expect(startOfDay('1/1/2015')).toBeUndefined()
  expect(startOfDay('')).toBeUndefined()
})
