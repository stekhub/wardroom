import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import { backendLoggedIn, backendLoggedOut, backendWithoutAccount, problem } from '@/test/backend'
import { server } from '@/test/server'

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

// What is shown depends on setup and session state (spec 001)

test('without an account, every URL shows the first-run wizard and nothing else', async () => {
  backendWithoutAccount()

  openAt('/settings/account')

  expect(await screen.findByRole('heading', { name: 'Create your account' })).toBeVisible()
  expect(screen.getByRole('list', { name: 'Setup steps' })).toHaveTextContent('1Account2Server')
  expect(screen.getByText('Account').closest('li')).toHaveAttribute('aria-current', 'step')
  expect(screen.queryByRole('link', { name: 'Settings' })).not.toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: 'Log in' })).not.toBeInTheDocument()
})

test('without a session, every URL shows the login page', async () => {
  backendLoggedOut()

  openAt('/discover')

  expect(await screen.findByRole('heading', { name: 'Log in' })).toBeVisible()
  expect(screen.queryByRole('link', { name: 'Discover groups' })).not.toBeInTheDocument()
})

test('with a session, the shell is shown: sidebar with logo name, settings button and discover button', async () => {
  backendLoggedIn()

  openAt('/')

  expect(await screen.findByRole('heading', { name: 'Discover groups' })).toBeVisible()
  expect(screen.getByRole('link', { name: 'Wardroom' })).toBeVisible()
  expect(screen.getByRole('link', { name: 'Settings' })).toHaveAttribute('href', '/settings')
  expect(screen.getByRole('link', { name: 'Discover groups' })).toHaveAttribute('href', '/discover')
  expect(window.location.pathname).toBe('/discover')
})

test('while the state is loading, a skeleton is shown instead of a spinner', () => {
  server.use(http.get('/api/auth/me', () => new Promise<never>(() => undefined)))

  openAt('/')

  expect(screen.getByLabelText('Loading')).toHaveAttribute('aria-busy', 'true')
})

test('when the backend does not answer as expected, an error with a retry button is shown', async () => {
  server.use(http.get('/api/auth/me', () => problem(500, 'An unexpected error occurred.')))
  openAt('/')
  expect(await screen.findByRole('heading', { name: 'Wardroom cannot be reached' })).toBeVisible()

  backendLoggedIn()
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

  expect(await screen.findByRole('heading', { name: 'Discover groups' })).toBeVisible()
})

test('an unknown URL shows a page that says so', async () => {
  backendLoggedIn()

  openAt('/no-such-view')

  expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeVisible()
})

// Settings page frame (UI concept)

test('the settings page lists the sections with the selected one in the URL, and log out last', async () => {
  backendLoggedIn()

  openAt('/settings')

  const navigation = await screen.findByRole('navigation', { name: 'Settings sections' })
  expect(
    within(navigation)
      .getAllByRole('listitem')
      .map((item) => item.textContent),
  ).toEqual([
    'Server',
    'Synchronization',
    'Posting identity',
    'Ignored',
    'Account',
    'About',
    'Log out',
  ])
  expect(window.location.pathname).toBe('/settings/server')
  expect(within(navigation).getByRole('link', { name: 'Server' })).toHaveAttribute(
    'aria-current',
    'page',
  )

  await userEvent.click(within(navigation).getByRole('link', { name: 'Account' }))

  expect(window.location.pathname).toBe('/settings/account')
  expect(screen.getByRole('heading', { name: 'Account' })).toBeVisible()
  expect(screen.getByText('owner')).toBeVisible()
})

// Logout and ended sessions

test('logging out sends the CSRF token and shows the login page', async () => {
  backendLoggedIn()
  document.cookie = 'XSRF-TOKEN=token-from-the-backend; path=/'
  let csrfHeader: string | null = null
  server.use(
    http.post('/api/auth/logout', ({ request }) => {
      csrfHeader = request.headers.get('X-XSRF-TOKEN')
      return new HttpResponse(null, { status: 204 })
    }),
  )
  openAt('/settings/account')

  await userEvent.click(await screen.findByRole('button', { name: 'Log out' }))

  expect(await screen.findByRole('heading', { name: 'Log in' })).toBeVisible()
  expect(csrfHeader).toBe('token-from-the-backend')
  expect(screen.queryByRole('link', { name: 'Settings' })).not.toBeInTheDocument()
})

test('when the session has ended, the next request leads to the login page', async () => {
  backendLoggedIn()
  openAt('/settings/account')
  await screen.findByRole('heading', { name: 'Account' })

  backendLoggedOut()
  server.use(http.put('/api/auth/password', () => problem(401, 'Log in to use this endpoint.')))
  await userEvent.type(screen.getByLabelText('Current password'), 'correct horse battery')
  await userEvent.type(screen.getByLabelText('New password'), 'another long password')
  await userEvent.type(screen.getByLabelText('Repeat new password'), 'another long password')
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))

  await waitFor(() => {
    expect(screen.getByRole('heading', { name: 'Log in' })).toBeVisible()
  })
})
