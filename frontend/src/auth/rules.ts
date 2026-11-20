import { z } from 'zod'

/**
 * The rules of spec 001 for username and password. The backend checks them again; they are
 * repeated here so the owner sees a mistake before the form is sent.
 */
const USERNAME_MAX_LENGTH = 64
const PASSWORD_MIN_LENGTH = 12
const PASSWORD_MAX_LENGTH = 128

/** Counts characters like the backend does: an emoji is one character, not two. */
function characterCount(text: string): number {
  return Array.from(text).length
}

export const usernameRule = z.string().refine(
  (username) => {
    const length = characterCount(username.trim())
    return length >= 1 && length <= USERNAME_MAX_LENGTH
  },
  { error: `Enter a username with 1 to ${String(USERNAME_MAX_LENGTH)} characters.` },
)

export const newPasswordRule = z.string().refine(
  (password) => {
    const length = characterCount(password)
    return length >= PASSWORD_MIN_LENGTH && length <= PASSWORD_MAX_LENGTH
  },
  {
    error: `Enter a password with ${String(PASSWORD_MIN_LENGTH)} to ${String(PASSWORD_MAX_LENGTH)} characters.`,
  },
)

export const PASSWORDS_DIFFER = 'The two passwords are not the same.'
