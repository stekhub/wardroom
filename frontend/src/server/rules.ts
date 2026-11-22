import { z } from 'zod'

/**
 * The rules of spec 002 for the fields of a server. The backend checks them again; they are
 * repeated here so the owner sees a mistake before the form is sent.
 */
const NAME_MAX_LENGTH = 64
const HOST = /^[A-Za-z0-9._:-]{1,253}$/
const PORT_MAX = 65535
const CREDENTIAL_MAX_LENGTH = 255
const MAX_CONNECTIONS_MAX = 50

const PORT_RULE = `Enter a port between 1 and ${String(PORT_MAX)}.`
const MAX_CONNECTIONS_RULE = `Enter a number between 1 and ${String(MAX_CONNECTIONS_MAX)}.`
const USERNAME_RULE = `Enter a username with 1 to ${String(CREDENTIAL_MAX_LENGTH)} characters.`
const PASSWORD_RULE = `Enter a password with 1 to ${String(CREDENTIAL_MAX_LENGTH)} characters.`
const HOST_RULE = 'Enter a host name or an IP address.'
const LOGIN_NEEDS_TLS = 'A login with username and password needs TLS.'

export const TLS_MODES = ['IMPLICIT', 'NONE'] as const
export const AUTH_METHODS = ['NONE', 'USER_PASS'] as const

export type TlsMode = (typeof TLS_MODES)[number]

/** The port a server usually listens on with this TLS mode. */
export const DEFAULT_PORTS: Record<TlsMode, number> = { IMPLICIT: 563, NONE: 119 }

/** Counts characters like the backend does: an emoji is one character, not two. */
function characterCount(text: string): number {
  return Array.from(text).length
}

/**
 * @param passwordStored whether a password is stored that is used when the field is left empty
 * @param postingPasswordStored the same for the password of the posting server's own login
 */
export function serverFormRules(passwordStored: boolean, postingPasswordStored: boolean) {
  return z
    .object({
      name: z.string().refine(
        (name) => {
          const length = characterCount(name.trim())
          return length >= 1 && length <= NAME_MAX_LENGTH
        },
        { error: `Enter a name with 1 to ${String(NAME_MAX_LENGTH)} characters.` },
      ),
      host: z.string().refine((host) => HOST.test(host.trim()), { error: HOST_RULE }),
      // An empty number field is "not a number", which is refused like a number out of range.
      port: z
        .number({ error: PORT_RULE })
        .int(PORT_RULE)
        .min(1, PORT_RULE)
        .max(PORT_MAX, PORT_RULE),
      tlsMode: z.enum(TLS_MODES),
      authMethod: z.enum(AUTH_METHODS),
      username: z.string(),
      password: z.string(),
      maxConnections: z
        .number({ error: MAX_CONNECTIONS_RULE })
        .int(MAX_CONNECTIONS_RULE)
        .min(1, MAX_CONNECTIONS_RULE)
        .max(MAX_CONNECTIONS_MAX, MAX_CONNECTIONS_RULE),
      // The fields of the posting server are checked below, and only while it is used: a field
      // that is not shown must not keep the form from being sent.
      separatePostingServer: z.boolean(),
      postingHost: z.string(),
      postingPort: z.union([z.number(), z.nan()]),
      postingTlsMode: z.enum(TLS_MODES),
      postingSameLogin: z.boolean(),
      postingAuthMethod: z.enum(AUTH_METHODS),
      postingUsername: z.string(),
      postingPassword: z.string(),
    })
    .superRefine((form, context) => {
      if (form.authMethod === 'USER_PASS') {
        if (form.tlsMode !== 'IMPLICIT') {
          context.addIssue({ code: 'custom', path: ['authMethod'], message: LOGIN_NEEDS_TLS })
        }
        const usernameLength = characterCount(form.username)
        if (usernameLength < 1 || usernameLength > CREDENTIAL_MAX_LENGTH) {
          context.addIssue({ code: 'custom', path: ['username'], message: USERNAME_RULE })
        }
        const passwordLength = characterCount(form.password)
        if (passwordLength > CREDENTIAL_MAX_LENGTH || (passwordLength === 0 && !passwordStored)) {
          context.addIssue({ code: 'custom', path: ['password'], message: PASSWORD_RULE })
        }
      }
      if (!form.separatePostingServer) {
        return
      }
      if (!HOST.test(form.postingHost.trim())) {
        context.addIssue({ code: 'custom', path: ['postingHost'], message: HOST_RULE })
      }
      if (
        !Number.isInteger(form.postingPort) ||
        form.postingPort < 1 ||
        form.postingPort > PORT_MAX
      ) {
        context.addIssue({ code: 'custom', path: ['postingPort'], message: PORT_RULE })
      }
      if (form.postingSameLogin) {
        // The password of the server is never sent without encryption.
        if (form.authMethod === 'USER_PASS' && form.postingTlsMode !== 'IMPLICIT') {
          context.addIssue({
            code: 'custom',
            path: ['postingTlsMode'],
            message: 'The posting server uses the login of the server and needs TLS.',
          })
        }
        return
      }
      if (form.postingAuthMethod !== 'USER_PASS') {
        return
      }
      if (form.postingTlsMode !== 'IMPLICIT') {
        context.addIssue({ code: 'custom', path: ['postingAuthMethod'], message: LOGIN_NEEDS_TLS })
      }
      const usernameLength = characterCount(form.postingUsername)
      if (usernameLength < 1 || usernameLength > CREDENTIAL_MAX_LENGTH) {
        context.addIssue({ code: 'custom', path: ['postingUsername'], message: USERNAME_RULE })
      }
      const passwordLength = characterCount(form.postingPassword)
      if (
        passwordLength > CREDENTIAL_MAX_LENGTH ||
        (passwordLength === 0 && !postingPasswordStored)
      ) {
        context.addIssue({ code: 'custom', path: ['postingPassword'], message: PASSWORD_RULE })
      }
    })
}

export type ServerFormValues = z.infer<ReturnType<typeof serverFormRules>>
