import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useId, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { z } from 'zod'
import { api } from '@/api/client'
import { problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import { useRefreshAuthState } from '@/auth/authState'
import { EntryPage } from '@/auth/EntryPage'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'

const loginForm = z.object({
  username: z.string().min(1, 'Enter your username.'),
  password: z.string().min(1, 'Enter your password.'),
  rememberMe: z.boolean(),
})

type LoginForm = z.infer<typeof loginForm>

const codeForm = z.object({ code: z.string().trim().min(1, 'Enter the code.') })

type CodeForm = z.infer<typeof codeForm>

/** Counts down the seconds a throttled login has to wait (spec 001). */
function useWaitingSeconds(): [number, (seconds: number) => void] {
  const [waitingSeconds, setWaitingSeconds] = useState(0)

  useEffect(() => {
    if (waitingSeconds <= 0) {
      return
    }
    const timer = window.setTimeout(() => {
      setWaitingSeconds((seconds) => seconds - 1)
    }, 1000)
    return () => {
      window.clearTimeout(timer)
    }
  }, [waitingSeconds])

  return [waitingSeconds, setWaitingSeconds]
}

/** The waiting time a throttled login was told (spec 001), at least one second. */
function retryAfterSeconds(response: Response): number {
  const retryAfter = Number(response.headers.get('Retry-After'))
  return Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : 1
}

function Failure({ failure, waitingSeconds }: { failure: string; waitingSeconds: number }) {
  return (
    <p role="alert" className="text-sm text-destructive">
      {failure}
      {waitingSeconds > 0 &&
        ` Try again in ${String(waitingSeconds)} ${waitingSeconds === 1 ? 'second' : 'seconds'}.`}
    </p>
  )
}

/**
 * The login: username and password and, when two-factor authentication is on, a second step that
 * asks for the code (spec 012).
 */
export function LoginPage() {
  const [codeNeeded, setCodeNeeded] = useState(false)

  return (
    <EntryPage>
      <Card>
        <CardHeader>
          <CardTitle>
            <h1>{codeNeeded ? 'Enter your code' : 'Log in'}</h1>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {codeNeeded ? (
            <CodeStep
              onStartAgain={() => {
                setCodeNeeded(false)
              }}
            />
          ) : (
            <PasswordStep
              onCodeNeeded={() => {
                setCodeNeeded(true)
              }}
            />
          )}
        </CardContent>
      </Card>
    </EntryPage>
  )
}

function PasswordStep({ onCodeNeeded }: { onCodeNeeded: () => void }) {
  const refreshAuthState = useRefreshAuthState()
  const [failure, setFailure] = useState<string>()
  const [waitingSeconds, setWaitingSeconds] = useWaitingSeconds()
  const rememberMeId = useId()
  const {
    register,
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginForm>({
    resolver: zodResolver(loginForm),
    defaultValues: { username: '', password: '', rememberMe: false },
  })

  async function logIn(credentials: LoginForm) {
    setFailure(undefined)
    const { data, error, response } = await api.POST('/api/auth/login', { body: credentials })
    if (response.ok) {
      if (data?.secondFactorRequired === true) {
        onCodeNeeded()
        return
      }
      await refreshAuthState()
      return
    }
    if (response.status === 429) {
      setWaitingSeconds(retryAfterSeconds(response))
      setFailure('Too many failed logins.')
      return
    }
    setFailure(problemMessage(error, UNEXPECTED_ERROR))
  }

  const waiting = waitingSeconds > 0

  return (
    <form className="grid gap-4" noValidate onSubmit={(event) => void handleSubmit(logIn)(event)}>
      <FormField
        label="Username"
        autoComplete="username"
        autoFocus
        error={errors.username?.message}
        {...register('username')}
      />
      <FormField
        label="Password"
        type="password"
        autoComplete="current-password"
        error={errors.password?.message}
        {...register('password')}
      />
      <div className="grid gap-1.5">
        <div className="flex items-center gap-2">
          <Controller
            control={control}
            name="rememberMe"
            render={({ field }) => (
              <Checkbox
                id={rememberMeId}
                aria-describedby={`${rememberMeId}-hint`}
                checked={field.value}
                onCheckedChange={(checked) => {
                  field.onChange(checked === true)
                }}
                onBlur={field.onBlur}
                ref={field.ref}
              />
            )}
          />
          <Label htmlFor={rememberMeId}>Remember me</Label>
        </div>
        <p id={`${rememberMeId}-hint`} className="text-xs text-muted-foreground">
          Stay logged in on this browser after it is closed.
        </p>
      </div>
      {failure !== undefined && <Failure failure={failure} waitingSeconds={waitingSeconds} />}
      <Button type="submit" disabled={isSubmitting || waiting}>
        Log in
      </Button>
    </form>
  )
}

/** The second step: the password was right, and the backend waits 5 minutes for the code. */
function CodeStep({ onStartAgain }: { onStartAgain: () => void }) {
  const refreshAuthState = useRefreshAuthState()
  const [failure, setFailure] = useState<string>()
  const [waitingSeconds, setWaitingSeconds] = useWaitingSeconds()
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<CodeForm>({ resolver: zodResolver(codeForm), defaultValues: { code: '' } })

  async function sendCode(form: CodeForm) {
    setFailure(undefined)
    const { error, response } = await api.POST('/api/auth/login/second-factor', { body: form })
    if (response.ok) {
      await refreshAuthState()
      return
    }
    if (response.status === 429) {
      setWaitingSeconds(retryAfterSeconds(response))
      setFailure('Too many failed logins.')
      return
    }
    setFailure(problemMessage(error, UNEXPECTED_ERROR))
  }

  const waiting = waitingSeconds > 0

  return (
    <form
      className="grid gap-4"
      noValidate
      onSubmit={(event) => void handleSubmit(sendCode)(event)}
    >
      <FormField
        label="Code"
        autoComplete="one-time-code"
        autoFocus
        hint="The 6-digit code from your authenticator app, or a recovery code."
        error={errors.code?.message}
        {...register('code')}
      />
      {failure !== undefined && <Failure failure={failure} waitingSeconds={waitingSeconds} />}
      <Button type="submit" disabled={isSubmitting || waiting}>
        Log in
      </Button>
      <Button type="button" variant="ghost" onClick={onStartAgain}>
        Back to the password
      </Button>
    </form>
  )
}
