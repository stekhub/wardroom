import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse, type JsonBodyType } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import { backendLoggedIn, backendWithoutServer, problem, TEST_SERVER } from '@/test/backend'
import { server } from '@/test/server'

const NNTP_PASSWORD = 's3cret-Passw0rd'
const SUCCESSFUL_TEST = { success: true, failure: null, postingAllowed: true }
const FAILED_TEST = {
  success: false,
  failure: { type: 'UNREACHABLE', message: 'Host not found: news.example.com' },
  postingAllowed: null,
}

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

async function openServerStep() {
  backendWithoutServer()
  openAt('/')
  await screen.findByRole('heading', { name: 'Add your server' })
}

async function openServerSection(serverOverrides: Partial<typeof TEST_SERVER> = {}) {
  backendLoggedIn('owner', [{ ...TEST_SERVER, ...serverOverrides }])
  openAt('/settings/server')
  await screen.findByRole('heading', { name: 'Server' })
}

async function fillInServerWithLogin() {
  await userEvent.type(screen.getByLabelText('Name'), 'My provider')
  await userEvent.type(screen.getByLabelText('Host'), 'news.example.com')
  await userEvent.type(screen.getByLabelText('Server username'), 'reader')
  await userEvent.type(screen.getByLabelText('Server password'), NNTP_PASSWORD)
}

/** Answers a request to the path with the body and remembers what was sent. */
function capture(method: 'post' | 'put', path: string, status: number, body: JsonBodyType) {
  const sent: { body?: unknown; count: number } = { count: 0 }
  server.use(
    http[method](path, async ({ request }) => {
      sent.body = await request.json()
      sent.count += 1
      return HttpResponse.json(body, { status })
    }),
  )
  return sent
}

// First-run wizard, step 2 (UI concept, spec 002)

test('without a server, every URL shows step 2 of the wizard and nothing else', async () => {
  backendWithoutServer()

  openAt('/settings/account')

  expect(await screen.findByRole('heading', { name: 'Add your server' })).toBeVisible()
  expect(screen.getByText('Server').closest('li')).toHaveAttribute('aria-current', 'step')
  expect(screen.getByText('Account').closest('li')).not.toHaveAttribute('aria-current')
  expect(screen.queryByRole('link', { name: 'Settings' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Remove server' })).not.toBeInTheDocument()
})

test('the form starts with the defaults of the specification', async () => {
  await openServerStep()

  expect(screen.getByLabelText('TLS mode')).toHaveValue('IMPLICIT')
  expect(screen.getByLabelText('Port')).toHaveValue(563)
  expect(screen.getByLabelText('Authentication')).toHaveValue('USER_PASS')
  expect(screen.getByLabelText('Connections')).toHaveValue(4)
  expect(screen.getByLabelText('Server password')).toHaveAttribute('type', 'password')
})

test('changing the TLS mode changes the port to the default of that mode', async () => {
  await openServerStep()

  await userEvent.selectOptions(screen.getByLabelText('TLS mode'), 'NONE')
  expect(screen.getByLabelText('Port')).toHaveValue(119)

  await userEvent.selectOptions(screen.getByLabelText('TLS mode'), 'IMPLICIT')
  expect(screen.getByLabelText('Port')).toHaveValue(563)
})

test('a port the owner entered is kept when the TLS mode changes', async () => {
  await openServerStep()
  await userEvent.clear(screen.getByLabelText('Port'))
  await userEvent.type(screen.getByLabelText('Port'), '8563')

  await userEvent.selectOptions(screen.getByLabelText('TLS mode'), 'NONE')

  expect(screen.getByLabelText('Port')).toHaveValue(8563)
})

test('without authentication there are no fields for username and password', async () => {
  await openServerStep()

  await userEvent.selectOptions(screen.getByLabelText('Authentication'), 'NONE')

  expect(screen.queryByLabelText('Server username')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('Server password')).not.toBeInTheDocument()
})

test('mistakes are shown at their fields and nothing is sent', async () => {
  await openServerStep()
  await userEvent.clear(screen.getByLabelText('Port'))
  await userEvent.clear(screen.getByLabelText('Connections'))
  await userEvent.type(screen.getByLabelText('Connections'), '51')

  await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

  expect(await screen.findByText('Enter a name with 1 to 64 characters.')).toBeVisible()
  expect(screen.getByText('Enter a host name or an IP address.')).toBeVisible()
  expect(screen.getByText('Enter a port between 1 and 65535.')).toBeVisible()
  expect(screen.getByText('Enter a number between 1 and 50.')).toBeVisible()
  expect(screen.getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true')
  // Nothing was sent: an unmocked request would have failed the test.
})

test('a login needs a username and a password', async () => {
  await openServerStep()
  await userEvent.type(screen.getByLabelText('Name'), 'My provider')
  await userEvent.type(screen.getByLabelText('Host'), 'news.example.com')

  await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

  expect(await screen.findByText('Enter a username with 1 to 255 characters.')).toBeVisible()
  expect(screen.getByText('Enter a password with 1 to 255 characters.')).toBeVisible()
})

test('a login without TLS is refused', async () => {
  await openServerStep()
  await fillInServerWithLogin()
  await userEvent.selectOptions(screen.getByLabelText('TLS mode'), 'NONE')

  await userEvent.click(screen.getByRole('button', { name: 'Test connection' }))

  expect(await screen.findByText('A login with username and password needs TLS.')).toBeVisible()
})

test('"Test connection" sends the form without saving and shows the result', async () => {
  await openServerStep()
  await fillInServerWithLogin()
  const sent = capture('post', '/api/servers/connection-test', 200, SUCCESSFUL_TEST)

  await userEvent.click(screen.getByRole('button', { name: 'Test connection' }))

  expect(await screen.findByText('Connection successful')).toBeVisible()
  expect(screen.getByText('The server allows posting.')).toBeVisible()
  expect(sent.body).toEqual({
    name: 'My provider',
    host: 'news.example.com',
    port: 563,
    tlsMode: 'IMPLICIT',
    authMethod: 'USER_PASS',
    username: 'reader',
    password: NNTP_PASSWORD,
    maxConnections: 4,
  })
  expect(screen.getByRole('heading', { name: 'Add your server' })).toBeVisible()
})

test('a failed connection test names what failed and the cause', async () => {
  await openServerStep()
  await fillInServerWithLogin()
  capture('post', '/api/servers/connection-test', 200, {
    success: false,
    failure: { type: 'TLS_CERTIFICATE', message: 'The certificate is not trusted: expired' },
    postingAllowed: null,
  })

  await userEvent.click(screen.getByRole('button', { name: 'Test connection' }))

  expect(await screen.findByText('The certificate of the server is not trusted')).toBeVisible()
  expect(screen.getByText('The certificate is not trusted: expired')).toBeVisible()
})

test('saving the server opens the discover view', async () => {
  await openServerStep()
  await userEvent.selectOptions(screen.getByLabelText('TLS mode'), 'NONE')
  await userEvent.selectOptions(screen.getByLabelText('Authentication'), 'NONE')
  await userEvent.type(screen.getByLabelText('Name'), '  Open server ')
  await userEvent.type(screen.getByLabelText('Host'), 'news.example.org')
  const saved = {
    ...TEST_SERVER,
    name: 'Open server',
    host: 'news.example.org',
    port: 119,
    tlsMode: 'NONE',
    authMethod: 'NONE',
    username: null,
    passwordStored: false,
  }
  const sent = capture('post', '/api/servers', 201, {
    server: saved,
    connectionTest: SUCCESSFUL_TEST,
  })

  await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

  expect(await screen.findByRole('heading', { name: 'Discover groups' })).toBeVisible()
  expect(window.location.pathname).toBe('/discover')
  expect(screen.getByText('Server added')).toBeVisible()
  expect(screen.getByRole('link', { name: 'Settings' })).toBeVisible()
  expect(sent.body).toEqual({
    name: 'Open server',
    host: 'news.example.org',
    port: 119,
    tlsMode: 'NONE',
    authMethod: 'NONE',
    maxConnections: 4,
  })
})

test('a server whose test failed is saved with a warning', async () => {
  await openServerStep()
  await fillInServerWithLogin()
  capture('post', '/api/servers', 201, { server: TEST_SERVER, connectionTest: FAILED_TEST })

  await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

  expect(await screen.findByRole('heading', { name: 'Discover groups' })).toBeVisible()
  expect(
    screen.getByText('Server added, but the connection test failed. Check the server settings.'),
  ).toBeVisible()
})

test('a rule only the backend found is shown at its field', async () => {
  await openServerStep()
  await fillInServerWithLogin()
  server.use(
    http.post('/api/servers', () =>
      HttpResponse.json(
        {
          status: 400,
          detail: 'The host must be a host name or an IP address with 1 to 253 characters.',
          errors: [
            {
              field: 'host',
              message: 'The host must be a host name or an IP address with 1 to 253 characters.',
            },
          ],
        },
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  )

  await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

  expect(
    await screen.findByText(
      'The host must be a host name or an IP address with 1 to 253 characters.',
    ),
  ).toBeVisible()
  expect(screen.getByLabelText('Host')).toHaveAttribute('aria-invalid', 'true')
})

// Settings section "Server"

test('the settings section shows the stored server with an empty password field', async () => {
  await openServerSection()

  expect(screen.getByLabelText('Name')).toHaveValue('My provider')
  expect(screen.getByLabelText('Host')).toHaveValue('news.example.com')
  expect(screen.getByLabelText('Port')).toHaveValue(563)
  expect(screen.getByLabelText('Server username')).toHaveValue('reader')
  expect(screen.getByLabelText('Server password')).toHaveValue('')
  expect(screen.getByText('A password is stored. Leave the field empty to keep it.')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
})

test('saving a change keeps the stored password when the field is empty', async () => {
  await openServerSection()
  const sent = capture('put', '/api/servers/1', 200, {
    server: { ...TEST_SERVER, name: 'Renamed' },
    connectionTest: SUCCESSFUL_TEST,
  })

  await userEvent.clear(screen.getByLabelText('Name'))
  await userEvent.type(screen.getByLabelText('Name'), 'Renamed')
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('Settings saved')).toBeVisible()
  expect(sent.body).toEqual({
    name: 'Renamed',
    host: 'news.example.com',
    port: 563,
    tlsMode: 'IMPLICIT',
    authMethod: 'USER_PASS',
    username: 'reader',
    maxConnections: 4,
  })
  expect(screen.getByText('Connection successful')).toBeVisible()
  expect(screen.getByLabelText('Name')).toHaveValue('Renamed')
  await waitFor(() => {
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })
})

test('saving while the connection test fails warns and shows the cause', async () => {
  await openServerSection()
  capture('put', '/api/servers/1', 200, { server: TEST_SERVER, connectionTest: FAILED_TEST })

  await userEvent.type(screen.getByLabelText('Server password'), NNTP_PASSWORD)
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('Settings saved, but the connection test failed.')).toBeVisible()
  expect(screen.getByText('The server cannot be reached')).toBeVisible()
  expect(screen.getByText('Host not found: news.example.com')).toBeVisible()
  expect(screen.getByLabelText('Server password')).toHaveValue('')
})

test('the connection test of a stored server names it, so its stored password is used', async () => {
  await openServerSection()
  const sent = capture('post', '/api/servers/connection-test', 200, SUCCESSFUL_TEST)

  await userEvent.click(screen.getByRole('button', { name: 'Test connection' }))

  expect(await screen.findByText('Connection successful')).toBeVisible()
  expect(sent.body).toEqual({
    id: 1,
    name: 'My provider',
    host: 'news.example.com',
    port: 563,
    tlsMode: 'IMPLICIT',
    authMethod: 'USER_PASS',
    username: 'reader',
    maxConnections: 4,
  })
})

test('removing the server asks first, and cancelling keeps it', async () => {
  await openServerSection()

  await userEvent.click(screen.getByRole('button', { name: 'Remove server' }))

  const dialog = await screen.findByRole('alertdialog', { name: 'Remove this server?' })
  expect(dialog).toHaveTextContent('subscriptions')
  await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
  await waitFor(() => {
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
  expect(screen.getByLabelText('Name')).toHaveValue('My provider')
})

test('after the server was removed, the server form is shown again', async () => {
  await openServerSection()
  let removed = false
  server.use(
    http.delete('/api/servers/1', () => {
      removed = true
      return new HttpResponse(null, { status: 204 })
    }),
  )

  await userEvent.click(screen.getByRole('button', { name: 'Remove server' }))
  const dialog = await screen.findByRole('alertdialog')
  await userEvent.click(within(dialog).getByRole('button', { name: 'Remove server' }))

  expect(await screen.findByRole('heading', { name: 'Add your server' })).toBeVisible()
  expect(removed).toBe(true)
  expect(screen.getByText('Server removed')).toBeVisible()
  expect(screen.getByLabelText('Name')).toHaveValue('')
  expect(screen.queryByRole('link', { name: 'Settings' })).not.toBeInTheDocument()
})

// Invalid credentials (spec 002)

test('rejected credentials are announced on every view with a link to the server settings', async () => {
  backendLoggedIn('owner', [{ ...TEST_SERVER, credentialsInvalid: true }])

  openAt('/discover')

  expect(await screen.findByText('Wardroom cannot log in to your server')).toBeVisible()
  expect(
    screen.getByRole('link', { name: 'Enter them again in the server settings.' }),
  ).toHaveAttribute('href', '/settings/server')
})

test('without rejected credentials there is no such notice', async () => {
  backendLoggedIn()

  openAt('/discover')

  expect(await screen.findByRole('heading', { name: 'Discover groups' })).toBeVisible()
  expect(screen.queryByText('Wardroom cannot log in to your server')).not.toBeInTheDocument()
})

test('when the servers cannot be loaded, an error with a retry button is shown', async () => {
  backendLoggedIn()
  server.use(http.get('/api/servers', () => problem(500, 'An unexpected error occurred.')))
  openAt('/')
  expect(await screen.findByRole('heading', { name: 'Wardroom cannot be reached' })).toBeVisible()

  backendLoggedIn()
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

  expect(await screen.findByRole('heading', { name: 'Discover groups' })).toBeVisible()
})
