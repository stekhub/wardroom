import { TriangleAlert } from 'lucide-react'
import { useEffect, useId, useRef, useState, type SyntheticEvent } from 'react'
import { Link, useBlocker } from 'react-router'
import { FormField } from '@/components/FormField'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { IdentityForm } from '@/posting/IdentityForm'
import {
  type ArticleDraft,
  identityIsSet,
  postArticle,
  type PostedArticle,
  type PostingFailure,
  type ServerFailure,
  usePostingIdentity,
} from '@/posting/postingState'
import { mostlyQuoted } from '@/posting/quoting'
import { useServers } from '@/server/serverState'

/** What the owner can do about a post the server did not take (spec 009, "Sending and errors"). */
const HINTS: Record<ServerFailure, string | null> = {
  POSTING_NOT_ALLOWED:
    "Your provider does not allow posting with this account. Some providers require posting to be activated in the account settings, or do not include it in trial or block accounts. Check your provider's account page or support. Some providers take posts only on a separate server; you can set one in the server settings.",
  AUTH_REQUIRED:
    'This server requires a login for posting. Add username and password in the server settings.',
  // The message of the server says what it did not accept.
  POSTING_FAILED: null,
  UNREACHABLE: 'The server could not be reached. Your text is kept; try again.',
}

const FAILURE_TITLES: Record<ServerFailure, string> = {
  POSTING_NOT_ALLOWED: 'Posting is not allowed',
  AUTH_REQUIRED: 'The server requires a login',
  POSTING_FAILED: 'The server did not accept the post',
  UNREACHABLE: 'The server could not be reached',
}

interface ArticleEditorProps {
  groupId: number
  /** The article that is answered; `null` for a new discussion. */
  parentId: number | null
  /** The subject and the text the editor starts with. */
  initialSubject: string
  initialBody: string
  /** The groups the article is posted to; they come from the server and are shown as text. */
  targetGroups: string[]
  /** Whether the group is moderated. */
  moderated: boolean
  onPosted: (posted: PostedArticle) => void
  /** Called when the owner gives the draft up. */
  onClose: () => void
}

/**
 * The editor for a new discussion or a reply (spec 009): a subject, a plain text area, and what is
 * needed around them. Without a posting identity it asks for one first. The draft is plain text
 * and is never saved; leaving the editor with a changed draft asks before it is discarded.
 */
export function ArticleEditor(props: ArticleEditorProps) {
  const serverId = useServers().data?.[0]?.id
  const identity = usePostingIdentity(serverId)

  if (identity.data === undefined || serverId === undefined) {
    return identity.isError ? (
      <p role="alert" className="text-sm text-destructive">
        The editor could not be opened. Try again.
      </p>
    ) : (
      <div role="status" aria-label="Loading the editor" className="grid gap-2">
        <Skeleton className="h-8 w-full" />
        <Skeleton className="h-24 w-full" />
      </div>
    )
  }
  if (!identityIsSet(identity.data)) {
    return (
      <section aria-label="Posting identity" className="grid gap-4 rounded-lg border p-4">
        <div>
          <h2 className="text-base font-medium">Who are you posting as?</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            Your posts are sent with a name and an email address. You set them once and can change
            them in the settings under Posting identity.
          </p>
        </div>
        <IdentityForm serverId={serverId} identity={identity.data} saveLabel="Save and continue" />
        <div>
          <Button type="button" variant="ghost" size="sm" onClick={props.onClose}>
            Cancel
          </Button>
        </div>
      </section>
    )
  }
  return <Editor {...props} signature={identity.data.signature ?? null} />
}

function Editor({
  groupId,
  parentId,
  initialSubject,
  initialBody,
  targetGroups,
  moderated,
  onPosted,
  onClose,
  signature,
}: ArticleEditorProps & { signature: string | null }) {
  const textId = useId()
  const signatureId = useId()
  const [subject, setSubject] = useState(initialSubject)
  const [text, setText] = useState(initialBody)
  const [includeSignature, setIncludeSignature] = useState(true)
  const [sending, setSending] = useState(false)
  const [failure, setFailure] = useState<PostingFailure | null>(null)
  /** The draft of the attempt that failed; trying again sends exactly this one. */
  const [failedDraft, setFailedDraft] = useState<ArticleDraft | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [confirmingDiscard, setConfirmingDiscard] = useState(false)
  const textArea = useRef<HTMLTextAreaElement | null>(null)

  const changed = subject !== initialSubject || text !== initialBody
  // Read when a navigation begins, which can be right after the post was sent.
  const guarded = useRef(changed)
  useEffect(() => {
    guarded.current = changed
  }, [changed])
  const blocker = useBlocker(() => guarded.current)

  useEffect(() => {
    if (!changed) {
      return
    }
    // Closing or reloading the tab; the browser asks with its own words.
    const ask = (event: BeforeUnloadEvent) => {
      event.preventDefault()
    }
    window.addEventListener('beforeunload', ask)
    return () => {
      window.removeEventListener('beforeunload', ask)
    }
  }, [changed])

  useEffect(() => {
    // The owner writes below the quoted text.
    const area = textArea.current
    if (area !== null) {
      area.focus()
      area.setSelectionRange(area.value.length, area.value.length)
    }
  }, [])

  async function send(draft: ArticleDraft) {
    setSending(true)
    const result = await postArticle(groupId, draft)
    setSending(false)
    if ('posted' in result) {
      guarded.current = false
      onPosted(result.posted)
      return
    }
    setFailure(result.failure)
    setFailedDraft(result.failure.serverFailure === null ? null : draft)
    setFieldErrors(
      Object.fromEntries(result.failure.fields.map(({ field, message }) => [field, message])),
    )
  }

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    const found: Record<string, string> = {}
    if (subject.trim() === '') {
      found.subject = 'Enter a subject.'
    }
    if (text.trim() === '') {
      found.body = 'Enter a text.'
    }
    setFieldErrors(found)
    setFailure(null)
    setFailedDraft(null)
    if (Object.keys(found).length > 0) {
      return
    }
    void send({ parentId, subject, body: text, includeSignature, messageId: null })
  }

  function tryAgain() {
    if (failedDraft !== null && failure !== null) {
      void send({ ...failedDraft, messageId: failure.messageId })
    }
  }

  function cancel() {
    if (changed) {
      setConfirmingDiscard(true)
    } else {
      onClose()
    }
  }

  const leaving = blocker.state === 'blocked'
  const serverFailure = failure?.serverFailure ?? null

  return (
    <form
      noValidate
      aria-label={parentId === null ? 'New discussion' : 'Reply'}
      onSubmit={submit}
      className="grid gap-4"
    >
      <FormField
        label="Subject"
        value={subject}
        error={fieldErrors.subject}
        onChange={(event) => {
          setSubject(event.target.value)
        }}
      />
      <div className="grid gap-2">
        <Label htmlFor={textId}>Text</Label>
        <Textarea
          ref={textArea}
          id={textId}
          rows={10}
          className="font-mono"
          value={text}
          aria-invalid={fieldErrors.body !== undefined}
          onChange={(event) => {
            setText(event.target.value)
          }}
        />
        {fieldErrors.body !== undefined && (
          <p className="text-sm text-destructive">{fieldErrors.body}</p>
        )}
        {mostlyQuoted(text) && (
          <p role="status" className="text-sm text-amber-700">
            Most of this text is quoted. Some servers reject such posts; you can shorten the quote.
          </p>
        )}
      </div>
      {signature !== null && (
        <div className="flex items-center gap-2">
          <Checkbox
            id={signatureId}
            checked={includeSignature}
            onCheckedChange={(checked) => {
              setIncludeSignature(checked === true)
            }}
          />
          <Label htmlFor={signatureId}>Add my signature</Label>
        </div>
      )}
      <p className="text-sm text-muted-foreground">
        {/* Group names come from an article or the server and are shown as text only. */}
        Posted to <span className="break-all text-foreground">{targetGroups.join(', ')}</span>
        {moderated && <> · Your post is sent to the moderator and appears after approval</>}
      </p>
      {failure !== null && serverFailure === null && failure.fields.length === 0 && (
        <p role="alert" className="text-sm text-destructive">
          {failure.message}
        </p>
      )}
      {failure !== null && serverFailure !== null && (
        <Alert variant="destructive">
          <TriangleAlert />
          <AlertTitle>{FAILURE_TITLES[serverFailure]}</AlertTitle>
          <AlertDescription>
            {/* The message comes from the server and is shown as text only. */}
            {serverFailure !== 'UNREACHABLE' && <p className="break-words">{failure.message}</p>}
            {HINTS[serverFailure] !== null && <p>{HINTS[serverFailure]}</p>}
            {(serverFailure === 'AUTH_REQUIRED' || serverFailure === 'POSTING_NOT_ALLOWED') && (
              <p>
                <Link to="/settings/server" className="font-medium underline underline-offset-4">
                  Open the server settings
                </Link>
              </p>
            )}
            <div className="mt-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={sending}
                onClick={tryAgain}
              >
                Try again
              </Button>
            </div>
          </AlertDescription>
        </Alert>
      )}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={sending}>
          {sending ? 'Sending…' : 'Send'}
        </Button>
        <Button type="button" variant="ghost" disabled={sending} onClick={cancel}>
          Cancel
        </Button>
      </div>
      <AlertDialog
        open={confirmingDiscard || leaving}
        onOpenChange={(open) => {
          if (!open) {
            setConfirmingDiscard(false)
            if (blocker.state === 'blocked') {
              blocker.reset()
            }
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Discard draft?</AlertDialogTitle>
            <AlertDialogDescription>
              Drafts are not saved. What you wrote is lost when you leave the editor.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep writing</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                guarded.current = false
                if (blocker.state === 'blocked') {
                  blocker.proceed()
                } else {
                  onClose()
                }
              }}
            >
              Discard
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </form>
  )
}
