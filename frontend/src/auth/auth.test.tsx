import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import { backendLoggedIn, backendLoggedOut, backendWithoutAccount, problem } from '@/test/backend'
import { server } from '@/test/server'

const PASSWORD = 'correct horse battery'

// First-run wizard, step 1: account

test('the wizard creates the account with the CSRF token and then shows the application', async () => {
  backendWithoutAccount()
  document.cookie = 'XSRF-TOKEN=token-from-the-backend; path=/'
  let sentBody: unknown
  let csrfHeader: string | null = null
  server.use(
    http.post('/api/auth/setup', async ({ request }) => {
      sentBody = await request.json()
      csrfHeader = request.headers.get('X-XSRF-TOKEN')
      backendLoggedIn()
      return new HttpResponse(null, { status: 201 })
    }),
  )
  render(<App />)

  await userEvent.type(await screen.findByLabelText('Username'), 'owner')
  await userEvent.type(screen.getByLabelText('Password'), PASSWORD)
  await userEvent.type(screen.getByLabelText('Repeat password'), PASSWORD)
  await userEvent.click(screen.getByRole('button', { name: 'Create account' }))

  expect(await screen.findByRole('link', { name: 'Discover groups' })).toBeVisible()
  expect(sentBody).toEqual({ username: 'owner', password: PASSWORD })
  expect(csrfHeader).toBe('token-from-the-backend')
})

test('the wizard names broken rules and sends nothing', async () => {
  backendWithoutAccount()
  let requests = 0
  server.use(
    http.post('/api/auth/setup', () => {
      requests++
      return new HttpResponse(null, { status: 201 })
    }),
  )
  render(<App />)

  await userEvent.type(await screen.findByLabelText('Username'), '   ')
  await userEvent.type(screen.getByLabelText('Password'), 'elevenchars')
  await userEvent.type(screen.getByLabelText('Repeat password'), 'elevenchars')
  await userEvent.click(screen.getByRole('button', { name: 'Create account' }))

  expect(await screen.findByText('Enter a username with 1 to 64 characters.')).toBeVisible()
  expect(screen.getByText('Enter a password with 12 to 128 characters.')).toBeVisible()
  expect(screen.getByLabelText('Username')).toBeInvalid()
  expect(screen.getByLabelText('Password')).toBeInvalid()

  await userEvent.clear(screen.getByLabelText('Username'))
  await userEvent.type(screen.getByLabelText('Username'), 'owner')
  await userEvent.clear(screen.getByLabelText('Password'))
  await userEvent.type(screen.getByLabelText('Password'), PASSWORD)
  await userEvent.clear(screen.getByLabelText('Repeat password'))
  await userEvent.type(screen.getByLabelText('Repeat password'), 'something else entirely')
  await userEvent.click(screen.getByRole('button', { name: 'Create account' }))

  expect(await screen.findByText('The two passwords are not the same.')).toBeVisible()
  expect(requests).toBe(0)
})

test('the wizard shows the rule the backend names', async () => {
  backendWithoutAccount()
  server.use(
    http.post('/api/auth/setup', () => problem(400, 'The username must have 1 to 64 characters.')),
  )
  render(<App />)

  await userEvent.type(await screen.findByLabelText('Username'), 'owner')
  await userEvent.type(screen.getByLabelText('Password'), PASSWORD)
  await userEvent.type(screen.getByLabelText('Repeat password'), PASSWORD)
  await userEvent.click(screen.getByRole('button', { name: 'Create account' }))

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'The username must have 1 to 64 characters.',
  )
})

test('when the account was created elsewhere in the meantime, the wizard gives way to the login page', async () => {
  backendWithoutAccount()
  server.use(
    http.post('/api/auth/setup', () => {
      backendLoggedOut()
      return problem(404, 'An account already exists.')
    }),
  )
  render(<App />)

  await userEvent.type(await screen.findByLabelText('Username'), 'owner')
  await userEvent.type(screen.getByLabelText('Password'), PASSWORD)
  await userEvent.type(screen.getByLabelText('Repeat password'), PASSWORD)
  await userEvent.click(screen.getByRole('button', { name: 'Create account' }))

  expect(await screen.findByRole('heading', { name: 'Log in' })).toBeVisible()
})

// Login

test('a successful login shows the application at the URL that was opened', async () => {
  backendLoggedOut()
  let sentBody: unknown
  server.use(
    http.post('/api/auth/login', async ({ request }) => {
      sentBody = await request.json()
      backendLoggedIn()
      return new HttpResponse(null, { status: 204 })
    }),
  )
  window.history.replaceState(null, '', '/settings/account')
  render(<App />)

  await userEvent.type(await screen.findByLabelText('Username'), 'owner')
  await userEvent.type(screen.getByLabelText('Password'), PASSWORD)
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

  expect(await screen.findByRole('heading', { name: 'Account' })).toBeVisible()
  expect(sentBody).toEqual({ username: 'owner', password: PASSWORD, rememberMe: false })
})

test('"Remember me" is not chosen by default and is sent when chosen', async () => {
  backendLoggedOut()
  let sentBody: unknown
  server.use(
    http.post('/api/auth/login', async ({ request }) => {
      sentBody = await request.json()
      backendLoggedIn()
      return new HttpResponse(null, { status: 204 })
    }),
  )
  render(<App />)

  const rememberMe = await screen.findByRole('checkbox', { name: 'Remember me' })
  expect(rememberMe).not.toBeChecked()
  expect(rememberMe).toHaveAccessibleDescription(
    'Stay logged in on this browser after it is closed.',
  )

  await userEvent.type(screen.getByLabelText('Username'), 'owner')
  await userEvent.type(screen.getByLabelText('Password'), PASSWORD)
  await userEvent.click(rememberMe)
  expect(rememberMe).toBeChecked()
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

  expect(await screen.findByRole('link', { name: 'Discover groups' })).toBeVisible()
  expect(sentBody).toEqual({ username: 'owner', password: PASSWORD, rememberMe: true })
})

test('a failed login shows the one generic message of the backend', async () => {
  backendLoggedOut()
  server.use(http.post('/api/auth/login', () => problem(401, 'Username or password is wrong.')))
  render(<App />)

  await userEvent.type(await screen.findByLabelText('Username'), 'owner')
  await userEvent.type(screen.getByLabelText('Password'), 'not the password')
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('Username or password is wrong.')
  expect(screen.getByRole('heading', { name: 'Log in' })).toBeVisible()
  expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled()
})

test('a throttled login shows the waiting time and blocks the button until it is over', async () => {
  backendLoggedOut()
  server.use(
    http.post('/api/auth/login', () =>
      problem(429, 'Too many failed logins. Try again in 2 seconds.', { 'Retry-After': '2' }),
    ),
  )
  render(<App />)

  await userEvent.type(await screen.findByLabelText('Username'), 'owner')
  await userEvent.type(screen.getByLabelText('Password'), PASSWORD)
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Too many failed logins. Try again in 2 seconds.',
  )
  expect(screen.getByRole('button', { name: 'Log in' })).toBeDisabled()

  await waitFor(
    () => {
      expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled()
    },
    { timeout: 4000 },
  )
  expect(screen.getByRole('alert')).toHaveTextContent(/^Too many failed logins\.$/)
})

test('the login form asks for both fields before it sends anything', async () => {
  backendLoggedOut()
  render(<App />)

  await userEvent.click(await screen.findByRole('button', { name: 'Log in' }))

  expect(await screen.findByText('Enter your username.')).toBeVisible()
  expect(screen.getByText('Enter your password.')).toBeVisible()
})

// Login with two-factor authentication (spec 012)

/** A backend whose login asks for a code after the password. */
function backendWithSecondStep() {
  backendLoggedOut()
  server.use(http.post('/api/auth/login', () => HttpResponse.json({ secondFactorRequired: true })))
}

async function enterPassword() {
  await userEvent.type(await screen.findByLabelText('Username'), 'owner')
  await userEvent.type(screen.getByLabelText('Password'), PASSWORD)
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))
}

test('with two-factor authentication, the login asks for the code and then shows the application', async () => {
  backendWithSecondStep()
  let sentBody: unknown
  server.use(
    http.post('/api/auth/login/second-factor', async ({ request }) => {
      sentBody = await request.json()
      backendLoggedIn()
      return new HttpResponse(null, { status: 204 })
    }),
  )
  window.history.replaceState(null, '', '/settings/account')
  render(<App />)

  await enterPassword()

  expect(await screen.findByRole('heading', { name: 'Enter your code' })).toBeVisible()
  expect(screen.queryByLabelText('Password')).not.toBeInTheDocument()
  const code = screen.getByLabelText('Code')
  expect(code).toHaveAccessibleDescription(
    'The 6-digit code from your authenticator app, or a recovery code.',
  )
  await userEvent.type(code, '123456')
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

  expect(await screen.findByRole('heading', { name: 'Account' })).toBeVisible()
  expect(sentBody).toEqual({ code: '123456' })
})

test('a wrong code shows the message of the backend and the code can be entered again', async () => {
  backendWithSecondStep()
  server.use(http.post('/api/auth/login/second-factor', () => problem(401, 'The code is wrong.')))
  render(<App />)
  await enterPassword()

  await userEvent.type(await screen.findByLabelText('Code'), '000000')
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('The code is wrong.')
  expect(screen.getByRole('heading', { name: 'Enter your code' })).toBeVisible()
  expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled()
})

test('a throttled code shows the waiting time', async () => {
  backendWithSecondStep()
  server.use(
    http.post('/api/auth/login/second-factor', () =>
      problem(429, 'Too many failed logins. Try again in 2 seconds.', { 'Retry-After': '2' }),
    ),
  )
  render(<App />)
  await enterPassword()

  await userEvent.type(await screen.findByLabelText('Code'), '000000')
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Too many failed logins. Try again in 2 seconds.',
  )
  expect(screen.getByRole('button', { name: 'Log in' })).toBeDisabled()
})

test('the code step leads back to the password, and no code is sent when none was entered', async () => {
  backendWithSecondStep()
  render(<App />)
  await enterPassword()

  await userEvent.click(await screen.findByRole('button', { name: 'Log in' }))
  expect(await screen.findByText('Enter the code.')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: 'Back to the password' }))

  expect(await screen.findByRole('heading', { name: 'Log in' })).toBeVisible()
  expect(screen.getByLabelText('Username')).toHaveValue('')
  expect(screen.getByLabelText('Password')).toHaveValue('')
})
