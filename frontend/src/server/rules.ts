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
 */
export function serverFormRules(passwordStored: boolean) {
  return z
    .object({
      name: z.string().refine(
        (name) => {
          const length = characterCount(name.trim())
          return length >= 1 && length <= NAME_MAX_LENGTH
        },
        { error: `Enter a name with 1 to ${String(NAME_MAX_LENGTH)} characters.` },
      ),
      host: z.string().refine((host) => HOST.test(host.trim()), {
        error: 'Enter a host name or an IP address.',
      }),
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
    })
    .superRefine((form, context) => {
      if (form.authMethod !== 'USER_PASS') {
        return
      }
      if (form.tlsMode !== 'IMPLICIT') {
        context.addIssue({
          code: 'custom',
          path: ['authMethod'],
          message: 'A login with username and password needs TLS.',
        })
      }
      const usernameLength = characterCount(form.username)
      if (usernameLength < 1 || usernameLength > CREDENTIAL_MAX_LENGTH) {
        context.addIssue({ code: 'custom', path: ['username'], message: USERNAME_RULE })
      }
      const passwordLength = characterCount(form.password)
      if (passwordLength > CREDENTIAL_MAX_LENGTH || (passwordLength === 0 && !passwordStored)) {
        context.addIssue({ code: 'custom', path: ['password'], message: PASSWORD_RULE })
      }
    })
}

export type ServerFormValues = z.infer<ReturnType<typeof serverFormRules>>
