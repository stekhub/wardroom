import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import { backendLoggedIn, IDLE_REFRESH, problem, TEST_SERVER, testGroup } from '@/test/backend'
import { formatActivity } from '@/groups/activity'
import { server } from '@/test/server'

const GROUPS_PATH = '/api/servers/:serverId/groups'
const REFRESH_PATH = '/api/servers/:serverId/groups/refresh'

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

/** Answers the group list request with what the function returns for its parameters. */
function groupsAnswer(answer: (query: string, page: number) => object) {
  const requests: {
    query: string
    page: number
    size: string | null
    serverId: string
    sort: string | null
  }[] = []
  server.use(
    http.get(GROUPS_PATH, ({ request, params }) => {
      const parameters = new URL(request.url).searchParams
      const query = parameters.get('q') ?? ''
      const page = Number(parameters.get('page') ?? '0')
      requests.push({
        query,
        page,
        size: parameters.get('size'),
        serverId: String(params.serverId),
        sort: parameters.get('sort'),
      })
      return HttpResponse.json(answer(query, page))
    }),
  )
  return requests
}

function pageOf(items: object[], page = 0, total = items.length) {
  return { items, page, size: 50, total, activityKnown: true }
}

// The list (spec 003, UI concept)

test('the discover view lists the groups with icon, name, description and posting status', async () => {
  backendLoggedIn()
  const requests = groupsAnswer(() =>
    pageOf([
      testGroup(1, 'comp.lang.java.programmer', {
        description: 'Programming in the Java language.',
      }),
      testGroup(2, 'example.announce', { postingStatus: 'MODERATED' }),
      testGroup(3, 'example.archive', { postingStatus: 'NOT_ALLOWED' }),
    ]),
  )

  openAt('/discover')

  const list = await screen.findByRole('list', { name: 'Groups' })
  const [javaRow, moderatedRow, archiveRow, ...furtherRows] = within(list).getAllByRole('listitem')
  if (javaRow === undefined || moderatedRow === undefined || archiveRow === undefined) {
    throw new Error('The list has fewer than three rows')
  }
  expect(furtherRows).toHaveLength(0)
  expect(within(javaRow).getByText('comp.lang.java.programmer')).toBeVisible()
  expect(within(javaRow).getByText('Programming in the Java language.')).toBeVisible()
  expect(javaRow.querySelector('[data-symbol]')).toHaveAttribute('data-symbol', 'coffee')
  expect(javaRow.querySelector('[data-symbol]')).toHaveAttribute('aria-hidden', 'true')
  expect(within(javaRow).queryByText('Moderated')).not.toBeInTheDocument()
  expect(within(javaRow).queryByText('No posting')).not.toBeInTheDocument()
  expect(within(moderatedRow).getByText('Moderated')).toBeVisible()
  expect(within(archiveRow).getByText('No posting')).toBeVisible()
  expect(screen.getByText('3 groups')).toBeVisible()
  // One page needs no paging row (spec 022).
  expect(screen.queryByRole('navigation', { name: 'Pages' })).not.toBeInTheDocument()
  expect(requests[0]).toEqual({
    query: '',
    page: 0,
    size: '50',
    serverId: String(TEST_SERVER.id),
    sort: 'name',
  })
})

test('name and description from the server are shown as text, not as HTML', async () => {
  backendLoggedIn()
  groupsAnswer(() =>
    pageOf([
      {
        id: 1,
        name: '<img src=x onerror=alert(1)>',
        description: '<b>bold</b> <script>alert(2)</script>',
        postingStatus: 'ALLOWED',
        subscribed: false,
      },
    ]),
  )

  openAt('/discover')

  expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeVisible()
  expect(screen.getByText('<b>bold</b> <script>alert(2)</script>')).toBeVisible()
  const list = screen.getByRole('list', { name: 'Groups' })
  expect(list.querySelector('img, b, script')).toBeNull()
})

test('a group without a description, sent as null, shows only its name', async () => {
  backendLoggedIn()
  groupsAnswer(() =>
    pageOf([
      { id: 1, name: 'alt.test', description: null, postingStatus: 'ALLOWED', subscribed: false },
    ]),
  )

  openAt('/discover')

  const row = within(await screen.findByRole('list', { name: 'Groups' })).getByRole('listitem')
  expect(row.querySelectorAll('p')).toHaveLength(1)
})

test('without groups, the view says how to get them', async () => {
  backendLoggedIn()

  openAt('/discover')

  expect(await screen.findByText(/No groups yet\. Refresh the group list/)).toBeVisible()
  expect(screen.queryByRole('navigation', { name: 'Pages' })).not.toBeInTheDocument()
})

// Search

test('typing searches by name and description and puts the search into the URL', async () => {
  backendLoggedIn()
  const requests = groupsAnswer((query) =>
    query === 'java language'
      ? pageOf([testGroup(1, 'comp.lang.java.programmer')])
      : pageOf([testGroup(1, 'comp.lang.java.programmer'), testGroup(2, 'rec.food.cooking')]),
  )
  openAt('/discover?page=3')
  await screen.findByText('rec.food.cooking')

  await userEvent.type(screen.getByRole('searchbox', { name: 'Search groups' }), ' java language ')

  await waitFor(() => {
    expect(screen.queryByText('rec.food.cooking')).not.toBeInTheDocument()
  })
  expect(screen.getByText('comp.lang.java.programmer')).toBeVisible()
  expect(requests.at(-1)).toMatchObject({ query: 'java language', page: 0 })
  expect(window.location.search).toBe('?q=java+language')
  expect(requests.filter((request) => request.query !== '').length).toBe(1)
})

test('a search without a match says so', async () => {
  backendLoggedIn()
  groupsAnswer((query) => (query === '' ? pageOf([testGroup(1, 'alt.test')]) : pageOf([])))
  openAt('/discover')
  await screen.findByText('alt.test')

  await userEvent.type(screen.getByRole('searchbox', { name: 'Search groups' }), 'cobol')

  expect(await screen.findByText('No group matches your search.')).toBeVisible()
})

test('the search and the page of the URL are shown, so bookmarks work', async () => {
  backendLoggedIn()
  const requests = groupsAnswer((query, page) =>
    pageOf([testGroup(page * 50 + 1, `match.${query}.page${String(page)}`)], page, 120),
  )

  openAt('/discover?q=java&page=2')

  expect(await screen.findByText('match.java.page1')).toBeVisible()
  expect(screen.getByRole('searchbox', { name: 'Search groups' })).toHaveValue('java')
  expect(screen.getByText('120 groups')).toBeVisible()
  expect(screen.getByLabelText('Page')).toHaveValue('2')
  expect(screen.getByText('of 3')).toBeVisible()
  expect(requests).toEqual([
    { query: 'java', page: 1, size: '50', serverId: String(TEST_SERVER.id), sort: 'name' },
  ])
})

// Paging

test('the list is paged with 50 groups per page', async () => {
  backendLoggedIn()
  const requests = groupsAnswer((_query, page) =>
    pageOf([testGroup(page + 1, `paged.page${String(page)}`)], page, 120),
  )
  openAt('/discover')
  await screen.findByText('paged.page0')

  expect(screen.getByText('120 groups')).toBeVisible()
  expect(screen.getByLabelText('Page')).toHaveValue('1')
  expect(screen.getByRole('button', { name: 'First' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Last' })).toBeEnabled()

  await userEvent.click(screen.getByRole('button', { name: 'Next' }))

  expect(await screen.findByText('paged.page1')).toBeVisible()
  expect(window.location.search).toBe('?page=2')
  expect(requests.at(-1)).toMatchObject({ page: 1, size: '50' })

  await userEvent.click(screen.getByRole('button', { name: 'Next' }))

  expect(await screen.findByText('paged.page2')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Last' })).toBeDisabled()
  expect(screen.getByLabelText('Page')).toHaveValue('3')

  await userEvent.click(screen.getByRole('button', { name: 'Previous' }))
  await userEvent.click(await screen.findByRole('button', { name: 'Previous' }))

  expect(await screen.findByText('paged.page0')).toBeVisible()
  expect(window.location.search).toBe('')
})

test('"First" and "Last" jump to the ends of the list', async () => {
  backendLoggedIn()
  groupsAnswer((_query, page) =>
    pageOf([testGroup(page + 1, `paged.page${String(page)}`)], page, 5000),
  )
  openAt('/discover?page=40')
  await screen.findByText('paged.page39')

  await userEvent.click(screen.getByRole('button', { name: 'Last' }))

  expect(await screen.findByText('paged.page99')).toBeVisible()
  expect(window.location.search).toBe('?page=100')

  await userEvent.click(screen.getByRole('button', { name: 'First' }))

  expect(await screen.findByText('paged.page0')).toBeVisible()
  expect(window.location.search).toBe('')
})

test('a page number that is typed opens that page when Enter is pressed', async () => {
  backendLoggedIn()
  const requests = groupsAnswer((_query, page) =>
    pageOf([testGroup(page + 1, `paged.page${String(page)}`)], page, 15_000),
  )
  openAt('/discover')
  await screen.findByText('paged.page0')
  const field = screen.getByLabelText('Page')

  await userEvent.clear(field)
  await userEvent.type(field, '300')
  // Nothing happens before Enter.
  expect(window.location.search).toBe('')
  await userEvent.keyboard('{Enter}')

  expect(await screen.findByText('paged.page299')).toBeVisible()
  expect(window.location.search).toBe('?page=300')
  expect(requests.at(-1)).toMatchObject({ page: 299 })
})

test('a page number outside the list is not accepted, and the field says so', async () => {
  backendLoggedIn()
  const requests = groupsAnswer((_query, page) =>
    pageOf([testGroup(page + 1, `paged.page${String(page)}`)], page, 120),
  )
  openAt('/discover')
  await screen.findByText('paged.page0')
  const field = screen.getByLabelText('Page')

  for (const typed of ['4', '0', '-1', '2.5', 'two', '']) {
    await userEvent.clear(field)
    if (typed !== '') {
      await userEvent.type(field, typed)
    }
    await userEvent.keyboard('{Enter}')

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a page between 1 and 3.')
    expect(field).toBeInvalid()
  }
  expect(window.location.search).toBe('')
  expect(requests).toHaveLength(1)

  await userEvent.clear(field)
  await userEvent.type(field, '3{Enter}')

  expect(await screen.findByText('paged.page2')).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('a page beyond the last one says so, and the paging row leads back', async () => {
  backendLoggedIn()
  groupsAnswer((_query, page) =>
    page > 2
      ? pageOf([], page, 120)
      : pageOf([testGroup(page + 1, `paged.page${String(page)}`)], page, 120),
  )
  openAt('/discover?page=9')

  expect(await screen.findByText('This page does not exist. The list has 3 pages.')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()

  await userEvent.click(screen.getByRole('button', { name: 'Last' }))

  expect(await screen.findByText('paged.page2')).toBeVisible()
  expect(window.location.search).toBe('?page=3')
})

test('changing the search opens the first page', async () => {
  backendLoggedIn()
  groupsAnswer((query, page) =>
    pageOf(
      [testGroup(page + 1, `${query === '' ? 'paged' : query}.page${String(page)}`)],
      page,
      120,
    ),
  )
  openAt('/discover?page=2')
  await screen.findByText('paged.page1')

  await userEvent.type(screen.getByRole('searchbox', { name: 'Search groups' }), 'java')

  expect(await screen.findByText('java.page0')).toBeVisible()
  expect(window.location.search).toBe('?q=java')
  expect(screen.getByLabelText('Page')).toHaveValue('1')
})

// Ignoring a group (spec 022)

test('"Ignore group" hides the row, and "Undo" brings it back', async () => {
  backendLoggedIn()
  const ignored = new Set<number>()
  const created: unknown[] = []
  const removed: string[] = []
  groupsAnswer(() =>
    pageOf(
      [testGroup(1, 'example.noise'), testGroup(2, 'example.talk')].filter(
        (group) => !ignored.has(group.id),
      ),
    ),
  )
  server.use(
    http.post('/api/ignore-rules', async ({ request }) => {
      const body = (await request.json()) as { groupId: number }
      created.push(body)
      ignored.add(body.groupId)
      return HttpResponse.json(
        {
          id: 40,
          kind: 'GROUP',
          label: 'example.noise',
          createdAt: 1,
          groupId: null,
          groupName: null,
        },
        { status: 201 },
      )
    }),
    http.delete('/api/ignore-rules/:ruleId', ({ params }) => {
      removed.push(String(params.ruleId))
      ignored.clear()
      return new HttpResponse(null, { status: 204 })
    }),
  )
  openAt('/discover')
  await screen.findByText('example.noise')

  await userEvent.click(screen.getByRole('button', { name: 'Ignore group example.noise' }))

  expect(await screen.findByText('Group ignored')).toBeVisible()
  await waitFor(() => {
    expect(screen.queryByText('example.noise')).not.toBeInTheDocument()
  })
  expect(screen.getByText('example.talk')).toBeVisible()
  expect(created).toEqual([{ kind: 'GROUP', groupId: 1 }])

  await userEvent.click(screen.getByRole('button', { name: 'Undo' }))

  expect(await screen.findByText('example.noise')).toBeVisible()
  expect(removed).toEqual(['40'])
})

test('a subscribed group has no "Ignore group"', async () => {
  backendLoggedIn()
  groupsAnswer(() =>
    pageOf([testGroup(1, 'example.read', { subscribed: true }), testGroup(2, 'example.other')]),
  )
  openAt('/discover')
  await screen.findByText('example.read')

  expect(screen.getByRole('button', { name: 'Ignore group example.other' })).toBeVisible()
  expect(
    screen.queryByRole('button', { name: 'Ignore group example.read' }),
  ).not.toBeInTheDocument()
})

// Refresh status and progress

test('the view shows when the group list was last refreshed', async () => {
  backendLoggedIn()

  openAt('/discover')

  expect(await screen.findByText(/^Last refreshed .*2026/)).toBeVisible()
  expect(screen.queryByRole('button', { name: /The last refresh failed/ })).not.toBeInTheDocument()
})

const NEVER_FETCHED = { running: false, groupsReceived: 0, refreshedAt: null, error: null }

/** Counts the refresh requests; after the first one, the status says that a refresh is running. */
function countRefreshRequests(statusBefore: object) {
  const requests = { count: 0 }
  server.use(
    http.post(REFRESH_PATH, () => {
      requests.count += 1
      return new HttpResponse(null, { status: 202 })
    }),
    http.get(REFRESH_PATH, () =>
      HttpResponse.json(
        requests.count > 0 ? { running: true, groupsReceived: 0, refreshedAt: null } : statusBefore,
      ),
    ),
  )
  return requests
}

test('a list that was never fetched is fetched when the view is opened', async () => {
  backendLoggedIn()
  const requests = countRefreshRequests(NEVER_FETCHED)

  openAt('/discover')

  expect(await screen.findByText('Fetching the group list… 0 groups received')).toBeVisible()
  expect(requests.count).toBe(1)
})

test('a list whose fetch failed is not fetched again on its own', async () => {
  backendLoggedIn()
  const requests = countRefreshRequests({ ...NEVER_FETCHED, error: 'Host not found' })

  openAt('/discover')

  expect(await screen.findByText('Not fetched yet')).toBeVisible()
  expect(await screen.findByRole('button', { name: /The last refresh failed/ })).toBeVisible()
  expect(requests.count).toBe(0)
})

test('a list is not fetched on its own while the credentials are invalid', async () => {
  backendLoggedIn('owner', [{ ...TEST_SERVER, credentialsInvalid: true }])
  const requests = countRefreshRequests(NEVER_FETCHED)

  openAt('/discover')

  expect(await screen.findByText('Not fetched yet')).toBeVisible()
  expect(requests.count).toBe(0)
})

test('the error of the last refresh is a status icon with its explanation', async () => {
  backendLoggedIn()
  server.use(
    http.get(REFRESH_PATH, () =>
      HttpResponse.json({ ...IDLE_REFRESH, error: 'Host not found: news.example.com' }),
    ),
  )

  openAt('/discover')

  const icon = await screen.findByRole('button', {
    name: 'The last refresh failed: Host not found: news.example.com',
  })
  await userEvent.hover(icon)
  expect(
    await screen.findByRole('tooltip', {
      name: 'The last refresh failed: Host not found: news.example.com',
    }),
  ).toBeInTheDocument()
})

test('a running refresh shows its progress, and the groups appear when it ends', async () => {
  backendLoggedIn()
  let refreshEnded = false
  let statusRequests = 0
  server.use(
    http.get(REFRESH_PATH, () => {
      statusRequests += 1
      refreshEnded = statusRequests > 1
      return HttpResponse.json(
        refreshEnded ? IDLE_REFRESH : { running: true, groupsReceived: 18250, refreshedAt: null },
      )
    }),
  )
  groupsAnswer(() => (refreshEnded ? pageOf([testGroup(1, 'alt.test')]) : pageOf([])))

  openAt('/discover')

  expect(await screen.findByText('Fetching the group list… 18,250 groups received')).toBeVisible()
  expect(screen.getByText(/The group list is being fetched from your server/)).toBeVisible()
  expect(screen.getByRole('button', { name: 'Refresh the group list' })).toBeDisabled()
  // The status is asked for again after a second; then the refresh has ended.
  expect(await screen.findByText('alt.test', {}, { timeout: 4000 })).toBeVisible()
  expect(screen.getByText(/^Last refreshed/)).toBeVisible()
  expect(screen.getByRole('button', { name: 'Refresh the group list' })).toBeEnabled()
})

test('the refresh button starts a refresh', async () => {
  backendLoggedIn()
  let refreshRequested = false
  server.use(
    http.post(REFRESH_PATH, () => {
      refreshRequested = true
      return new HttpResponse(null, { status: 202 })
    }),
    http.get(REFRESH_PATH, () =>
      HttpResponse.json(
        refreshRequested ? { running: true, groupsReceived: 0, refreshedAt: null } : IDLE_REFRESH,
      ),
    ),
  )
  openAt('/discover')
  await screen.findByText(/^Last refreshed/)

  await userEvent.click(screen.getByRole('button', { name: 'Refresh the group list' }))

  expect(await screen.findByText('Fetching the group list… 0 groups received')).toBeVisible()
})

test('a refresh that is refused for invalid credentials leads to the server settings', async () => {
  let credentialsInvalid = false
  backendLoggedIn()
  server.use(
    http.get('/api/servers', () => HttpResponse.json([{ ...TEST_SERVER, credentialsInvalid }])),
    http.post(REFRESH_PATH, () => {
      credentialsInvalid = true
      return problem(409, 'Wardroom cannot log in to the server.')
    }),
  )
  openAt('/discover')
  await screen.findByText(/^Last refreshed/)

  await userEvent.click(screen.getByRole('button', { name: 'Refresh the group list' }))

  expect(await screen.findByText('Wardroom cannot log in to the server.')).toBeVisible()
  expect(
    await screen.findByRole('link', { name: 'Enter them again in the server settings.' }),
  ).toHaveAttribute('href', '/settings/server')
})

// Activity (spec 003)

test('a row shows the activity of its group, and nothing while it is not known', async () => {
  backendLoggedIn()
  groupsAnswer(() =>
    pageOf([
      testGroup(1, 'example.busy', { articlesPerDay: 240.4 }),
      testGroup(2, 'example.quiet', { articlesPerDay: 0.3 }),
      // The backend sends null for an activity that is not known.
      { ...testGroup(3, 'example.new'), articlesPerDay: null },
    ]),
  )

  openAt('/discover')

  const list = await screen.findByRole('list', { name: 'Groups' })
  const [busyRow, quietRow, newRow] = within(list).getAllByRole('listitem')
  if (busyRow === undefined || quietRow === undefined || newRow === undefined) {
    throw new Error('The list has fewer than three rows')
  }
  expect(within(busyRow).getByText('240 per day')).toBeVisible()
  expect(within(quietRow).getByText('under 1 per day')).toBeVisible()
  expect(within(newRow).queryByText(/per day/)).not.toBeInTheDocument()
  expect(screen.queryByText(/shown one day after/)).not.toBeInTheDocument()
})

test('while no activity is known, the view says when it will be', async () => {
  backendLoggedIn()
  groupsAnswer(() => ({ ...pageOf([testGroup(1, 'example.new')]), activityKnown: false }))

  openAt('/discover')

  expect(
    await screen.findByText(
      'The activity of the groups is shown one day after the group list was first fetched.',
    ),
  ).toBeVisible()
})

test('the order by activity is asked for, kept in the URL and starts at the first page', async () => {
  backendLoggedIn()
  const requests = groupsAnswer((_query, page) =>
    pageOf([testGroup(page + 1, 'example.a')], page, 120),
  )

  openAt('/discover?q=example&page=2')

  const order = await screen.findByRole('combobox', { name: 'Order of the groups' })
  expect(order).toHaveValue('name')
  await userEvent.selectOptions(order, 'By activity')

  await waitFor(() => {
    expect(requests.at(-1)).toMatchObject({ query: 'example', sort: 'activity', page: 0 })
  })
  expect(window.location.search).toBe('?q=example&sort=activity')

  await userEvent.click(screen.getByRole('button', { name: 'Next' }))

  await waitFor(() => {
    expect(requests.at(-1)).toMatchObject({ query: 'example', sort: 'activity', page: 1 })
  })
  expect(window.location.search).toBe('?q=example&sort=activity&page=2')
})

test('the order of the URL is shown, and a search keeps it', async () => {
  backendLoggedIn()
  const requests = groupsAnswer(() => pageOf([testGroup(1, 'example.a')]))

  openAt('/discover?sort=activity')

  expect(await screen.findByRole('combobox', { name: 'Order of the groups' })).toHaveValue(
    'activity',
  )
  await userEvent.type(screen.getByRole('searchbox', { name: 'Search groups' }), 'java')

  await waitFor(() => {
    expect(requests.at(-1)).toMatchObject({ query: 'java', sort: 'activity', page: 0 })
  })
  expect(window.location.search).toBe('?q=java&sort=activity')
})

test('the activity is shown as a whole number per day', () => {
  expect(formatActivity(0)).toBe('0 per day')
  expect(formatActivity(0.04)).toBe('under 1 per day')
  expect(formatActivity(0.99)).toBe('under 1 per day')
  expect(formatActivity(1)).toBe('1 per day')
  expect(formatActivity(12.5)).toBe('13 per day')
  expect(formatActivity(12345.6)).toBe('12,346 per day')
})
