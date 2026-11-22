import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import { backendLoggedIn, problem } from '@/test/backend'
import { server } from '@/test/server'
import { formatCacheSize } from '@/settings/cacheSize'

const STORED = {
  syncIntervalMinutes: 15,
  initialDepth: 500,
  groupListIntervalHours: 24,
  cacheLimitEnabled: true,
  maxCacheSizeGb: 5,
}

/** What the backend adds to the settings it returns. */
const MEASURED = { cacheUsedBytes: 1288490189, cacheLimitUnreachable: false }

/** A backend that keeps the settings and counts how often they were saved. */
function backendWithSettings() {
  backendLoggedIn()
  const state = { settings: STORED, measured: MEASURED, saved: [] as unknown[] }
  server.use(
    http.get('/api/settings', () => HttpResponse.json({ ...state.settings, ...state.measured })),
    http.put('/api/settings', async ({ request }) => {
      const body = (await request.json()) as typeof STORED
      state.saved.push(body)
      state.settings = body
      return HttpResponse.json({ ...body, ...state.measured })
    }),
  )
  return state
}

async function openSection() {
  window.history.replaceState(null, '', '/settings/synchronization')
  render(<App />)
  await screen.findByRole('heading', { name: 'Synchronization' })
}

async function enter(label: string, value: string) {
  const field = await screen.findByLabelText(label)
  await userEvent.clear(field)
  if (value !== '') {
    await userEvent.type(field, value)
  }
}

test('the section shows the stored settings, and the button waits for a change', async () => {
  backendWithSettings()
  await openSection()

  expect(await screen.findByLabelText('Synchronization interval')).toHaveValue(15)
  expect(screen.getByLabelText('First synchronization depth')).toHaveValue(500)
  expect(screen.getByLabelText('Group list refresh interval')).toHaveValue(24)
  expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()

  await enter('Synchronization interval', '30')

  expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
})

test('saving sends all settings as numbers, says so and waits for the next change', async () => {
  const backend = backendWithSettings()
  await openSection()

  await enter('Synchronization interval', '30')
  await enter('First synchronization depth', '2000')
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('Settings saved')).toBeVisible()
  expect(backend.saved).toEqual([
    {
      syncIntervalMinutes: 30,
      initialDepth: 2000,
      groupListIntervalHours: 24,
      cacheLimitEnabled: true,
      maxCacheSizeGb: 5,
    },
  ])
  await waitFor(() => {
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })
  expect(screen.getByLabelText('Synchronization interval')).toHaveValue(30)
})

test('a value outside its range or an empty field is not sent', async () => {
  const backend = backendWithSettings()
  await openSection()

  await enter('Synchronization interval', '4')
  await enter('First synchronization depth', '10001')
  await enter('Group list refresh interval', '')
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('Enter a number between 5 and 1440.')).toBeVisible()
  expect(screen.getByText('Enter a number between 50 and 10000.')).toBeVisible()
  expect(screen.getByText('Enter a number between 1 and 168.')).toBeVisible()
  expect(screen.getByLabelText('Synchronization interval')).toBeInvalid()
  expect(backend.saved).toEqual([])
})

test('a value the backend refuses is shown at its field', async () => {
  backendWithSettings()
  server.use(
    http.put('/api/settings', () =>
      HttpResponse.json(
        {
          type: 'about:blank',
          title: 'Bad Request',
          status: 400,
          detail: 'The synchronization interval must be between 5 and 1440 minutes.',
          errors: [
            {
              field: 'syncIntervalMinutes',
              message: 'The synchronization interval must be between 5 and 1440 minutes.',
            },
          ],
        },
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  )
  await openSection()

  await enter('Synchronization interval', '30')
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(
    await screen.findByText('The synchronization interval must be between 5 and 1440 minutes.'),
  ).toBeVisible()
  expect(screen.getByLabelText('Synchronization interval')).toBeInvalid()
  // Nothing was saved, so the button still offers to.
  expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
})

test('settings that cannot be loaded are not shown as a form', async () => {
  backendLoggedIn()
  server.use(http.get('/api/settings', () => problem(500, 'An unexpected error occurred.')))
  await openSection()

  expect(await screen.findByText('The settings could not be loaded.')).toBeVisible()
  expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
})

// Cache size limit (ADR 0023, ADR 0034)

test('the section shows the limit and the current use of the cache', async () => {
  backendWithSettings()
  await openSection()

  expect(await screen.findByLabelText('Limit the cache size')).toBeChecked()
  expect(screen.getByLabelText('Maximum cache size')).toHaveValue(5)
  expect(screen.getByText('Cache use: 1.2 GB of 5 GB')).toBeVisible()
  expect(screen.queryByText(/larger than its limit/)).not.toBeInTheDocument()
})

test('a lower limit is saved, and the use is shown against the saved limit', async () => {
  const backend = backendWithSettings()
  await openSection()

  await enter('Maximum cache size', '2')
  // The use is compared with what is stored, not with what is being typed.
  expect(screen.getByText('Cache use: 1.2 GB of 5 GB')).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('Cache use: 1.2 GB of 2 GB')).toBeVisible()
  expect(backend.saved).toEqual([{ ...STORED, maxCacheSizeGb: 2 }])
})

test('switching the limit off hides its field, keeps its value and shows the use alone', async () => {
  const backend = backendWithSettings()
  await openSection()

  await userEvent.click(await screen.findByLabelText('Limit the cache size'))

  expect(screen.queryByLabelText('Maximum cache size')).not.toBeInTheDocument()
  expect(screen.getByText(/Without a limit, nothing is removed/)).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('Cache use: 1.2 GB used')).toBeVisible()
  expect(backend.saved).toEqual([{ ...STORED, cacheLimitEnabled: false }])

  // Switched on again, the field has the value it had.
  await userEvent.click(screen.getByLabelText('Limit the cache size'))
  expect(screen.getByLabelText('Maximum cache size')).toHaveValue(5)
})

test('a cache size outside its range is not sent', async () => {
  const backend = backendWithSettings()
  await openSection()

  await enter('Maximum cache size', '1001')
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('Enter a number between 1 and 1000.')).toBeVisible()
  expect(backend.saved).toEqual([])
})

test('a limit that cannot be reached is explained, but not while the limit is off', async () => {
  const backend = backendWithSettings()
  backend.measured = { cacheUsedBytes: 6 * 1024 * 1024 * 1024, cacheLimitUnreachable: true }
  await openSection()

  expect(await screen.findByText('Cache use: 6.0 GB of 5 GB')).toBeVisible()
  expect(screen.getByRole('status')).toHaveTextContent(
    'The stored headers alone are larger than the limit. Unsubscribe from large groups to free space, or raise the limit.',
  )

  backend.measured = { cacheUsedBytes: 6 * 1024 * 1024 * 1024, cacheLimitUnreachable: false }
  await userEvent.click(screen.getByLabelText('Limit the cache size'))
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('Cache use: 6.0 GB used')).toBeVisible()
  expect(screen.queryByText(/larger than its limit/)).not.toBeInTheDocument()
})

test('the size of the cache is shown in the unit that fits', () => {
  expect(formatCacheSize(0)).toBe('less than 1 MB')
  expect(formatCacheSize(1024 * 1024 - 1)).toBe('less than 1 MB')
  expect(formatCacheSize(340 * 1024 * 1024)).toBe('340 MB')
  expect(formatCacheSize(1024 * 1024 * 1024)).toBe('1.0 GB')
  expect(formatCacheSize(1288490189)).toBe('1.2 GB')
})
