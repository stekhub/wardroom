import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import type { components } from '@/api/schema'
import { dayOf, startOfDay } from '@/groups/days'
import { backendLoggedIn, problem, testSubscription } from '@/test/backend'
import { server } from '@/test/server'

type JumpResult = components['schemas']['JumpResult']
type Range = components['schemas']['Range']

const RANGE: Range = {
  rangeId: 7,
  firstNumber: 120400,
  lastNumber: 120899,
  atOldest: false,
  atNewest: false,
}

const NEW_YEAR_2015 = new Date(2015, 0, 1).getTime()

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

/** A backend with one subscribed group that answers jumps and remembers what it was asked. */
function backendWithJump(jumpResult: Partial<JumpResult> = {}) {
  backendLoggedIn()
  const state = { jumps: [] as unknown[], extensions: [] as string[] }
  server.use(
    http.get('/api/subscriptions', () => HttpResponse.json([testSubscription(5, 'example.talk')])),
    http.post('/api/groups/:groupId/jump', async ({ request }) => {
      state.jumps.push(await request.json())
      return HttpResponse.json({
        range: RANGE,
        startNumber: 120400,
        startDate: NEW_YEAR_2015 + 3_600_000,
        clampedToOldest: false,
        ...jumpResult,
      })
    }),
  )
  return state
}

// Jump to date (spec 005) and the date view (spec 008)

test('choosing a day in the group header opens the date view and asks for the jump', async () => {
  const backend = backendWithJump()
  openAt('/groups/5')

  await userEvent.click(await screen.findByRole('button', { name: 'Jump to date' }))
  const field = await screen.findByLabelText('Read the group from')
  expect(screen.getByRole('button', { name: 'Jump' })).toBeDisabled()
  expect(field).toHaveAttribute('max', dayOf(Date.now()))
  fireEvent.change(field, { target: { value: '2015-01-01' } })
  await userEvent.click(screen.getByRole('button', { name: 'Jump' }))

  expect(await screen.findByText('Showing from January 1, 2015')).toBeVisible()
  expect(await screen.findByText(/The articles 120400 to 120899 are loaded\./)).toBeVisible()
  expect(window.location.pathname + window.location.search).toBe('/groups/5?from=2015-01-01')
  // The start of the day in the time zone of the browser.
  expect(backend.jumps).toEqual([{ from: NEW_YEAR_2015 }])
  expect(screen.queryByLabelText('Read the group from')).not.toBeInTheDocument()
})

test('"Load more" extends the range upwards until the newest range is reached', async () => {
  const backend = backendWithJump()
  server.use(
    http.post('/api/groups/:groupId/ranges/:rangeId/extend', async ({ params, request }) => {
      const body = (await request.json()) as { direction: string }
      backend.extensions.push(`${String(params.rangeId)} ${body.direction}`)
      return HttpResponse.json({ ...RANGE, lastNumber: 121399, atNewest: true })
    }),
  )
  openAt('/groups/5?from=2015-01-01')

  await userEvent.click(await screen.findByRole('button', { name: 'Load more' }))

  expect(await screen.findByText(/The articles 120400 to 121399 are loaded\./)).toBeVisible()
  expect(backend.extensions).toEqual(['7 NEWER'])
  expect(backend.jumps).toHaveLength(1)
  expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
})

test('a "Load more" the backend refuses says why and keeps what is shown', async () => {
  backendWithJump()
  server.use(
    http.post('/api/groups/:groupId/ranges/:rangeId/extend', () =>
      problem(502, 'The server did not answer in time.'),
    ),
  )
  openAt('/groups/5?from=2015-01-01')

  await userEvent.click(await screen.findByRole('button', { name: 'Load more' }))

  expect(await screen.findByText('The server did not answer in time.')).toBeVisible()
  expect(screen.getByText(/The articles 120400 to 120899 are loaded\./)).toBeVisible()
  expect(screen.getByRole('button', { name: 'Load more' })).toBeEnabled()
})

test('a day before the oldest article says from when the oldest article is', async () => {
  backendWithJump({ clampedToOldest: true, startDate: new Date(2019, 2, 5, 14).getTime() })
  openAt('/groups/5?from=2015-01-01')

  expect(
    await screen.findByText('The oldest article on the server is from March 5, 2019.'),
  ).toBeVisible()
  expect(screen.getByText('Showing from January 1, 2015')).toBeVisible()
})

test('a jump the backend refuses says why and offers no "Load more"', async () => {
  backendLoggedIn()
  server.use(
    http.get('/api/subscriptions', () => HttpResponse.json([testSubscription(5, 'example.talk')])),
    http.post('/api/groups/:groupId/jump', () =>
      problem(502, 'The dates of the articles in this group could not be read.'),
    ),
  )
  openAt('/groups/5?from=2015-01-01')

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'The dates of the articles in this group could not be read.',
  )
  expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
})

test('the link of the date view leads back to the normal view', async () => {
  backendWithJump()
  openAt('/groups/5?from=2015-01-01')

  await userEvent.click(await screen.findByRole('link', { name: 'Back to the newest' }))

  await waitFor(() => {
    expect(screen.queryByText('Showing from January 1, 2015')).not.toBeInTheDocument()
  })
  expect(window.location.pathname + window.location.search).toBe('/groups/5')
  expect(screen.getByText('The threads of this group are not shown yet.')).toBeVisible()
})

test('an address with something that is no day shows the normal view', async () => {
  const backend = backendWithJump()
  openAt('/groups/5?from=2015-02-31')

  expect(await screen.findByText('The threads of this group are not shown yet.')).toBeVisible()
  expect(screen.queryByText(/Showing from/)).not.toBeInTheDocument()
  expect(backend.jumps).toEqual([])
})

// Days

test('the start of a day is midnight in the time zone of the browser', () => {
  expect(startOfDay('2015-01-01')).toBe(NEW_YEAR_2015)
  expect(startOfDay('2024-02-29')).toBe(new Date(2024, 1, 29).getTime())
  expect(dayOf(new Date(2015, 0, 1, 23, 59).getTime())).toBe('2015-01-01')
})

test('a text that is no existing day has no start', () => {
  expect(startOfDay('2015-02-31')).toBeUndefined()
  expect(startOfDay('2015-13-01')).toBeUndefined()
  expect(startOfDay('1/1/2015')).toBeUndefined()
  expect(startOfDay('')).toBeUndefined()
})
