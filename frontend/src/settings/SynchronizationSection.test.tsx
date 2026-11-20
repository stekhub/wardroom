import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import { backendLoggedIn, problem } from '@/test/backend'
import { server } from '@/test/server'

const STORED = { syncIntervalMinutes: 15, initialDepth: 500, groupListIntervalHours: 24 }

/** A backend that keeps the settings and counts how often they were saved. */
function backendWithSettings() {
  backendLoggedIn()
  const state = { settings: STORED, saved: [] as unknown[] }
  server.use(
    http.get('/api/settings', () => HttpResponse.json(state.settings)),
    http.put('/api/settings', async ({ request }) => {
      const body = (await request.json()) as typeof STORED
      state.saved.push(body)
      state.settings = body
      return HttpResponse.json(body)
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
    { syncIntervalMinutes: 30, initialDepth: 2000, groupListIntervalHours: 24 },
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
