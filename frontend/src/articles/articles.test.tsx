import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, expect, test, vi } from 'vitest'
import { App } from '@/App'
import type { components } from '@/api/schema'
import { ArticleContent } from '@/articles/ArticleContent'
import { ArticleText } from '@/articles/ArticleText'
import { authorColors } from '@/articles/authorColors'
import { AuthorIcon } from '@/articles/AuthorIcon'
import { backendLoggedIn, problem } from '@/test/backend'
import { server } from '@/test/server'

type Content = components['schemas']['ArticleContent']

function content(overrides: Partial<Content> = {}): Content {
  return {
    articleId: 7,
    body: 'Hello',
    flowed: false,
    delSp: false,
    truncated: false,
    omittedParts: [],
    rawHeaders: 'Subject: Hello\r\nFrom: Ada <ada@example.org>',
    ...overrides,
  }
}

function renderText(body: string, options: { flowed?: boolean; delSp?: boolean } = {}) {
  return render(
    <ArticleText body={body} flowed={options.flowed ?? false} delSp={options.delSp ?? false} />,
  )
}

/** A backend that has the content of article 7 and remembers what it was asked. */
function backendWithContent(answer: () => Response = () => HttpResponse.json(content())) {
  const state = { fetches: 0, retries: 0, read: [] as string[] }
  server.use(
    http.get('/api/subscriptions', () => HttpResponse.json([])),
    http.get('/api/articles/:articleId/content', () => {
      state.fetches++
      return answer()
    }),
    http.post('/api/articles/:articleId/content/retry', () => {
      state.retries++
      return answer()
    }),
    http.put('/api/articles/:articleId/read', ({ params }) => {
      state.read.push(String(params.articleId))
      return new HttpResponse(null, { status: 204 })
    }),
  )
  return state
}

function renderContent(onRead?: () => void) {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <ArticleContent articleId={7} onRead={onRead} />
    </QueryClientProvider>,
  )
}

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

afterEach(() => {
  vi.unstubAllGlobals()
})

// Article text (spec 007, "Display")

test('quoted lines get a bar per quote level', () => {
  const { container } = renderText('Ada wrote:\n> first\n>> second\nMy answer')

  expect(screen.getByText('Ada wrote:')).toBeInTheDocument()
  const quote = container.querySelector('blockquote')
  expect(quote).not.toBeNull()
  const first = within(quote as HTMLElement).getByText('first')
  expect(first).toHaveAttribute('data-quote-level', '1')
  const second = within(quote as HTMLElement).getByText('second')
  expect(second).toHaveAttribute('data-quote-level', '2')
  // The second level is drawn inside a bar of the first level.
  expect(second.parentElement).toHaveAttribute('data-quote-level', '1')
  expect(first.className).not.toBe(second.className)
})

test('a quote of up to five lines is shown, a longer one is collapsed until it is asked for', async () => {
  renderText('> 1\n> 2\n> 3\n> 4\n> 5\nBetween\n> a\n> b\n> c\n> d\n> e\n> f')

  expect(screen.getByText('1 2 3 4 5')).toBeInTheDocument()
  expect(screen.queryByText(/^a b/)).not.toBeInTheDocument()

  await userEvent.click(screen.getByRole('button', { name: 'Show quoted text' }))
  expect(screen.getByText('a b c d e f')).toBeInTheDocument()

  await userEvent.click(screen.getByRole('button', { name: 'Hide quoted text' }))
  expect(screen.queryByText(/^a b/)).not.toBeInTheDocument()
})

test('the signature is shown dimmed and line breaks are kept', () => {
  const { container } = renderText('First line\n\n  indented\n-- \nAda\nexample.org')

  const signature = container.querySelector('[data-signature]')
  expect(signature).toHaveTextContent('-- Ada example.org')
  expect(signature).toHaveClass('text-muted-foreground')
  expect(screen.getByText(/First line/).textContent).toBe('First line\n\n  indented')
  expect(container.firstElementChild).toHaveClass('whitespace-pre-wrap')
})

test('flowed text is unwrapped before it is shown', () => {
  renderText('A paragraph that \nruns on.\n> a quote that \n> runs on too', { flowed: true })

  expect(screen.getByText('A paragraph that runs on.')).toBeInTheDocument()
  expect(screen.getByText('a quote that runs on too')).toBeInTheDocument()
})

test('a link in an article leads to the leaving page and not to the target', () => {
  renderText(
    'See https://example.org/a?b=c&d=e, then ftp://example.org/file and javascript:alert(1)',
  )

  const links = screen.getAllByRole('link')
  expect(links).toHaveLength(1)
  expect(links[0]).toHaveTextContent('https://example.org/a?b=c&d=e')
  expect(links[0]).toHaveAttribute(
    'href',
    '/leaving?url=https%3A%2F%2Fexample.org%2Fa%3Fb%3Dc%26d%3De',
  )
  expect(links[0]).toHaveAttribute('target', '_blank')
  expect(links[0]).toHaveAttribute('rel', 'noopener noreferrer')
})

test('article text with HTML and script tags is shown literally', () => {
  const body =
    '<script>window.hacked = true</script><img src=x onerror="window.hacked = true"><b>bold</b>'
  const { container } = renderText(body)

  expect(container).toHaveTextContent(body)
  expect(container.querySelector('script')).toBeNull()
  expect(container.querySelector('img')).toBeNull()
  expect(container.querySelector('b')).toBeNull()
  expect((window as unknown as { hacked?: boolean }).hacked).toBeUndefined()
})

// Content of an article

test('the content is shown, the article is marked as read and the headers can be shown', async () => {
  const backend = backendWithContent()
  const onRead = vi.fn()
  renderContent(onRead)

  expect(await screen.findByText('Hello')).toBeInTheDocument()
  await waitFor(() => {
    expect(backend.read).toEqual(['7'])
  })
  await waitFor(() => {
    expect(onRead).toHaveBeenCalledTimes(1)
  })
  expect(backend.fetches).toBe(1)

  expect(screen.queryByText(/From: Ada/)).not.toBeInTheDocument()
  const headers = screen.getByRole('button', { name: 'Show all headers' })
  expect(headers).toHaveAttribute('aria-pressed', 'false')
  await userEvent.click(headers)
  expect(headers).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByText(/From: Ada <ada@example.org>/)).toBeInTheDocument()
})

test('the fixed width toggle switches the text to a monospace font and back', async () => {
  backendWithContent(() => HttpResponse.json(content({ body: '+---+\n| a |\n+---+' })))
  const { container } = renderContent()

  await screen.findByText(/\| a \|/)
  const text = container.querySelector('[data-fixed-width]')
  expect(text).not.toHaveClass('font-mono')

  const toggle = screen.getByRole('button', { name: 'Fixed width' })
  await userEvent.click(toggle)
  expect(toggle).toHaveAttribute('aria-pressed', 'true')
  expect(text).toHaveClass('font-mono')

  await userEvent.click(toggle)
  expect(text).not.toHaveClass('font-mono')
})

test('the content is fetched only when the article scrolls into view', async () => {
  const backend = backendWithContent()
  let show: () => void = () => undefined
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      constructor(callback: (entries: { isIntersecting: boolean }[]) => void) {
        show = () => {
          callback([{ isIntersecting: true }])
        }
      }
      observe() {
        // The test decides when the article is on screen.
      }
      disconnect() {
        // See observe.
      }
    },
  )
  renderContent()

  expect(screen.getByLabelText('Loading the text')).toBeInTheDocument()
  await new Promise((resolve) => setTimeout(resolve, 50))
  expect(backend.fetches).toBe(0)
  expect(backend.read).toEqual([])

  act(() => {
    show()
  })

  expect(await screen.findByText('Hello')).toBeInTheDocument()
  expect(backend.fetches).toBe(1)
})

test('parts that are not displayed are named, and an article of HTML only says so', async () => {
  backendWithContent(() =>
    HttpResponse.json(
      content({
        body: 'The text.',
        truncated: true,
        omittedParts: [
          { name: null, mediaType: 'text/html', size: 900 },
          { name: 'photo<b>.jpg', mediaType: 'image/jpeg', size: 245_000 },
          { name: null, mediaType: 'application/pdf', size: 2_400_000 },
        ],
      }),
    ),
  )
  const { unmount } = renderContent()

  expect(
    await screen.findByText(
      '2 attachments omitted (photo<b>.jpg, 245 kB; application/pdf, 2.4 MB)',
    ),
  ).toBeInTheDocument()
  expect(screen.getByText(/Only a part of it is shown/)).toBeInTheDocument()
  expect(screen.queryByText('HTML message, not displayed')).not.toBeInTheDocument()
  unmount()

  backendWithContent(() =>
    HttpResponse.json(
      content({
        body: '',
        omittedParts: [{ name: null, mediaType: 'text/html', size: 900 }],
      }),
    ),
  )
  renderContent()

  expect(await screen.findByText('HTML message, not displayed')).toBeInTheDocument()
  expect(screen.queryByText(/omitted/)).not.toBeInTheDocument()
})

test('an article the server no longer has says so, and "Try again" asks the server again', async () => {
  let available = false
  const backend = backendWithContent(() =>
    available
      ? HttpResponse.json(content({ body: 'Back again' }))
      : problem(410, 'This article is no longer available on the server.'),
  )
  renderContent()

  expect(
    await screen.findByText('This article is no longer available on the server.'),
  ).toBeInTheDocument()
  expect(backend.read).toEqual([])

  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
  await waitFor(() => {
    expect(backend.retries).toBe(1)
  })
  expect(
    await screen.findByText('This article is no longer available on the server.'),
  ).toBeInTheDocument()

  available = true
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

  expect(await screen.findByText('Back again')).toBeInTheDocument()
  expect(backend.retries).toBe(2)
  await waitFor(() => {
    expect(backend.read).toEqual(['7'])
  })
})

test('a failure of the server shows the error and a "Try again" button', async () => {
  let failing = true
  backendWithContent(() =>
    failing ? problem(502, 'Cannot connect to news.example.com:563') : HttpResponse.json(content()),
  )
  renderContent()

  expect(
    await screen.findByText('The text could not be loaded: Cannot connect to news.example.com:563'),
  ).toBeInTheDocument()

  failing = false
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

  expect(await screen.findByText('Hello')).toBeInTheDocument()
})

// Author icons (UI concept)

test('the same author always gets the same two colors, and a stub is gray', () => {
  const colors = authorColors('ada@example.org')
  expect(colors).toEqual(authorColors('ada@example.org'))
  expect(colors).not.toEqual(authorColors('bob@example.org'))
  expect(colors.circle).toMatch(/^hsl\(\d{1,3} 55% 42%\)$/)
  expect(colors.symbol).toMatch(/^hsl\(\d{1,3} 70% 90%\)$/)

  const { container, rerender } = render(<AuthorIcon authorKey="ada@example.org" />)
  const icon = container.firstElementChild as HTMLElement
  expect(icon).toHaveAttribute('aria-hidden', 'true')
  expect(icon.style.backgroundColor).not.toBe('')
  expect(icon).toHaveClass('size-8')

  rerender(<AuthorIcon authorKey={null} size="small" />)
  const stubIcon = container.firstElementChild as HTMLElement
  expect(stubIcon.style.backgroundColor).toBe('')
  expect(stubIcon).toHaveClass('size-6')
})

// Leaving page (spec 007)

test('the leaving page shows the target address and its domain, and "Continue" leads to the target', async () => {
  backendLoggedIn()
  openAt(`/leaving?url=${encodeURIComponent('https://login.example.org.evil.example/a?b=c#d')}`)

  expect(
    await screen.findByRole('heading', { name: 'You are leaving Wardroom' }),
  ).toBeInTheDocument()
  expect(screen.getByTestId('target-domain')).toHaveTextContent('login.example.org.evil.example')
  expect(screen.getByTestId('target-address')).toHaveTextContent(
    'https://login.example.org.evil.example/a?b=c#d',
  )
  const proceed = screen.getByRole('link', { name: 'Continue' })
  expect(proceed).toHaveAttribute('href', 'https://login.example.org.evil.example/a?b=c#d')
  expect(proceed).toHaveAttribute('rel', 'noopener noreferrer')
  expect(proceed).not.toHaveAttribute('target')
  // The page has no sidebar.
  expect(screen.queryByRole('link', { name: 'Discover' })).not.toBeInTheDocument()
})

test('the leaving page offers no "Continue" for another scheme or an invalid address', async () => {
  backendLoggedIn()
  for (const address of [
    'javascript:alert(1)',
    'ftp://example.org/file',
    'https://',
    'example.org',
    '',
  ]) {
    const { unmount } = openAt(`/leaving?url=${encodeURIComponent(address)}`)

    expect(
      await screen.findByRole('heading', { name: 'This link cannot be opened' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Continue' })).not.toBeInTheDocument()
    expect(screen.queryAllByRole('link')).toHaveLength(0)
    unmount()
  }
})

test('the leaving page shows an address as text, whatever it contains', async () => {
  backendLoggedIn()
  const address = 'https://example.org/<script>alert(1)</script>'
  const { container } = openAt(`/leaving?url=${encodeURIComponent(address)}`)

  expect(await screen.findByTestId('target-address')).toHaveTextContent(
    'https://example.org/%3Cscript%3Ealert(1)%3C/script%3E',
  )
  expect(container.querySelector('script')).toBeNull()
})

test('"Stay" closes the tab, and leads to the start page where the tab stays open', async () => {
  backendLoggedIn()
  const close = vi.spyOn(window, 'close').mockImplementation(() => undefined)
  openAt(`/leaving?url=${encodeURIComponent('https://example.org/')}`)

  await userEvent.click(await screen.findByRole('button', { name: 'Stay' }))

  expect(close).toHaveBeenCalled()
  await waitFor(() => {
    expect(window.location.pathname).toBe('/discover')
  })
  close.mockRestore()
})

test('the leaving page needs a login like every other view', async () => {
  server.use(
    http.get('/api/auth/me', () => problem(401, 'Log in to use this endpoint.')),
    http.get('/api/auth/setup', () => problem(404, 'An account already exists.')),
  )
  openAt(`/leaving?url=${encodeURIComponent('https://example.org/')}`)

  expect(await screen.findByLabelText('Password')).toBeInTheDocument()
  expect(
    screen.queryByRole('heading', { name: 'You are leaving Wardroom' }),
  ).not.toBeInTheDocument()
})
