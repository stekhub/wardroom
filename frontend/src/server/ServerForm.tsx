import { zodResolver } from '@hookform/resolvers/zod'
import { type ChangeEvent, type ReactNode, useId, useMemo, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { api } from '@/api/client'
import { problemFieldErrors, problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import type { components } from '@/api/schema'
import { FormField } from '@/components/FormField'
import { FormSelect } from '@/components/FormSelect'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { NativeSelectOption } from '@/components/ui/native-select'
import { ConnectionTestMessage } from '@/server/ConnectionTestMessage'
import { DEFAULT_PORTS, serverFormRules, type ServerFormValues, type TlsMode } from '@/server/rules'
import { type ConnectionTestResult, type Server, useRefreshServers } from '@/server/serverState'

type SavedServer = components['schemas']['SavedServer']
type ServerRequest = components['schemas']['ServerRequest']
type PostingServerRequest = components['schemas']['PostingServerRequest']

/** The form field for each field the backend names in a validation failure. */
const FORM_FIELDS: Readonly<Record<string, keyof ServerFormValues>> = {
  name: 'name',
  host: 'host',
  port: 'port',
  tlsMode: 'tlsMode',
  authMethod: 'authMethod',
  username: 'username',
  password: 'password',
  maxConnections: 'maxConnections',
  'postingServer.host': 'postingHost',
  'postingServer.port': 'postingPort',
  'postingServer.tlsMode': 'postingTlsMode',
  'postingServer.authMethod': 'postingAuthMethod',
  'postingServer.username': 'postingUsername',
  'postingServer.password': 'postingPassword',
}

function formValues(server: Server | undefined): ServerFormValues {
  const postingServer = server?.postingServer
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
    // The backend sends `null` when posts go to the server itself.
    separatePostingServer: postingServer != null,
    postingHost: postingServer?.host ?? '',
    postingPort: postingServer?.port ?? DEFAULT_PORTS.IMPLICIT,
    postingTlsMode: postingServer?.tlsMode ?? 'IMPLICIT',
    postingSameLogin: postingServer?.sameLogin ?? true,
    postingAuthMethod: postingServer?.authMethod ?? 'USER_PASS',
    postingUsername: postingServer?.username ?? '',
    postingPassword: '',
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
  if (form.separatePostingServer) {
    const postingServer: PostingServerRequest = {
      host: form.postingHost.trim(),
      port: form.postingPort,
      tlsMode: form.postingTlsMode,
      sameLogin: form.postingSameLogin,
    }
    if (!form.postingSameLogin) {
      postingServer.authMethod = form.postingAuthMethod
      if (form.postingAuthMethod === 'USER_PASS') {
        postingServer.username = form.postingUsername
        // An empty password keeps the stored one.
        if (form.postingPassword !== '') {
          postingServer.password = form.postingPassword
        }
      }
    }
    server.postingServer = postingServer
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
  const postingPasswordStored =
    server?.postingServer?.authMethod === 'USER_PASS' && server.postingServer.passwordStored
  const resolver = useMemo(
    () => zodResolver(serverFormRules(passwordStored, postingPasswordStored)),
    [passwordStored, postingPasswordStored],
  )
  const separatePostingServerId = useId()
  const postingSameLoginId = useId()
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
  const separatePostingServer = useWatch({ control, name: 'separatePostingServer' })
  const postingSameLogin = useWatch({ control, name: 'postingSameLogin' })
  const postingAuthMethod = useWatch({ control, name: 'postingAuthMethod' })

  /** Changing the TLS mode changes the port to that mode's default, unless the owner chose a port. */
  function adjustPortOf(portField: 'port' | 'postingPort') {
    return (event: ChangeEvent<HTMLSelectElement>) => {
      const newMode = event.target.value as TlsMode
      const previousMode: TlsMode = newMode === 'IMPLICIT' ? 'NONE' : 'IMPLICIT'
      if (dirtyFields[portField] !== true && getValues(portField) === DEFAULT_PORTS[previousMode]) {
        setValue(portField, DEFAULT_PORTS[newMode])
      }
    }
  }

  function showFieldErrors(problem: unknown): boolean {
    let shown = false
    for (const error of problemFieldErrors(problem)) {
      const formField = FORM_FIELDS[error.field]
      if (formField !== undefined) {
        setError(formField, { message: error.message })
        shown = true
      }
    }
    return shown
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
        {...register('tlsMode', { onChange: adjustPortOf('port') })}
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
      <div className="grid gap-1">
        <div className="flex items-center gap-2">
          <Checkbox
            id={separatePostingServerId}
            checked={separatePostingServer}
            onCheckedChange={(checked) => {
              setValue('separatePostingServer', checked === true, { shouldDirty: true })
            }}
          />
          <Label htmlFor={separatePostingServerId}>Use a separate server for posting</Label>
        </div>
        <p className="text-xs text-muted-foreground">
          Some providers take posts only on a separate server. Your provider tells you its host.
        </p>
      </div>
      {separatePostingServer && (
        <fieldset className="grid gap-4 rounded-lg border p-4">
          <legend className="px-1 text-sm font-medium">Posting server</legend>
          <div className="grid grid-cols-[1fr_7rem] items-start gap-4">
            <FormField
              label="Posting host"
              placeholder="post.example.com"
              autoComplete="off"
              autoCapitalize="none"
              spellCheck={false}
              error={errors.postingHost?.message}
              {...register('postingHost')}
            />
            <FormField
              label="Posting port"
              type="number"
              min={1}
              max={65535}
              error={errors.postingPort?.message}
              {...register('postingPort', { valueAsNumber: true })}
            />
          </div>
          <FormSelect
            label="Posting TLS mode"
            error={errors.postingTlsMode?.message}
            {...register('postingTlsMode', { onChange: adjustPortOf('postingPort') })}
          >
            <NativeSelectOption value="IMPLICIT">TLS (encrypted)</NativeSelectOption>
            <NativeSelectOption value="NONE">None (not encrypted)</NativeSelectOption>
          </FormSelect>
          <div className="flex items-center gap-2">
            <Checkbox
              id={postingSameLoginId}
              checked={postingSameLogin}
              onCheckedChange={(checked) => {
                setValue('postingSameLogin', checked === true, { shouldDirty: true })
              }}
            />
            <Label htmlFor={postingSameLoginId}>Use the same login as for reading</Label>
          </div>
          {!postingSameLogin && (
            <>
              <FormSelect
                label="Posting authentication"
                error={errors.postingAuthMethod?.message}
                {...register('postingAuthMethod')}
              >
                <NativeSelectOption value="USER_PASS">Username and password</NativeSelectOption>
                <NativeSelectOption value="NONE">None</NativeSelectOption>
              </FormSelect>
              {postingAuthMethod === 'USER_PASS' && (
                <>
                  <FormField
                    label="Posting username"
                    autoComplete="off"
                    autoCapitalize="none"
                    spellCheck={false}
                    error={errors.postingUsername?.message}
                    {...register('postingUsername')}
                  />
                  <FormField
                    label="Posting password"
                    type="password"
                    autoComplete="new-password"
                    hint={
                      postingPasswordStored
                        ? 'A password is stored. Leave the field empty to keep it.'
                        : 'Stored encrypted and never shown again.'
                    }
                    error={errors.postingPassword?.message}
                    {...register('postingPassword')}
                  />
                </>
              )}
            </>
          )}
        </fieldset>
      )}
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
