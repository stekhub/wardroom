import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { type FieldPath, useForm, type UseFormSetError } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { api } from '@/api/client'
import { problemFieldErrors, problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { QrCode } from '@/settings/QrCode'

const TWO_FACTOR_KEY = ['two-factor'] as const

const ENTER_PASSWORD = 'Enter your current password.'
const ENTER_CODE = 'Enter the code.'

const passwordForm = z.object({ currentPassword: z.string().min(1, ENTER_PASSWORD) })
const codeForm = z.object({ code: z.string().trim().min(1, ENTER_CODE) })
const confirmationForm = z.object({
  currentPassword: z.string().min(1, ENTER_PASSWORD),
  code: z.string().trim().min(1, ENTER_CODE),
})

type PasswordForm = z.infer<typeof passwordForm>
type CodeForm = z.infer<typeof codeForm>
type ConfirmationForm = z.infer<typeof confirmationForm>

/** What the dialog of the section shows. */
type Step =
  | { kind: 'closed' }
  | { kind: 'password' }
  | { kind: 'scan'; otpauthUri: string; secret: string }
  | { kind: 'confirm'; action: 'turnOff' | 'newRecoveryCodes' }
  | { kind: 'recoveryCodes'; recoveryCodes: string[] }

async function fetchTwoFactor() {
  const { data, response } = await api.GET('/api/auth/two-factor')
  if (data === undefined) {
    throw new Error(`Unexpected answer ${String(response.status)} to the two-factor request`)
  }
  return data
}

/**
 * Puts the objection of the backend at the field it is about. An ended session is handled by the
 * API client and shows nothing here.
 */
function showFailure<Form extends Record<string, string>>(
  status: number,
  error: unknown,
  setError: UseFormSetError<Form>,
  fields: readonly FieldPath<Form>[],
) {
  if (status === 401) {
    return
  }
  const fieldErrors = problemFieldErrors(error).filter((fieldError) =>
    fields.includes(fieldError.field as FieldPath<Form>),
  )
  if (fieldErrors.length === 0) {
    toast.error(problemMessage(error as never, UNEXPECTED_ERROR))
    return
  }
  for (const fieldError of fieldErrors) {
    setError(fieldError.field as FieldPath<Form>, { message: fieldError.message })
  }
}

/** Shows the secret in groups of four characters, which is easier to type into an app. */
function inGroupsOfFour(secret: string): string {
  return secret.match(/.{1,4}/g)?.join(' ') ?? secret
}

/**
 * "Two-factor authentication" in the settings section "Account" (spec 012): turning it on with a
 * QR code, creating new recovery codes and turning it off.
 */
export function TwoFactorSection() {
  const queryClient = useQueryClient()
  const twoFactor = useQuery({ queryKey: TWO_FACTOR_KEY, queryFn: fetchTwoFactor, retry: false })
  const [step, setStep] = useState<Step>({ kind: 'closed' })

  function close() {
    setStep({ kind: 'closed' })
  }

  function reload() {
    void queryClient.invalidateQueries({ queryKey: TWO_FACTOR_KEY })
  }

  return (
    <section aria-labelledby="two-factor-heading" className="grid gap-3">
      <h3 id="two-factor-heading" className="text-sm font-medium">
        Two-factor authentication
      </h3>
      <p className="text-sm text-muted-foreground">
        A login then also needs a 6-digit code from an authenticator app on your phone.
      </p>
      {twoFactor.data === undefined ? (
        twoFactor.isError ? (
          <p role="alert" className="text-sm text-destructive">
            The state of two-factor authentication could not be loaded.
          </p>
        ) : (
          <Skeleton className="h-9 w-48" />
        )
      ) : twoFactor.data.enabled ? (
        <>
          <p className="text-sm">
            <span className="font-medium">On.</span>{' '}
            {twoFactor.data.recoveryCodesLeft === 1
              ? '1 recovery code is left.'
              : `${String(twoFactor.data.recoveryCodesLeft)} recovery codes are left.`}
          </p>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              onClick={() => {
                setStep({ kind: 'confirm', action: 'newRecoveryCodes' })
              }}
            >
              Create new recovery codes
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                setStep({ kind: 'confirm', action: 'turnOff' })
              }}
            >
              Turn off
            </Button>
          </div>
        </>
      ) : (
        <>
          <p className="text-sm">
            <span className="font-medium">Off.</span>
          </p>
          <div>
            <Button
              variant="outline"
              onClick={() => {
                setStep({ kind: 'password' })
              }}
            >
              Turn on
            </Button>
          </div>
        </>
      )}

      <Dialog
        open={step.kind !== 'closed'}
        onOpenChange={(open) => {
          // The recovery codes are shown only once, so their dialog closes only with its button.
          if (!open && step.kind !== 'recoveryCodes') {
            close()
          }
        }}
      >
        <DialogContent
          onInteractOutside={(event) => {
            event.preventDefault()
          }}
        >
          {step.kind === 'password' && (
            <PasswordStep
              onCancel={close}
              onStarted={(setup) => {
                setStep({ kind: 'scan', ...setup })
              }}
            />
          )}
          {step.kind === 'scan' && (
            <ScanStep
              otpauthUri={step.otpauthUri}
              secret={step.secret}
              onCancel={close}
              onEnabled={(recoveryCodes) => {
                reload()
                setStep({ kind: 'recoveryCodes', recoveryCodes })
              }}
            />
          )}
          {step.kind === 'confirm' && (
            <ConfirmStep
              action={step.action}
              onCancel={close}
              onTurnedOff={() => {
                reload()
                close()
                toast.success('Two-factor authentication is off.')
              }}
              onRecoveryCodes={(recoveryCodes) => {
                reload()
                setStep({ kind: 'recoveryCodes', recoveryCodes })
              }}
            />
          )}
          {step.kind === 'recoveryCodes' && (
            <RecoveryCodesStep recoveryCodes={step.recoveryCodes} onDone={close} />
          )}
        </DialogContent>
      </Dialog>
    </section>
  )
}

function PasswordStep({
  onCancel,
  onStarted,
}: {
  onCancel: () => void
  onStarted: (setup: { otpauthUri: string; secret: string }) => void
}) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<PasswordForm>({
    resolver: zodResolver(passwordForm),
    defaultValues: { currentPassword: '' },
  })

  async function start(form: PasswordForm) {
    const { data, error, response } = await api.POST('/api/auth/two-factor/setup', { body: form })
    if (data !== undefined) {
      onStarted(data)
      return
    }
    showFailure(response.status, error, setError, ['currentPassword'])
  }

  return (
    <form className="grid gap-4" noValidate onSubmit={(event) => void handleSubmit(start)(event)}>
      <DialogHeader>
        <DialogTitle>Turn on two-factor authentication</DialogTitle>
        <DialogDescription>
          You need an authenticator app on your phone. First, enter your password.
        </DialogDescription>
      </DialogHeader>
      <FormField
        label="Current password"
        type="password"
        autoComplete="current-password"
        autoFocus
        error={errors.currentPassword?.message}
        {...register('currentPassword')}
      />
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          Continue
        </Button>
      </DialogFooter>
    </form>
  )
}

function ScanStep({
  otpauthUri,
  secret,
  onCancel,
  onEnabled,
}: {
  otpauthUri: string
  secret: string
  onCancel: () => void
  onEnabled: (recoveryCodes: string[]) => void
}) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<CodeForm>({ resolver: zodResolver(codeForm), defaultValues: { code: '' } })

  async function enable(form: CodeForm) {
    const { data, error, response } = await api.POST('/api/auth/two-factor/enable', { body: form })
    if (data !== undefined) {
      onEnabled(data.recoveryCodes)
      return
    }
    showFailure(response.status, error, setError, ['code'])
  }

  return (
    <form className="grid gap-4" noValidate onSubmit={(event) => void handleSubmit(enable)(event)}>
      <DialogHeader>
        <DialogTitle>Scan the QR code</DialogTitle>
        <DialogDescription>
          Scan it with your authenticator app, or type the secret into the app. Then enter the code
          the app shows. Nothing changes until the code was accepted.
        </DialogDescription>
      </DialogHeader>
      <div className="grid justify-items-center gap-2">
        <QrCode text={otpauthUri} label="QR code for the authenticator app" />
        <p className="text-xs text-muted-foreground">Secret</p>
        <p className="text-center font-mono text-sm break-all select-all">
          {inGroupsOfFour(secret)}
        </p>
      </div>
      <FormField
        label="Code from the app"
        inputMode="numeric"
        autoComplete="one-time-code"
        autoFocus
        error={errors.code?.message}
        {...register('code')}
      />
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          Turn on
        </Button>
      </DialogFooter>
    </form>
  )
}

function ConfirmStep({
  action,
  onCancel,
  onTurnedOff,
  onRecoveryCodes,
}: {
  action: 'turnOff' | 'newRecoveryCodes'
  onCancel: () => void
  onTurnedOff: () => void
  onRecoveryCodes: (recoveryCodes: string[]) => void
}) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ConfirmationForm>({
    resolver: zodResolver(confirmationForm),
    defaultValues: { currentPassword: '', code: '' },
  })
  const turnsOff = action === 'turnOff'

  async function confirm(form: ConfirmationForm) {
    if (turnsOff) {
      const { error, response } = await api.DELETE('/api/auth/two-factor', { body: form })
      if (response.ok) {
        onTurnedOff()
        return
      }
      showFailure(response.status, error, setError, ['currentPassword', 'code'])
      return
    }
    const { data, error, response } = await api.POST('/api/auth/two-factor/recovery-codes', {
      body: form,
    })
    if (data !== undefined) {
      onRecoveryCodes(data.recoveryCodes)
      return
    }
    showFailure(response.status, error, setError, ['currentPassword', 'code'])
  }

  return (
    <form className="grid gap-4" noValidate onSubmit={(event) => void handleSubmit(confirm)(event)}>
      <DialogHeader>
        <DialogTitle>
          {turnsOff ? 'Turn off two-factor authentication' : 'Create new recovery codes'}
        </DialogTitle>
        <DialogDescription>
          {turnsOff
            ? 'A login then needs only your password. The recovery codes stop working.'
            : 'The recovery codes you have now stop working.'}
        </DialogDescription>
      </DialogHeader>
      <FormField
        label="Current password"
        type="password"
        autoComplete="current-password"
        autoFocus
        error={errors.currentPassword?.message}
        {...register('currentPassword')}
      />
      <FormField
        label="Code"
        autoComplete="one-time-code"
        hint="A code from your authenticator app, or a recovery code."
        error={errors.code?.message}
        {...register('code')}
      />
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          type="submit"
          variant={turnsOff ? 'destructive' : 'default'}
          disabled={isSubmitting}
        >
          {turnsOff ? 'Turn off' : 'Create codes'}
        </Button>
      </DialogFooter>
    </form>
  )
}

function RecoveryCodesStep({
  recoveryCodes,
  onDone,
}: {
  recoveryCodes: string[]
  onDone: () => void
}) {
  const text = `${recoveryCodes.join('\n')}\n`

  async function copy() {
    try {
      await navigator.clipboard.writeText(text)
      toast.success('Recovery codes copied.')
    } catch {
      toast.error('The recovery codes could not be copied.')
    }
  }

  function download() {
    const address = URL.createObjectURL(new Blob([text], { type: 'text/plain' }))
    const link = document.createElement('a')
    link.href = address
    link.download = 'wardroom-recovery-codes.txt'
    link.click()
    URL.revokeObjectURL(address)
  }

  return (
    <div className="grid gap-4">
      <DialogHeader>
        <DialogTitle>Your recovery codes</DialogTitle>
        <DialogDescription>
          Each code logs you in once when you do not have your phone. Keep them in a safe place.
          They are not shown again.
        </DialogDescription>
      </DialogHeader>
      <ul
        aria-label="Recovery codes"
        className="grid grid-cols-2 gap-x-4 gap-y-1 rounded-md bg-muted p-3 font-mono text-sm"
      >
        {recoveryCodes.map((recoveryCode) => (
          <li key={recoveryCode}>{recoveryCode}</li>
        ))}
      </ul>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={() => void copy()}>
          Copy
        </Button>
        <Button type="button" variant="outline" onClick={download}>
          Download
        </Button>
        <Button type="button" onClick={onDone}>
          Done
        </Button>
      </DialogFooter>
    </div>
  )
}
