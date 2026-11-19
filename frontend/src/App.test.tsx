import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import { server } from '@/test/server'

test('shows the empty page', () => {
  render(<App />)

  expect(screen.getByRole('main')).toBeEmptyDOMElement()
})

test('the mocked backend answers requests', async () => {
  server.use(http.get('/actuator/health', () => HttpResponse.json({ status: 'UP' })))

  const response = await fetch('/actuator/health')

  await expect(response.json()).resolves.toEqual({ status: 'UP' })
})
