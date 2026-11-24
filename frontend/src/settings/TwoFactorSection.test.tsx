import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import { backendLoggedIn } from '@/test/backend'
import { server } from '@/test/server'

const PASSWORD = 'correct horse battery'
const SECRET = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP'
const OTPAUTH_URI = `otpauth://totp/Wardroom%3Aowner?secret=${SECRET}&issuer=Wardroom&algorithm=SHA1&digits=6&period=30`
const RECOVERY_CODES = [
  'ABCDE23456',
  'BCDEF34567',
  'CDEFG45678',
  'DEFGH56789',
  'EFGHJ67892',
  'FGHJK78923',
  'GHJKL89234',
  'HJKLM92345',
  'JKLMN23456',
  'KLMNP34567',
]

/** A field validation error as the backend sends it. */
function fieldProblem(field: string, message: string) {
  return HttpResponse.json(
    { type: 'about:blank', title: 'Bad Request', status: 400, errors: [{ field, message }] },
    { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

/** A backend that keeps whether two-factor authentication is on. */
function backendWithTwoFactor(enabled: boolean) {
  backendLoggedIn()
  const state = { enabled, recoveryCodesLeft: enabled ? 10 : 0 }
  server.use(http.get('/api/auth/two-factor', () => HttpResponse.json(state)))
  return state
}

async function openAccountSection() {
  window.history.replaceState(null, '', '/settings/account')
  render(<App />)
  await screen.findByRole('heading', { name: 'Two-factor authentication' })
}

test('turning it on asks for the password, shows the QR code and then the recovery codes once', async () => {
  const state = backendWithTwoFactor(false)
  const sent: unknown[] = []
  server.use(
    http.post('/api/auth/two-factor/setup', async ({ request }) => {
      sent.push(await request.json())
      return HttpResponse.json({ otpauthUri: OTPAUTH_URI, secret: SECRET })
    }),
    http.post('/api/auth/two-factor/enable', async ({ request }) => {
      sent.push(await request.json())
      state.enabled = true
      state.recoveryCodesLeft = 10
      return HttpResponse.json({ recoveryCodes: RECOVERY_CODES })
    }),
  )
  await openAccountSection()
  expect(await screen.findByText('Off.')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: 'Turn on' }))
  const dialog = await screen.findByRole('dialog', { name: 'Turn on two-factor authentication' })
  await userEvent.type(within(dialog).getByLabelText('Current password'), PASSWORD)
  await userEvent.click(within(dialog).getByRole('button', { name: 'Continue' }))

  const scanDialog = await screen.findByRole('dialog', { name: 'Scan the QR code' })
  const code = within(scanDialog).getByRole('img', { name: 'QR code for the authenticator app' })
  expect(code.querySelector('image')).toHaveAttribute(
    'href',
    expect.stringContaining('wardroom-logo'),
  )
  expect(within(scanDialog).getByText('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP')).toBeVisible()
  await userEvent.type(within(scanDialog).getByLabelText('Code from the app'), '123456')
  await userEvent.click(within(scanDialog).getByRole('button', { name: 'Turn on' }))

  const codesDialog = await screen.findByRole('dialog', { name: 'Your recovery codes' })
  expect(within(codesDialog).getByText(/They are not shown again\./)).toBeVisible()
  const shownCodes = within(within(codesDialog).getByRole('list', { name: 'Recovery codes' }))
    .getAllByRole('listitem')
    .map((item) => item.textContent)
  expect(shownCodes).toEqual(RECOVERY_CODES)
  expect(sent).toEqual([{ currentPassword: PASSWORD }, { code: '123456' }])

  await userEvent.click(within(codesDialog).getByRole('button', { name: 'Done' }))

  await waitFor(() => {
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
  expect(await screen.findByText('On.')).toBeVisible()
  expect(screen.getByText('10 recovery codes are left.')).toBeVisible()
  expect(screen.queryByText(RECOVERY_CODES[0] ?? '')).not.toBeInTheDocument()
})

test('the recovery codes can be copied', async () => {
  const state = backendWithTwoFactor(true)
  server.use(
    http.post('/api/auth/two-factor/recovery-codes', () => {
      state.recoveryCodesLeft = 10
      return HttpResponse.json({ recoveryCodes: RECOVERY_CODES })
    }),
  )
  const user = userEvent.setup()
  await openAccountSection()

  await user.click(await screen.findByRole('button', { name: 'Create new recovery codes' }))
  const dialog = await screen.findByRole('dialog', { name: 'Create new recovery codes' })
  await user.type(within(dialog).getByLabelText('Current password'), PASSWORD)
  await user.type(within(dialog).getByLabelText('Code'), '123456')
  await user.click(within(dialog).getByRole('button', { name: 'Create codes' }))
  const codesDialog = await screen.findByRole('dialog', { name: 'Your recovery codes' })
  await user.click(within(codesDialog).getByRole('button', { name: 'Copy' }))

  expect(await screen.findByText('Recovery codes copied.')).toBeVisible()
  expect(await navigator.clipboard.readText()).toBe(`${RECOVERY_CODES.join('\n')}\n`)
  expect(within(codesDialog).getByRole('button', { name: 'Download' })).toBeVisible()
})

test('the dialog of the recovery codes closes only with its button', async () => {
  const state = backendWithTwoFactor(true)
  server.use(
    http.post('/api/auth/two-factor/recovery-codes', () => {
      state.recoveryCodesLeft = 10
      return HttpResponse.json({ recoveryCodes: RECOVERY_CODES })
    }),
  )
  await openAccountSection()

  await userEvent.click(await screen.findByRole('button', { name: 'Create new recovery codes' }))
  const dialog = await screen.findByRole('dialog', { name: 'Create new recovery codes' })
  await userEvent.type(within(dialog).getByLabelText('Current password'), PASSWORD)
  await userEvent.type(within(dialog).getByLabelText('Code'), '123456')
  await userEvent.click(within(dialog).getByRole('button', { name: 'Create codes' }))
  await screen.findByRole('dialog', { name: 'Your recovery codes' })

  await userEvent.keyboard('{Escape}')

  expect(screen.getByRole('dialog', { name: 'Your recovery codes' })).toBeVisible()
})

test('a wrong password is shown at its field and nothing is turned on', async () => {
  backendWithTwoFactor(false)
  server.use(
    http.post('/api/auth/two-factor/setup', () =>
      fieldProblem('currentPassword', 'The current password is wrong.'),
    ),
  )
  await openAccountSection()

  await userEvent.click(await screen.findByRole('button', { name: 'Turn on' }))
  const dialog = await screen.findByRole('dialog')
  await userEvent.type(within(dialog).getByLabelText('Current password'), 'not the password')
  await userEvent.click(within(dialog).getByRole('button', { name: 'Continue' }))

  expect(await within(dialog).findByText('The current password is wrong.')).toBeVisible()
  expect(within(dialog).getByLabelText('Current password')).toBeInvalid()
  expect(screen.queryByRole('img')).not.toBeInTheDocument()
})

test('a wrong code keeps the QR code, so the owner tries again', async () => {
  backendWithTwoFactor(false)
  server.use(
    http.post('/api/auth/two-factor/setup', () =>
      HttpResponse.json({ otpauthUri: OTPAUTH_URI, secret: SECRET }),
    ),
    http.post('/api/auth/two-factor/enable', () => fieldProblem('code', 'The code is wrong.')),
  )
  await openAccountSection()

  await userEvent.click(await screen.findByRole('button', { name: 'Turn on' }))
  const dialog = await screen.findByRole('dialog')
  await userEvent.type(within(dialog).getByLabelText('Current password'), PASSWORD)
  await userEvent.click(within(dialog).getByRole('button', { name: 'Continue' }))
  const scanDialog = await screen.findByRole('dialog', { name: 'Scan the QR code' })
  await userEvent.type(within(scanDialog).getByLabelText('Code from the app'), '000000')
  await userEvent.click(within(scanDialog).getByRole('button', { name: 'Turn on' }))

  expect(await within(scanDialog).findByText('The code is wrong.')).toBeVisible()
  expect(within(scanDialog).getByRole('img')).toBeVisible()
})

test('turning it off asks for password and code and sends both', async () => {
  const state = backendWithTwoFactor(true)
  let sentBody: unknown
  server.use(
    http.delete('/api/auth/two-factor', async ({ request }) => {
      sentBody = await request.json()
      state.enabled = false
      state.recoveryCodesLeft = 0
      return new HttpResponse(null, { status: 204 })
    }),
  )
  await openAccountSection()
  expect(await screen.findByText('On.')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: 'Turn off' }))
  const dialog = await screen.findByRole('dialog', { name: 'Turn off two-factor authentication' })
  await userEvent.click(within(dialog).getByRole('button', { name: 'Turn off' }))
  expect(await within(dialog).findByText('Enter your current password.')).toBeVisible()
  expect(within(dialog).getByText('Enter the code.')).toBeVisible()
  expect(sentBody).toBeUndefined()

  await userEvent.type(within(dialog).getByLabelText('Current password'), PASSWORD)
  await userEvent.type(within(dialog).getByLabelText('Code'), ' 123456 ')
  await userEvent.click(within(dialog).getByRole('button', { name: 'Turn off' }))

  expect(await screen.findByText('Two-factor authentication is off.')).toBeVisible()
  expect(sentBody).toEqual({ currentPassword: PASSWORD, code: '123456' })
  expect(await screen.findByText('Off.')).toBeVisible()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

test('a wrong code when turning off is shown at its field', async () => {
  backendWithTwoFactor(true)
  server.use(http.delete('/api/auth/two-factor', () => fieldProblem('code', 'The code is wrong.')))
  await openAccountSection()

  await userEvent.click(await screen.findByRole('button', { name: 'Turn off' }))
  const dialog = await screen.findByRole('dialog')
  await userEvent.type(within(dialog).getByLabelText('Current password'), PASSWORD)
  await userEvent.type(within(dialog).getByLabelText('Code'), '000000')
  await userEvent.click(within(dialog).getByRole('button', { name: 'Turn off' }))

  expect(await within(dialog).findByText('The code is wrong.')).toBeVisible()
  expect(within(dialog).getByLabelText('Code')).toBeInvalid()
  expect(screen.getByText('On.')).toBeInTheDocument()
})

test('with one recovery code left, the section says so', async () => {
  const state = backendWithTwoFactor(true)
  state.recoveryCodesLeft = 1
  await openAccountSection()

  expect(await screen.findByText('1 recovery code is left.')).toBeVisible()
})
