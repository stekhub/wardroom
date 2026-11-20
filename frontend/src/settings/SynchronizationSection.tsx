import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { api } from '@/api/client'
import { problemFieldErrors, problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import type { components } from '@/api/schema'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { SettingsSectionFrame } from '@/settings/SettingsSectionFrame'

type Settings = components['schemas']['Settings']

const SETTINGS_KEY = ['settings'] as const

/**
 * The ranges of spec 005. The backend checks them again; they are repeated here so the owner
 * sees a mistake before the form is sent.
 */
function numberBetween(minimum: number, maximum: number) {
  const rule = `Enter a number between ${String(minimum)} and ${String(maximum)}.`
  // An empty number field is "not a number", which is refused like a number out of range.
  return z.number({ error: rule }).int(rule).min(minimum, rule).max(maximum, rule)
}

const settingsForm = z.object({
  syncIntervalMinutes: numberBetween(5, 1440),
  initialDepth: numberBetween(50, 10_000),
  groupListIntervalHours: numberBetween(1, 168),
})

type SettingsForm = z.infer<typeof settingsForm>

const FORM_FIELDS = ['syncIntervalMinutes', 'initialDepth', 'groupListIntervalHours'] as const

function isFormField(field: string): field is (typeof FORM_FIELDS)[number] {
  return (FORM_FIELDS as readonly string[]).includes(field)
}

async function fetchSettings(): Promise<Settings> {
  const { data, response } = await api.GET('/api/settings')
  if (data === undefined) {
    throw new Error(`Unexpected answer ${String(response.status)} to the settings request`)
  }
  return data
}

/**
 * The settings section "Synchronization" (spec 005): how often the groups and the group list
 * are fetched, and how much a first synchronization fetches.
 */
export function SynchronizationSection() {
  const settings = useQuery({ queryKey: SETTINGS_KEY, queryFn: fetchSettings, retry: false })

  return (
    <SettingsSectionFrame title="Synchronization">
      {settings.data !== undefined && <SynchronizationForm settings={settings.data} />}
      {settings.data === undefined && settings.isError && (
        <p className="text-sm text-destructive">The settings could not be loaded.</p>
      )}
      {settings.data === undefined && !settings.isError && (
        <div aria-hidden="true" className="grid gap-4">
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-14 w-full" />
        </div>
      )}
    </SettingsSectionFrame>
  )
}

function SynchronizationForm({ settings }: { settings: Settings }) {
  const queryClient = useQueryClient()
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<SettingsForm>({
    resolver: zodResolver(settingsForm),
    defaultValues: settings,
  })

  async function save(form: SettingsForm) {
    const { data, error, response } = await api.PUT('/api/settings', { body: form })
    if (data !== undefined) {
      queryClient.setQueryData<Settings>(SETTINGS_KEY, data)
      // The saved values are the new starting point, so the button waits for the next change.
      reset(data)
      toast.success('Settings saved')
      return
    }
    const fieldErrors = problemFieldErrors(error).filter((fieldError) =>
      isFormField(fieldError.field),
    )
    for (const fieldError of fieldErrors) {
      if (isFormField(fieldError.field)) {
        setError(fieldError.field, { message: fieldError.message })
      }
    }
    if (fieldErrors.length === 0 && response.status !== 401) {
      toast.error(problemMessage(error, UNEXPECTED_ERROR))
    }
  }

  return (
    <form className="grid gap-4" noValidate onSubmit={(event) => void handleSubmit(save)(event)}>
      <FormField
        label="Synchronization interval"
        type="number"
        inputMode="numeric"
        min={5}
        max={1440}
        hint="In minutes, 5 to 1440. All subscribed groups are synchronized this often."
        error={errors.syncIntervalMinutes?.message}
        {...register('syncIntervalMinutes', { valueAsNumber: true })}
      />
      <FormField
        label="First synchronization depth"
        type="number"
        inputMode="numeric"
        min={50}
        max={10000}
        hint="In articles, 50 to 10000. The first synchronization of a group fetches this many of its newest articles. A change applies to groups you subscribe to afterwards."
        error={errors.initialDepth?.message}
        {...register('initialDepth', { valueAsNumber: true })}
      />
      <FormField
        label="Group list refresh interval"
        type="number"
        inputMode="numeric"
        min={1}
        max={168}
        hint="In hours, 1 to 168. The list of all groups of the server is fetched this often."
        error={errors.groupListIntervalHours?.message}
        {...register('groupListIntervalHours', { valueAsNumber: true })}
      />
      <div>
        <Button type="submit" disabled={!isDirty || isSubmitting}>
          Save
        </Button>
      </div>
    </form>
  )
}
