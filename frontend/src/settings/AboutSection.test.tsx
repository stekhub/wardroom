import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import { REPOSITORY } from '@/settings/AboutSection'
import { backendLoggedIn, problem } from '@/test/backend'
import { server } from '@/test/server'

async function openAboutSection() {
  window.history.replaceState(null, '', '/settings/about')
  render(<App />)
  await screen.findByRole('heading', { name: 'About' })
}

test('the section shows the version of the backend', async () => {
  backendLoggedIn()

  await openAboutSection()

  expect(await screen.findByText('Version 0.9.0')).toBeVisible()
  expect(screen.getByRole('img', { name: 'Wardroom' })).toBeVisible()
})

test('a build without version information is a development build', async () => {
  backendLoggedIn()
  server.use(http.get('/api/about', () => HttpResponse.json({ version: null })))

  await openAboutSection()

  expect(await screen.findByText('Development build')).toBeVisible()
})

test('a version that cannot be loaded is said so, and the links stay', async () => {
  backendLoggedIn()
  server.use(http.get('/api/about', () => problem(500, 'Something went wrong.')))

  await openAboutSection()

  expect(await screen.findByText('The version could not be loaded.')).toBeVisible()
  expect(screen.getByRole('link', { name: /Source code/ })).toBeVisible()
})

test('the license, the source code, the user guide and the issues are linked in a new tab', async () => {
  backendLoggedIn()

  await openAboutSection()

  expect(
    screen.getByText(
      /free software under the GNU Affero General Public License, version 3 or later/,
    ),
  ).toBeVisible()
  const links = {
    license: `${REPOSITORY}/blob/main/LICENSE`,
    'additional terms': `${REPOSITORY}/blob/main/LICENSE-ADDITIONAL-TERMS.md`,
    'Source code': REPOSITORY,
    'User guide': `${REPOSITORY}/blob/main/docs/user-guide.md`,
    'Report a problem': `${REPOSITORY}/issues`,
  }
  for (const [name, address] of Object.entries(links)) {
    const link = screen.getByRole('link', { name: `${name} (opens in a new tab)` })
    expect(link).toHaveAttribute('href', address)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  }
  expect(REPOSITORY).toBe('https://github.com/stekhub/wardroom')
})
