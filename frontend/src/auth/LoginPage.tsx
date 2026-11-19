import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { api } from '@/api/client'
import { problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import { useRefreshAuthState } from '@/auth/authState'
import { EntryPage } from '@/auth/EntryPage'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

const loginForm = z.object({
  username: z.string().min(1, 'Enter your username.'),
  password: z.string().min(1, 'Enter your password.'),
})

type LoginForm = z.infer<typeof loginForm>

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

export function LoginPage() {
  const refreshAuthState = useRefreshAuthState()
  const [failure, setFailure] = useState<string>()
  const [waitingSeconds, setWaitingSeconds] = useWaitingSeconds()
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginForm>({ resolver: zodResolver(loginForm) })

  async function logIn(credentials: LoginForm) {
    setFailure(undefined)
    const { error, response } = await api.POST('/api/auth/login', { body: credentials })
    if (response.ok) {
      await refreshAuthState()
      return
    }
    if (response.status === 429) {
      const retryAfter = Number(response.headers.get('Retry-After'))
      setWaitingSeconds(Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : 1)
      setFailure('Too many failed logins.')
      return
    }
    setFailure(problemMessage(error, UNEXPECTED_ERROR))
  }

  const waiting = waitingSeconds > 0

  return (
    <EntryPage>
      <Card>
        <CardHeader>
          <CardTitle>
            <h1>Log in</h1>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <form
            className="grid gap-4"
            noValidate
            onSubmit={(event) => void handleSubmit(logIn)(event)}
          >
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
            {failure !== undefined && (
              <p role="alert" className="text-sm text-destructive">
                {failure}
                {waiting &&
                  ` Try again in ${String(waitingSeconds)} ${waitingSeconds === 1 ? 'second' : 'seconds'}.`}
              </p>
            )}
            <Button type="submit" disabled={isSubmitting || waiting}>
              Log in
            </Button>
          </form>
        </CardContent>
      </Card>
    </EntryPage>
  )
}
