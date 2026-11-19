import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { api } from '@/api/client'
import { problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import { useAuthState, useShowLoginPage } from '@/auth/authState'
import { newPasswordRule, PASSWORDS_DIFFER } from '@/auth/rules'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { SettingsSectionFrame } from '@/settings/SettingsSectionFrame'

const passwordForm = z
  .object({
    currentPassword: z.string().min(1, 'Enter your current password.'),
    newPassword: newPasswordRule,
    repeatedPassword: z.string(),
  })
  .refine((form) => form.newPassword === form.repeatedPassword, {
    path: ['repeatedPassword'],
    error: PASSWORDS_DIFFER,
  })

type PasswordForm = z.infer<typeof passwordForm>

/**
 * The settings section "Account": changing the password (spec 001). A changed password logs out
 * everywhere, also here, so the login page follows.
 */
export function AccountSection() {
  const authState = useAuthState()
  const showLoginPage = useShowLoginPage()
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<PasswordForm>({
    resolver: zodResolver(passwordForm),
    defaultValues: { currentPassword: '', newPassword: '', repeatedPassword: '' },
  })

  async function changePassword(form: PasswordForm) {
    const { error, response } = await api.PUT('/api/auth/password', {
      body: { currentPassword: form.currentPassword, newPassword: form.newPassword },
    })
    if (response.ok) {
      // The backend has ended all sessions, this one included (spec 001).
      toast.success('Password changed. Log in with your new password.')
      showLoginPage()
      return
    }
    if (response.status === 400) {
      // The frontend has checked the new password already, so the backend's objection is about the current one.
      setError('currentPassword', { message: problemMessage(error, UNEXPECTED_ERROR) })
      return
    }
    if (response.status !== 401) {
      toast.error(problemMessage(error, UNEXPECTED_ERROR))
    }
  }

  return (
    <SettingsSectionFrame title="Account">
      {authState.data?.status === 'authenticated' && (
        <p className="text-sm text-muted-foreground">
          Logged in as{' '}
          <span className="font-medium text-foreground">{authState.data.username}</span>.
        </p>
      )}
      <form
        className="grid gap-4"
        noValidate
        onSubmit={(event) => void handleSubmit(changePassword)(event)}
      >
        <FormField
          label="Current password"
          type="password"
          autoComplete="current-password"
          error={errors.currentPassword?.message}
          {...register('currentPassword')}
        />
        <FormField
          label="New password"
          type="password"
          autoComplete="new-password"
          hint="At least 12 characters."
          error={errors.newPassword?.message}
          {...register('newPassword')}
        />
        <FormField
          label="Repeat new password"
          type="password"
          autoComplete="new-password"
          error={errors.repeatedPassword?.message}
          {...register('repeatedPassword')}
        />
        <div>
          <Button type="submit" disabled={!isDirty || isSubmitting}>
            Change password
          </Button>
        </div>
      </form>
    </SettingsSectionFrame>
  )
}
