import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import { backendLoggedIn, problem, testGroup, testSubscription } from '@/test/backend'
import { server } from '@/test/server'

type Subscription = ReturnType<typeof testSubscription>

const GROUPS_PATH = '/api/servers/:serverId/groups'
const SUBSCRIPTION_PATH = '/api/groups/:groupId/subscription'

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

/** A backend that keeps the subscriptions, so the sidebar shows what the toggle did. */
function backendWithSubscriptions(subscriptions: Subscription[] = []) {
  backendLoggedIn()
  const state = { subscriptions, requests: [] as string[] }
  server.use(
    http.get('/api/subscriptions', () => HttpResponse.json(state.subscriptions)),
    http.put(SUBSCRIPTION_PATH, ({ params }) => {
      state.requests.push(`PUT ${String(params.groupId)}`)
      state.subscriptions = [
        ...state.subscriptions,
        testSubscription(Number(params.groupId), 'example.talk'),
      ]
      return new HttpResponse(null, { status: 204 })
    }),
    http.delete(SUBSCRIPTION_PATH, ({ params }) => {
      state.requests.push(`DELETE ${String(params.groupId)}`)
      state.subscriptions = state.subscriptions.filter(
        (subscription) => subscription.groupId !== Number(params.groupId),
      )
      return new HttpResponse(null, { status: 204 })
    }),
  )
  return state
}

function showGroups(groups: ReturnType<typeof testGroup>[]) {
  server.use(
    http.get(GROUPS_PATH, () =>
      HttpResponse.json({ items: groups, page: 0, size: 50, total: groups.length }),
    ),
  )
}

async function sidebarList() {
  return screen.findByRole('list', { name: 'Subscribed groups' })
}

// The subscribe toggle (spec 004, UI concept)

test('the toggle changes at once on subscribe, and the group appears in the sidebar', async () => {
  const backend = backendWithSubscriptions()
  showGroups([testGroup(7, 'example.talk'), testGroup(8, 'example.misc')])
  let answerSubscribe: () => void = () => undefined
  const subscribeAnswered = new Promise<void>((resolve) => {
    answerSubscribe = resolve
  })
  server.use(
    http.put(SUBSCRIPTION_PATH, async ({ params }) => {
      backend.requests.push(`PUT ${String(params.groupId)}`)
      await subscribeAnswered
      backend.subscriptions = [testSubscription(7, 'example.talk')]
      return new HttpResponse(null, { status: 204 })
    }),
  )
  openAt('/discover')
  expect(await screen.findByText('No subscribed groups yet.')).toBeVisible()
  const toggle = await screen.findByRole('button', { name: 'Subscribe to example.talk' })
  expect(toggle).toHaveAttribute('aria-pressed', 'false')

  await userEvent.click(toggle)

  // The backend has not answered yet, and no dialog asked.
  const pressedToggle = await screen.findByRole('button', { name: 'Unsubscribe from example.talk' })
  expect(pressedToggle).toHaveAttribute('aria-pressed', 'true')
  expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Subscribe to example.misc' })).toBeVisible()
  expect(backend.requests).toEqual(['PUT 7'])

  answerSubscribe()

  expect(await within(await sidebarList()).findByText('example.talk')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Unsubscribe from example.talk' })).toBeVisible()
})

test('the toggle goes back when the backend refuses to subscribe', async () => {
  backendWithSubscriptions()
  showGroups([testGroup(7, 'example.talk')])
  server.use(
    http.put(SUBSCRIPTION_PATH, () =>
      problem(409, 'The server no longer has this group, so it cannot be subscribed to.'),
    ),
  )
  openAt('/discover')

  await userEvent.click(await screen.findByRole('button', { name: 'Subscribe to example.talk' }))

  expect(await screen.findByText('Could not subscribe to example.talk. Try again.')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Subscribe to example.talk' })).toHaveAttribute(
    'aria-pressed',
    'false',
  )
})

test('unsubscribing asks for confirmation and does nothing when cancelled', async () => {
  const backend = backendWithSubscriptions([testSubscription(7, 'example.talk')])
  showGroups([testGroup(7, 'example.talk', { subscribed: true })])
  openAt('/discover')

  await userEvent.click(
    await screen.findByRole('button', { name: 'Unsubscribe from example.talk' }),
  )

  const dialog = await screen.findByRole('alertdialog', { name: 'Unsubscribe from this group?' })
  expect(dialog).toHaveTextContent(
    'This deletes the cached articles of example.talk and what you have read in it.',
  )
  expect(backend.requests).toEqual([])

  await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))

  await waitFor(() => {
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
  expect(backend.requests).toEqual([])
  expect(screen.getByRole('button', { name: 'Unsubscribe from example.talk' })).toBeVisible()
})

test('confirming unsubscribes, and the group leaves the sidebar', async () => {
  const backend = backendWithSubscriptions([testSubscription(7, 'example.talk')])
  showGroups([testGroup(7, 'example.talk', { subscribed: true })])
  openAt('/discover')
  await sidebarList()

  await userEvent.click(
    await screen.findByRole('button', { name: 'Unsubscribe from example.talk' }),
  )
  await userEvent.click(
    within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Unsubscribe' }),
  )

  expect(await screen.findByText('Unsubscribed from example.talk')).toBeVisible()
  expect(backend.requests).toEqual(['DELETE 7'])
  expect(await screen.findByRole('button', { name: 'Subscribe to example.talk' })).toBeVisible()
  expect(await screen.findByText('No subscribed groups yet.')).toBeVisible()
})

// The subscription list in the sidebar

test('the sidebar lists the subscribed groups with icon, unread count and status', async () => {
  backendWithSubscriptions([
    testSubscription(1, 'comp.lang.java.programmer', {
      description: 'Programming in the Java language.',
      unreadCount: 1234,
    }),
    testSubscription(2, 'example.failed', {
      sync: { running: false, error: 'The server did not answer in time' },
    }),
    testSubscription(3, 'example.gone', { removed: true }),
    testSubscription(4, 'example.running', { sync: { running: true } }),
  ])

  openAt('/discover')

  const [javaEntry, failedEntry, goneEntry, runningEntry, ...furtherEntries] = within(
    await sidebarList(),
  ).getAllByRole('listitem')
  if (
    javaEntry === undefined ||
    failedEntry === undefined ||
    goneEntry === undefined ||
    runningEntry === undefined
  ) {
    throw new Error('The sidebar has fewer than four entries')
  }
  expect(furtherEntries).toHaveLength(0)
  expect(within(javaEntry).getByRole('link')).toHaveAttribute('href', '/groups/1')
  expect(within(javaEntry).getByText('comp.lang.java.programmer')).toHaveAttribute(
    'title',
    'comp.lang.java.programmer',
  )
  expect(javaEntry.querySelector('[data-symbol]')).toHaveAttribute('data-symbol', 'coffee')
  expect(within(javaEntry).getByLabelText('1,234 unread')).toHaveTextContent('1,234')
  expect(within(javaEntry).queryByRole('img')).not.toBeInTheDocument()
  expect(within(failedEntry).queryByLabelText(/unread/)).not.toBeInTheDocument()
  expect(
    within(failedEntry).getByRole('img', {
      name: 'The last synchronization failed: The server did not answer in time',
    }),
  ).toBeVisible()
  expect(
    within(goneEntry).getByRole('img', { name: 'The server no longer has this group' }),
  ).toBeVisible()
  expect(within(runningEntry).getByRole('img', { name: 'Synchronizing' })).toBeVisible()
})

test('a group name from the server is shown as text in the sidebar, not as HTML', async () => {
  backendWithSubscriptions([testSubscription(1, '<img src=x onerror=alert(1)>')])

  openAt('/discover')

  const list = await sidebarList()
  expect(within(list).getByText('<img src=x onerror=alert(1)>')).toBeVisible()
  expect(list.querySelector('img')).toBeNull()
})

// The group header

test('the group view shows the header of a subscribed group with its badges', async () => {
  backendWithSubscriptions([
    testSubscription(5, 'example.announce', { postingStatus: 'MODERATED', removed: true }),
  ])

  openAt('/groups/5')

  expect(await screen.findByRole('heading', { name: 'example.announce' })).toBeVisible()
  const header = screen.getByRole('banner')
  expect(header.querySelector('[data-symbol]')).toHaveClass('size-16')
  expect(within(header).getByText('Moderated')).toBeVisible()
  expect(within(header).getByText('Removed from the server')).toBeVisible()
  expect(
    within(header).getByRole('button', { name: 'Unsubscribe from example.announce' }),
  ).toHaveAttribute('aria-pressed', 'true')
})

test('"Mark all as read" asks the backend and shows the new unread count', async () => {
  const backend = backendWithSubscriptions([
    testSubscription(5, 'example.talk', { unreadCount: 12 }),
  ])
  server.use(
    http.post('/api/groups/:groupId/mark-all-read', ({ params }) => {
      backend.requests.push(`MARK ${String(params.groupId)}`)
      backend.subscriptions = [testSubscription(5, 'example.talk')]
      return new HttpResponse(null, { status: 204 })
    }),
  )
  openAt('/groups/5')
  expect(await within(await sidebarList()).findByLabelText('12 unread')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: 'Mark all as read' }))

  await waitFor(() => {
    expect(screen.queryByLabelText('12 unread')).not.toBeInTheDocument()
  })
  expect(backend.requests).toEqual(['MARK 5'])
  expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
})

// The refresh button (spec 005)

test('the refresh button starts a synchronization and shows that it is running', async () => {
  const backend = backendWithSubscriptions([testSubscription(5, 'example.talk')])
  server.use(
    http.post('/api/groups/:groupId/sync', ({ params }) => {
      backend.requests.push(`SYNC ${String(params.groupId)}`)
      backend.subscriptions = [testSubscription(5, 'example.talk', { sync: { running: true } })]
      return new HttpResponse(null, { status: 202 })
    }),
  )
  openAt('/groups/5')

  await userEvent.click(await screen.findByRole('button', { name: 'Refresh' }))

  expect(await screen.findByRole('button', { name: 'Synchronizing' })).toBeVisible()
  expect(backend.requests).toEqual(['SYNC 5'])
  expect(screen.queryByRole('button', { name: 'Refresh' })).not.toBeInTheDocument()
})

test('a refresh the backend refuses says why', async () => {
  backendWithSubscriptions([testSubscription(5, 'example.talk')])
  server.use(
    http.post('/api/groups/:groupId/sync', () =>
      problem(409, 'Wardroom cannot log in to the server.'),
    ),
  )
  openAt('/groups/5')

  await userEvent.click(await screen.findByRole('button', { name: 'Refresh' }))

  expect(await screen.findByText('Wardroom cannot log in to the server.')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Refresh' })).toBeVisible()
})

test('unsubscribing in the group view opens the discover view', async () => {
  const backend = backendWithSubscriptions([testSubscription(5, 'example.talk')])
  openAt('/groups/5')

  await userEvent.click(
    await screen.findByRole('button', { name: 'Unsubscribe from example.talk' }),
  )
  await userEvent.click(
    within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Unsubscribe' }),
  )

  expect(await screen.findByRole('heading', { name: 'Discover groups' })).toBeVisible()
  expect(window.location.pathname).toBe('/discover')
  expect(backend.requests).toEqual(['DELETE 5'])
})

test('a group that is not subscribed has no group view', async () => {
  backendWithSubscriptions([testSubscription(5, 'example.talk')])

  openAt('/groups/6')

  expect(await screen.findByRole('heading', { name: 'Not a subscribed group' })).toBeVisible()
  expect(
    within(screen.getByRole('main')).getByRole('link', { name: 'Discover groups' }),
  ).toHaveAttribute('href', '/discover')
})
