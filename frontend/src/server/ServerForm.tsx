import { zodResolver } from '@hookform/resolvers/zod'
import { type ChangeEvent, type ReactNode, useMemo, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { api } from '@/api/client'
import { problemFieldErrors, problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import type { components } from '@/api/schema'
import { FormField } from '@/components/FormField'
import { FormSelect } from '@/components/FormSelect'
import { Button } from '@/components/ui/button'
import { NativeSelectOption } from '@/components/ui/native-select'
import { ConnectionTestMessage } from '@/server/ConnectionTestMessage'
import { DEFAULT_PORTS, serverFormRules, type ServerFormValues, type TlsMode } from '@/server/rules'
import { type ConnectionTestResult, type Server, useRefreshServers } from '@/server/serverState'

type SavedServer = components['schemas']['SavedServer']
type ServerRequest = components['schemas']['ServerRequest']

const FORM_FIELDS: readonly string[] = [
  'name',
  'host',
  'port',
  'tlsMode',
  'authMethod',
  'username',
  'password',
  'maxConnections',
]

function isFormField(field: string): field is keyof ServerFormValues {
  return FORM_FIELDS.includes(field)
}

function formValues(server: Server | undefined): ServerFormValues {
  return {
    name: server?.name ?? '',
    host: server?.host ?? '',
    port: server?.port ?? DEFAULT_PORTS.IMPLICIT,
    tlsMode: server?.tlsMode ?? 'IMPLICIT',
    authMethod: server?.authMethod ?? 'USER_PASS',
    username: server?.username ?? '',
    // The password is never sent to the browser; the field is always empty at first (spec 002).
    password: '',
    maxConnections: server?.maxConnections ?? 4,
  }
}

function requestBody(form: ServerFormValues): ServerRequest {
  const server: ServerRequest = {
    name: form.name.trim(),
    host: form.host.trim(),
    port: form.port,
    tlsMode: form.tlsMode,
    authMethod: form.authMethod,
    maxConnections: form.maxConnections,
  }
  if (form.authMethod === 'USER_PASS') {
    server.username = form.username
    // An empty password keeps the stored one.
    if (form.password !== '') {
      server.password = form.password
    }
  }
  return server
}

type ServerFormProps = {
  /** The server that is edited; absent when a server is added. */
  server?: Server | undefined
  saveLabel: string
  /** Called after the backend saved the server, also when its connection test failed. */
  onSaved: (saved: SavedServer) => void
  /** Further actions, shown after the buttons of the form. */
  children?: ReactNode
}

/**
 * The server form of spec 002, used by the first-run wizard and by the settings page: the fields,
 * "Test connection", and saving. Saving runs the test again and saves even when it fails, so the
 * result is shown below the form.
 */
export function ServerForm({ server, saveLabel, onSaved, children }: ServerFormProps) {
  const refreshServers = useRefreshServers()
  const [connectionTest, setConnectionTest] = useState<ConnectionTestResult>()
  const [failure, setFailure] = useState<string>()
  const [isTesting, setIsTesting] = useState(false)
  const passwordStored = server?.authMethod === 'USER_PASS' && server.passwordStored
  const resolver = useMemo(() => zodResolver(serverFormRules(passwordStored)), [passwordStored])
  const {
    register,
    control,
    handleSubmit,
    getValues,
    setValue,
    setError,
    reset,
    trigger,
    formState: { errors, dirtyFields, isDirty, isSubmitting },
  } = useForm<ServerFormValues>({ resolver, defaultValues: formValues(server) })
  const authMethod = useWatch({ control, name: 'authMethod' })

  /** Changing the TLS mode changes the port to that mode's default, unless the owner chose a port. */
  function adjustPort(event: ChangeEvent<HTMLSelectElement>) {
    const newMode = event.target.value as TlsMode
    const previousMode: TlsMode = newMode === 'IMPLICIT' ? 'NONE' : 'IMPLICIT'
    if (dirtyFields.port !== true && getValues('port') === DEFAULT_PORTS[previousMode]) {
      setValue('port', DEFAULT_PORTS[newMode])
    }
  }

  function showFieldErrors(problem: unknown): boolean {
    const fieldErrors = problemFieldErrors(problem).filter((error) => isFormField(error.field))
    for (const error of fieldErrors) {
      if (isFormField(error.field)) {
        setError(error.field, { message: error.message })
      }
    }
    return fieldErrors.length > 0
  }

  async function testConnection() {
    setFailure(undefined)
    if (!(await trigger())) {
      return
    }
    setIsTesting(true)
    setConnectionTest(undefined)
    try {
      const { data, error, response } = await api.POST('/api/servers/connection-test', {
        body: { ...requestBody(getValues()), ...(server === undefined ? {} : { id: server.id }) },
      })
      if (data !== undefined) {
        setConnectionTest(data)
      } else if (response.status === 404) {
        await refreshServers()
      } else if (response.status !== 401 && !showFieldErrors(error)) {
        setFailure(problemMessage(error, UNEXPECTED_ERROR))
      }
    } finally {
      setIsTesting(false)
    }
  }

  async function save(form: ServerFormValues) {
    setFailure(undefined)
    setConnectionTest(undefined)
    const { data, error, response } =
      server === undefined
        ? await api.POST('/api/servers', { body: requestBody(form) })
        : await api.PUT('/api/servers/{serverId}', {
            params: { path: { serverId: server.id } },
            body: requestBody(form),
          })
    if (data !== undefined) {
      setConnectionTest(data.connectionTest)
      reset(formValues(data.server))
      onSaved(data)
      return
    }
    if (response.status === 404 || response.status === 409) {
      // The server was removed or added in another browser; show what is there now.
      await refreshServers()
      return
    }
    if (response.status !== 401 && !showFieldErrors(error)) {
      setFailure(problemMessage(error, UNEXPECTED_ERROR))
    }
  }

  return (
    <form className="grid gap-4" noValidate onSubmit={(event) => void handleSubmit(save)(event)}>
      <FormField
        label="Name"
        hint="How Wardroom calls this server, for example the name of your provider."
        autoComplete="off"
        error={errors.name?.message}
        {...register('name')}
      />
      <div className="grid grid-cols-[1fr_7rem] items-start gap-4">
        <FormField
          label="Host"
          placeholder="news.example.com"
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          error={errors.host?.message}
          {...register('host')}
        />
        <FormField
          label="Port"
          type="number"
          min={1}
          max={65535}
          error={errors.port?.message}
          {...register('port', { valueAsNumber: true })}
        />
      </div>
      <FormSelect
        label="TLS mode"
        error={errors.tlsMode?.message}
        {...register('tlsMode', { onChange: adjustPort })}
      >
        <NativeSelectOption value="IMPLICIT">TLS (encrypted)</NativeSelectOption>
        <NativeSelectOption value="NONE">None (not encrypted)</NativeSelectOption>
      </FormSelect>
      <FormSelect
        label="Authentication"
        error={errors.authMethod?.message}
        {...register('authMethod')}
      >
        <NativeSelectOption value="USER_PASS">Username and password</NativeSelectOption>
        <NativeSelectOption value="NONE">None</NativeSelectOption>
      </FormSelect>
      {authMethod === 'USER_PASS' && (
        <>
          <FormField
            label="Server username"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            error={errors.username?.message}
            {...register('username')}
          />
          <FormField
            label="Server password"
            type="password"
            autoComplete="new-password"
            hint={
              passwordStored
                ? 'A password is stored. Leave the field empty to keep it.'
                : 'Stored encrypted and never shown again.'
            }
            error={errors.password?.message}
            {...register('password')}
          />
        </>
      )}
      <FormField
        label="Connections"
        type="number"
        min={1}
        max={50}
        hint="How many connections Wardroom may open at the same time. Your provider sets the limit."
        error={errors.maxConnections?.message}
        {...register('maxConnections', { valueAsNumber: true })}
      />
      {failure !== undefined && (
        <p role="alert" className="text-sm text-destructive">
          {failure}
        </p>
      )}
      {connectionTest !== undefined && <ConnectionTestMessage result={connectionTest} />}
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" disabled={isSubmitting || (server !== undefined && !isDirty)}>
          {saveLabel}
        </Button>
        <Button
          type="button"
          variant="outline"
          disabled={isTesting || isSubmitting}
          onClick={() => void testConnection()}
        >
          {isTesting ? 'Testing…' : 'Test connection'}
        </Button>
        {children}
      </div>
    </form>
  )
}
