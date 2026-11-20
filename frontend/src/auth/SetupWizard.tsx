import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { api } from '@/api/client'
import { problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import { useRefreshAuthState } from '@/auth/authState'
import { EntryPage } from '@/auth/EntryPage'
import { newPasswordRule, PASSWORDS_DIFFER, usernameRule } from '@/auth/rules'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/utils'

const WIZARD_STEPS = ['Account', 'Server'] as const

const accountForm = z
  .object({
    username: usernameRule,
    password: newPasswordRule,
    repeatedPassword: z.string(),
  })
  .refine((form) => form.password === form.repeatedPassword, {
    path: ['repeatedPassword'],
    error: PASSWORDS_DIFFER,
  })

type AccountForm = z.infer<typeof accountForm>

/** The step indicator of the first-run wizard: 1 Account · 2 Server (UI concept). */
function WizardSteps({ current }: { current: (typeof WIZARD_STEPS)[number] }) {
  return (
    <ol aria-label="Setup steps" className="flex items-center justify-center gap-3 text-sm">
      {WIZARD_STEPS.map((step, index) => {
        const isCurrent = step === current
        return (
          <li
            key={step}
            aria-current={isCurrent ? 'step' : undefined}
            className={cn(
              'flex items-center gap-2',
              isCurrent ? 'font-medium text-foreground' : 'text-muted-foreground',
            )}
          >
            <span
              className={cn(
                'flex size-6 items-center justify-center rounded-full text-xs',
                isCurrent ? 'bg-primary text-primary-foreground' : 'bg-muted',
              )}
            >
              {index + 1}
            </span>
            {step}
          </li>
        )
      })}
    </ol>
  )
}

/**
 * Step 1 of the first-run wizard: the account (spec 001). Completing it creates the account and
 * logs in. Step 2, the server, belongs to the server configuration.
 */
export function SetupWizard() {
  const refreshAuthState = useRefreshAuthState()
  const [failure, setFailure] = useState<string>()
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<AccountForm>({ resolver: zodResolver(accountForm) })

  async function createAccount(form: AccountForm) {
    setFailure(undefined)
    const { error, response } = await api.POST('/api/auth/setup', {
      body: { username: form.username, password: form.password },
    })
    if (response.ok || response.status === 404) {
      // 404: somebody else has created the account in the meantime; the login page is shown.
      await refreshAuthState()
      return
    }
    setFailure(problemMessage(error, UNEXPECTED_ERROR))
  }

  return (
    <EntryPage>
      <div className="grid gap-6">
        <WizardSteps current="Account" />
        <Card>
          <CardHeader>
            <CardTitle>
              <h1>Create your account</h1>
            </CardTitle>
            <CardDescription>
              Wardroom has one account. It protects this instance and the server login you add in
              the next step.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form
              className="grid gap-4"
              noValidate
              onSubmit={(event) => void handleSubmit(createAccount)(event)}
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
                autoComplete="new-password"
                hint="At least 12 characters."
                error={errors.password?.message}
                {...register('password')}
              />
              <FormField
                label="Repeat password"
                type="password"
                autoComplete="new-password"
                error={errors.repeatedPassword?.message}
                {...register('repeatedPassword')}
              />
              {failure !== undefined && (
                <p role="alert" className="text-sm text-destructive">
                  {failure}
                </p>
              )}
              <Button type="submit" disabled={isSubmitting}>
                Create account
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </EntryPage>
  )
}
