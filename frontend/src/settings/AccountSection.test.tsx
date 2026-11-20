import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import { backendLoggedIn, problem } from '@/test/backend'
import { server } from '@/test/server'

const PASSWORD = 'correct horse battery'
const NEW_PASSWORD = 'another long password'

async function openAccountSection() {
  backendLoggedIn()
  window.history.replaceState(null, '', '/settings/account')
  render(<App />)
  await screen.findByRole('heading', { name: 'Account' })
}

async function fillIn(currentPassword: string, newPassword: string, repeatedPassword: string) {
  await userEvent.type(screen.getByLabelText('Current password'), currentPassword)
  await userEvent.type(screen.getByLabelText('New password'), newPassword)
  await userEvent.type(screen.getByLabelText('Repeat new password'), repeatedPassword)
}

test('the button is enabled once something was entered', async () => {
  await openAccountSection()
  expect(screen.getByRole('button', { name: 'Change password' })).toBeDisabled()

  await userEvent.type(screen.getByLabelText('Current password'), 'c')

  expect(screen.getByRole('button', { name: 'Change password' })).toBeEnabled()
})

test('changing the password sends both passwords, says so and shows the login page', async () => {
  await openAccountSection()
  let sentBody: unknown
  server.use(
    http.put('/api/auth/password', async ({ request }) => {
      sentBody = await request.json()
      return new HttpResponse(null, { status: 204 })
    }),
  )

  await fillIn(PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))

  expect(await screen.findByRole('heading', { name: 'Log in' })).toBeVisible()
  expect(screen.getByText('Password changed. Log in with your new password.')).toBeVisible()
  expect(sentBody).toEqual({ currentPassword: PASSWORD, newPassword: NEW_PASSWORD })
  expect(screen.queryByRole('link', { name: 'Settings' })).not.toBeInTheDocument()
})

test('a wrong current password is shown at its field', async () => {
  await openAccountSection()
  server.use(http.put('/api/auth/password', () => problem(400, 'The current password is wrong.')))

  await fillIn('not the password', NEW_PASSWORD, NEW_PASSWORD)
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))

  expect(await screen.findByText('The current password is wrong.')).toBeVisible()
  expect(screen.getByLabelText('Current password')).toBeInvalid()
})

test('a new password that breaks a rule or is repeated differently is not sent', async () => {
  await openAccountSection()
  let requests = 0
  server.use(
    http.put('/api/auth/password', () => {
      requests++
      return new HttpResponse(null, { status: 204 })
    }),
  )

  await fillIn(PASSWORD, 'short', 'short')
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))
  expect(await screen.findByText('Enter a password with 12 to 128 characters.')).toBeVisible()

  await userEvent.clear(screen.getByLabelText('New password'))
  await userEvent.type(screen.getByLabelText('New password'), NEW_PASSWORD)
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))
  expect(await screen.findByText('The two passwords are not the same.')).toBeVisible()

  expect(requests).toBe(0)
})
