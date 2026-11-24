import { useId, useState, type SyntheticEvent } from 'react'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { type PostingIdentity, useSaveIdentity } from '@/posting/postingState'
import { signatureLines } from '@/posting/quoting'

/** `local@domain`; the backend has the full rule. */
const EMAIL_FORM = /^[^\s@]+@[^\s@]+$/

interface IdentityFormProps {
  serverId: number
  identity: PostingIdentity
  /** The text of the button that saves. */
  saveLabel: string
  /** Called after the identity was stored. */
  onSaved?: () => void
}

/**
 * The posting identity (spec 009): the name, the email address and the signature the owner posts
 * with. Shown in the settings and, before the first post, in the editor.
 */
export function IdentityForm({ serverId, identity, saveLabel, onSaved }: IdentityFormProps) {
  const saveIdentity = useSaveIdentity(serverId)
  const signatureId = useId()
  const [name, setName] = useState(identity.name ?? '')
  const [email, setEmail] = useState(identity.email ?? '')
  const [signature, setSignature] = useState(identity.signature ?? '')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [failure, setFailure] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  async function save(event: SyntheticEvent) {
    event.preventDefault()
    // The form may stand inside another one.
    event.stopPropagation()
    const found: Record<string, string> = {}
    if (name.trim() === '') {
      found.name = 'Enter the name your posts are sent with.'
    }
    if (!EMAIL_FORM.test(email.trim())) {
      found.email = 'Enter an email address of the form name@example.org.'
    }
    setErrors(found)
    setFailure(null)
    if (Object.keys(found).length > 0) {
      return
    }
    setSaving(true)
    const refused = await saveIdentity({
      name: name.trim(),
      email: email.trim(),
      signature: signature.trim() === '' ? null : signature,
    })
    setSaving(false)
    if (refused === null) {
      onSaved?.()
      return
    }
    setErrors(Object.fromEntries(refused.fields.map(({ field, message }) => [field, message])))
    setFailure(refused.fields.length === 0 && refused.message !== '' ? refused.message : null)
  }

  return (
    <form noValidate onSubmit={(event) => void save(event)} className="grid max-w-md gap-4">
      <FormField
        label="Name"
        autoComplete="name"
        value={name}
        error={errors.name}
        onChange={(event) => {
          setName(event.target.value)
        }}
      />
      <FormField
        label="Email address"
        type="email"
        autoComplete="email"
        value={email}
        error={errors.email}
        hint="It is shown with every post and is not verified. To keep spam away, you can use an address that is disguised, such as jane@example.invalid."
        onChange={(event) => {
          setEmail(event.target.value)
        }}
      />
      <div className="grid gap-2">
        <Label htmlFor={signatureId}>Signature (optional)</Label>
        <Textarea
          id={signatureId}
          rows={3}
          value={signature}
          aria-invalid={errors.signature !== undefined}
          aria-describedby={`${signatureId}-hint`}
          onChange={(event) => {
            setSignature(event.target.value)
          }}
        />
        <p id={`${signatureId}-hint`} className="text-xs text-muted-foreground">
          Added below your posts, after a line with two dashes.
        </p>
        {signatureLines(signature) > 4 && (
          <p role="status" className="text-sm text-warning">
            A signature of more than 4 lines is considered impolite on Usenet.
          </p>
        )}
        {errors.signature !== undefined && (
          <p className="text-sm text-destructive">{errors.signature}</p>
        )}
      </div>
      {failure !== null && (
        <p role="alert" className="text-sm text-destructive">
          {failure}
        </p>
      )}
      <div>
        <Button type="submit" disabled={saving}>
          {saving ? 'Saving…' : saveLabel}
        </Button>
      </div>
    </form>
  )
}
