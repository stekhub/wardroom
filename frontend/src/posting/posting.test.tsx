import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { App } from '@/App'
import type { components } from '@/api/schema'
import {
  backendLoggedIn,
  problem,
  testSubscription,
  testThreadArticle,
  testThreadPage,
} from '@/test/backend'
import { server } from '@/test/server'

type PostingIdentity = components['schemas']['PostingIdentity']
type ThreadArticle = components['schemas']['ThreadArticle']
type Subscription = components['schemas']['Subscription']

const JANE: PostingIdentity = { name: 'Jane Doe', email: 'jane@example.org', signature: null }
const MESSAGE_ID = '<abcdefghijklmnopqrst@example.org>'

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<App />)
}

interface Start {
  identity?: PostingIdentity
  articles?: ThreadArticle[]
  postingStatus?: Subscription['postingStatus']
  /** The text and the headers of every article of the thread. */
  body?: string
  rawHeaders?: string
}

/**
 * A backend with the subscribed group 5 on server 1, a thread whose articles all have the same
 * text, and a posting identity. A post is accepted and gets the article ID 90.
 */
function backendForPosting(start: Start = {}) {
  backendLoggedIn()
  const state = {
    identity: start.identity ?? JANE,
    articles: start.articles ?? [testThreadArticle(31, null, 0)],
    storedIdentities: [] as unknown[],
    drafts: [] as unknown[],
    threadRequests: 0,
  }
  server.use(
    http.get('/api/subscriptions', () =>
      HttpResponse.json([
        testSubscription(5, 'example.talk', { postingStatus: start.postingStatus ?? 'ALLOWED' }),
      ]),
    ),
    http.get('/api/groups/:groupId/threads', () => HttpResponse.json(testThreadPage([]))),
    http.get('/api/groups/:groupId/threads/:articleId', () => {
      state.threadRequests++
      return HttpResponse.json({
        rootId: state.articles[0]?.id ?? 0,
        ignored: false,
        ignoreRuleIds: [],
        articles: state.articles,
      })
    }),
    http.get('/api/articles/:articleId/content', ({ params }) =>
      HttpResponse.json({
        articleId: Number(params.articleId),
        body: start.body ?? 'First line\nSecond line\n-- \nAda',
        flowed: false,
        delSp: false,
        truncated: false,
        omittedParts: [],
        rawHeaders: start.rawHeaders ?? 'Subject: Hello\r\nNewsgroups: example.talk\r\n',
      }),
    ),
    http.put('/api/articles/:articleId/read', () => new HttpResponse(null, { status: 204 })),
    http.get('/api/servers/:serverId/identity', () => HttpResponse.json(state.identity)),
    http.put('/api/servers/:serverId/identity', async ({ request }) => {
      const identity = (await request.json()) as PostingIdentity
      state.storedIdentities.push(identity)
      state.identity = identity
      return HttpResponse.json(identity)
    }),
    http.post('/api/groups/:groupId/articles', async ({ request }) => {
      state.drafts.push(await request.json())
      return HttpResponse.json(
        { articleId: 90, rootId: 31, messageId: MESSAGE_ID },
        { status: 201 },
      )
    }),
  )
  return state
}

/** The answer of a server that did not take the article. */
function refusal(type: string, detail: string) {
  return HttpResponse.json(
    { type, title: 'Bad Gateway', status: 502, detail, messageId: MESSAGE_ID },
    { status: 502, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

async function openReply() {
  await screen.findByText(/First line/)
  await userEvent.click(screen.getByRole('button', { name: 'Reply' }))
  return within(await screen.findByRole('form', { name: 'Reply' }))
}

// Posting identity

test('an editor without an identity asks for it first and continues with the editor', async () => {
  const backend = backendForPosting({ identity: { name: null, email: null, signature: null } })
  openAt('/groups/5/new')

  const prompt = within(await screen.findByRole('region', { name: 'Posting identity' }))
  expect(prompt.getByText('Who are you posting as?')).toBeVisible()
  expect(screen.queryByRole('textbox', { name: 'Subject' })).not.toBeInTheDocument()

  await userEvent.click(prompt.getByRole('button', { name: 'Save and continue' }))
  expect(prompt.getByText('Enter the name your posts are sent with.')).toBeVisible()
  expect(prompt.getByText('Enter an email address of the form name@example.org.')).toBeVisible()
  expect(backend.storedIdentities).toEqual([])

  await userEvent.type(prompt.getByRole('textbox', { name: 'Name' }), 'Jane Doe')
  await userEvent.type(prompt.getByRole('textbox', { name: 'Email address' }), 'jane@example.org')
  await userEvent.click(prompt.getByRole('button', { name: 'Save and continue' }))

  expect(await screen.findByRole('textbox', { name: 'Subject' })).toBeVisible()
  expect(backend.storedIdentities).toEqual([
    { name: 'Jane Doe', email: 'jane@example.org', signature: null },
  ])
})

test('the settings section stores the identity and warns about a long signature', async () => {
  const backend = backendForPosting({ identity: { ...JANE, signature: 'Jane' } })
  openAt('/settings/posting-identity')

  const name = await screen.findByRole('textbox', { name: 'Name' })
  expect(name).toHaveValue('Jane Doe')
  expect(screen.getByRole('textbox', { name: 'Email address' })).toHaveValue('jane@example.org')
  const signature = screen.getByRole('textbox', { name: 'Signature (optional)' })
  expect(screen.queryByText(/more than 4 lines/)).not.toBeInTheDocument()

  await userEvent.clear(signature)
  await userEvent.type(signature, '1{Enter}2{Enter}3{Enter}4{Enter}5')
  expect(screen.getByText(/more than 4 lines/)).toBeVisible()
  await userEvent.clear(name)
  await userEvent.type(name, 'J. Doe')
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('Posting identity saved')).toBeVisible()
  expect(backend.storedIdentities).toEqual([
    { name: 'J. Doe', email: 'jane@example.org', signature: '1\n2\n3\n4\n5' },
  ])
})

test('an identity the backend refuses shows the rule at its field', async () => {
  backendForPosting()
  server.use(
    http.put('/api/servers/:serverId/identity', () =>
      HttpResponse.json(
        {
          status: 400,
          detail: 'Invalid',
          errors: [{ field: 'email', message: 'Enter an email address of the form x.' }],
        },
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  )
  openAt('/settings/posting-identity')

  await userEvent.click(await screen.findByRole('button', { name: 'Save' }))

  expect(await screen.findByText('Enter an email address of the form x.')).toBeVisible()
})

// New discussion

test('a new discussion is sent from its own page and leads to its thread', async () => {
  const backend = backendForPosting({ identity: { ...JANE, signature: 'Jane' } })
  openAt('/groups/5')

  await userEvent.click(await screen.findByRole('link', { name: 'New discussion' }))
  expect(window.location.pathname).toBe('/groups/5/new')
  const editor = within(await screen.findByRole('form', { name: 'New discussion' }))
  expect(editor.getByText('example.talk')).toBeVisible()
  expect(editor.getByRole('checkbox', { name: 'Add my signature' })).toBeChecked()

  await userEvent.click(editor.getByRole('button', { name: 'Send' }))
  expect(editor.getByText('Enter a subject.')).toBeVisible()
  expect(editor.getByText('Enter a text.')).toBeVisible()
  expect(backend.drafts).toEqual([])

  await userEvent.type(editor.getByRole('textbox', { name: 'Subject' }), 'Hello 🙂')
  await userEvent.type(editor.getByRole('textbox', { name: 'Text' }), 'First{Enter}Second')
  await userEvent.click(editor.getByRole('checkbox', { name: 'Add my signature' }))
  await userEvent.click(editor.getByRole('button', { name: 'Send' }))

  await waitFor(() => {
    expect(window.location.pathname).toBe('/groups/5/threads/90')
  })
  expect(backend.drafts).toEqual([
    {
      parentId: null,
      subject: 'Hello 🙂',
      body: 'First\nSecond',
      includeSignature: false,
      messageId: null,
    },
  ])
})

test('a group that does not allow posting has no "New discussion" and no "Reply"', async () => {
  backendForPosting({ postingStatus: 'NOT_ALLOWED' })
  const { unmount } = openAt('/groups/5')
  await screen.findByText('No posting')
  expect(screen.queryByRole('link', { name: 'New discussion' })).not.toBeInTheDocument()
  unmount()

  openAt('/groups/5/threads/31')
  await screen.findByText(/First line/)
  expect(screen.queryByRole('button', { name: 'Reply' })).not.toBeInTheDocument()
  unmount()

  openAt('/groups/5/new')
  expect(await screen.findByText('This group does not allow posting')).toBeVisible()
})

test('a moderated group says that the post goes to the moderator', async () => {
  backendForPosting({ postingStatus: 'MODERATED' })
  openAt('/groups/5/new')

  expect(
    await screen.findByText(/Your post is sent to the moderator and appears after approval/),
  ).toBeVisible()
})

// Replies

test('a reply starts with the quoted text of the article, without its signature', async () => {
  const backend = backendForPosting({
    rawHeaders: 'Newsgroups: example.talk,example.other\r\nFollowup-To: example.followup\r\n',
  })
  openAt('/groups/5/threads/31')

  const editor = await openReply()

  expect(editor.getByRole('textbox', { name: 'Subject' })).toHaveValue('Re: Hello')
  expect(editor.getByRole('textbox', { name: 'Text' })).toHaveValue(
    'Ada Lovelace wrote:\n> First line\n> Second line\n\n',
  )
  // The reply goes where the author asked for it.
  expect(editor.getByText('example.followup')).toBeVisible()
  // No signature is set, so there is nothing to leave out.
  expect(editor.queryByRole('checkbox')).not.toBeInTheDocument()

  await userEvent.type(editor.getByRole('textbox', { name: 'Text' }), 'My answer')
  await userEvent.click(editor.getByRole('button', { name: 'Send' }))

  expect(await screen.findByText('Your post was sent')).toBeVisible()
  expect(screen.queryByRole('form', { name: 'Reply' })).not.toBeInTheDocument()
  expect(backend.drafts).toEqual([
    {
      parentId: 31,
      subject: 'Re: Hello',
      body: 'Ada Lovelace wrote:\n> First line\n> Second line\n\nMy answer',
      includeSignature: true,
      messageId: null,
    },
  ])
  // The thread is asked for again, so the reply appears in it.
  expect(backend.threadRequests).toBe(2)
})

test('only what is selected in the article is quoted', async () => {
  backendForPosting()
  openAt('/groups/5/threads/31')
  const line = await screen.findByText(/Second line/)
  const range = document.createRange()
  const textNode = [...line.childNodes].find((node) => node.textContent?.includes('Second line'))
  const start = (textNode?.textContent ?? '').indexOf('Second')
  range.setStart(textNode as Node, start)
  range.setEnd(textNode as Node, start + 'Second line'.length)
  window.getSelection()?.removeAllRanges()
  window.getSelection()?.addRange(range)

  // A click that leaves the selection as it is.
  fireEvent.click(screen.getByRole('button', { name: 'Reply' }))

  const editor = within(await screen.findByRole('form', { name: 'Reply' }))
  expect(editor.getByRole('textbox', { name: 'Text' })).toHaveValue(
    'Ada Lovelace wrote:\n> Second line\n\n',
  )
  window.getSelection()?.removeAllRanges()
})

test('an article whose author asked for replies by email cannot be answered', async () => {
  backendForPosting({ rawHeaders: 'Newsgroups: example.talk\r\nFollowup-To: poster\r\n' })
  openAt('/groups/5/threads/31')

  expect(
    await screen.findByText('The author asked for replies by email. Wardroom does not send email.'),
  ).toBeVisible()
  expect(screen.queryByRole('button', { name: 'Reply' })).not.toBeInTheDocument()
})

test('a text that is mostly quoted shows a warning until enough is written', async () => {
  backendForPosting()
  openAt('/groups/5/threads/31')

  const editor = await openReply()

  expect(editor.getByText(/Most of this text is quoted/)).toBeVisible()
  await userEvent.type(
    editor.getByRole('textbox', { name: 'Text' }),
    'One{Enter}Two{Enter}Three{Enter}Four',
  )
  expect(editor.queryByText(/Most of this text is quoted/)).not.toBeInTheDocument()
  expect(editor.getByRole('button', { name: 'Send' })).toBeEnabled()
})

test('an article that waits for its place on the server is marked "Sent"', async () => {
  backendForPosting({
    articles: [testThreadArticle(31, null, 0), testThreadArticle(90, 31, 1, { sent: true })],
  })
  openAt('/groups/5/threads/31')

  await screen.findAllByText(/First line/)
  expect(screen.getAllByText('Sent')).toHaveLength(1)
})

// Drafts

test('leaving an editor with a changed draft asks before it is discarded', async () => {
  backendForPosting()
  openAt('/groups/5/threads/31')

  // An untouched reply closes at once.
  let editor = await openReply()
  await userEvent.click(editor.getByRole('button', { name: 'Cancel' }))
  expect(screen.queryByRole('form', { name: 'Reply' })).not.toBeInTheDocument()

  editor = await openReply()
  await userEvent.type(editor.getByRole('textbox', { name: 'Text' }), 'My answer')
  await userEvent.click(editor.getByRole('button', { name: 'Cancel' }))
  let dialog = within(await screen.findByRole('alertdialog'))
  expect(dialog.getByText('Discard draft?')).toBeVisible()
  await userEvent.click(dialog.getByRole('button', { name: 'Keep writing' }))
  expect(screen.getByRole('textbox', { name: 'Text' })).toHaveValue(
    'Ada Lovelace wrote:\n> First line\n> Second line\n\nMy answer',
  )

  // Leaving the page asks as well.
  await userEvent.click(screen.getByRole('link', { name: 'Back to example.talk' }))
  dialog = within(await screen.findByRole('alertdialog'))
  await userEvent.click(dialog.getByRole('button', { name: 'Keep writing' }))
  expect(window.location.pathname).toBe('/groups/5/threads/31')

  await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
  dialog = within(await screen.findByRole('alertdialog'))
  await userEvent.click(dialog.getByRole('button', { name: 'Discard' }))
  await waitFor(() => {
    expect(screen.queryByRole('form', { name: 'Reply' })).not.toBeInTheDocument()
  })
  expect(window.location.pathname).toBe('/groups/5/threads/31')
})

// Errors

test('a post the server does not take shows its message and a hint for each kind of failure', async () => {
  const backend = backendForPosting()
  const answers = [
    refusal('POSTING_NOT_ALLOWED', 'Posting not permitted'),
    refusal('AUTH_REQUIRED', 'Authentication required'),
    refusal('POSTING_FAILED', 'Too much quoted text'),
    refusal('UNREACHABLE', 'Connection refused'),
  ]
  server.use(
    http.post('/api/groups/:groupId/articles', async ({ request }) => {
      backend.drafts.push(await request.json())
      return (
        answers.shift() ??
        HttpResponse.json({ articleId: 90, rootId: 31, messageId: MESSAGE_ID }, { status: 201 })
      )
    }),
  )
  openAt('/groups/5/threads/31')
  const editor = await openReply()

  await userEvent.click(editor.getByRole('button', { name: 'Send' }))
  expect(await editor.findByText('Posting not permitted')).toBeVisible()
  expect(editor.getByText(/Your provider does not allow posting with this account/)).toBeVisible()

  await userEvent.click(editor.getByRole('button', { name: 'Try again' }))
  expect(await editor.findByText('Authentication required')).toBeVisible()
  expect(editor.getByText(/This server requires a login for posting/)).toBeVisible()
  expect(editor.getByRole('link', { name: 'Open the server settings' })).toHaveAttribute(
    'href',
    '/settings/server',
  )

  await userEvent.click(editor.getByRole('button', { name: 'Try again' }))
  expect(await editor.findByText('Too much quoted text')).toBeVisible()

  await userEvent.click(editor.getByRole('button', { name: 'Try again' }))
  expect(
    await editor.findByText('The server could not be reached. Your text is kept; try again.'),
  ).toBeVisible()
  expect(editor.getByRole('textbox', { name: 'Text' })).toHaveValue(
    'Ada Lovelace wrote:\n> First line\n> Second line\n\n',
  )

  await userEvent.click(editor.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByText('Your post was sent')).toBeVisible()

  // The first attempt has no Message-ID; every further one sends the one of the failed attempt.
  expect(backend.drafts.map((draft) => (draft as { messageId: string | null }).messageId)).toEqual([
    null,
    MESSAGE_ID,
    MESSAGE_ID,
    MESSAGE_ID,
    MESSAGE_ID,
  ])
})

test('a post the backend does not accept says why and offers no second attempt', async () => {
  backendForPosting()
  server.use(
    http.post('/api/groups/:groupId/articles', () =>
      problem(409, 'Set your posting identity before you post.'),
    ),
  )
  openAt('/groups/5/threads/31')
  const editor = await openReply()

  await userEvent.click(editor.getByRole('button', { name: 'Send' }))

  expect(await editor.findByText('Set your posting identity before you post.')).toBeVisible()
  expect(editor.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
})
