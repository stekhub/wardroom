import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { problemFieldErrors, problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import type { components } from '@/api/schema'
import { useReloadSubscriptions } from '@/groups/subscriptionState'
import { allThreadLists } from '@/threads/threadState'

export type IgnoreRule = components['schemas']['IgnoreRule']
export type IgnoreKind = IgnoreRule['kind']
type IgnoreRuleRequest = components['schemas']['IgnoreRuleRequest']

const IGNORE_RULES_KEY = ['ignore-rules'] as const

/** How long the toast with "Undo" stays. */
const UNDO_MILLIS = 8_000

async function fetchIgnoreRules(): Promise<IgnoreRule[]> {
  const { data, response } = await api.GET('/api/ignore-rules')
  if (data === undefined) {
    throw new Error(`Unexpected answer ${String(response.status)} to the ignore rules request`)
  }
  return data
}

/** The ignore rules of the owner, the newest first (spec 010). */
export function useIgnoreRules() {
  return useQuery({ queryKey: IGNORE_RULES_KEY, queryFn: fetchIgnoreRules, retry: false })
}

/**
 * Ignoring an author, a thread, a group or a word in subjects, and stopping it (spec 010, 022,
 * 023). A rule changes what the lists show and what counts as unread, so the thread lists, the
 * group list and the sidebar are asked for again after it.
 */
export function useIgnoring() {
  const queryClient = useQueryClient()
  const reloadSubscriptions = useReloadSubscriptions()

  const showChange = useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: IGNORE_RULES_KEY }),
      queryClient.invalidateQueries({ queryKey: allThreadLists() }),
      // The group list of every server (`groupListState`).
      queryClient.invalidateQueries({ queryKey: ['groups'] }),
      reloadSubscriptions(),
    ])
  }, [queryClient, reloadSubscriptions])

  /**
   * Removes the rules.
   *
   * @returns whether the backend removed them all
   */
  const stopIgnoring = useCallback(
    async (ruleIds: number[]): Promise<boolean> => {
      const answers = await Promise.all(
        ruleIds.map((ruleId) =>
          api.DELETE('/api/ignore-rules/{ruleId}', { params: { path: { ruleId } } }),
        ),
      )
      await showChange()
      return answers.every(({ response }) => response.ok)
    },
    [showChange],
  )

  /**
   * Creates the rule and says so with a toast that offers "Undo".
   *
   * @param shown called with the rule when it is in place, and with `null` when it was undone
   */
  const create = useCallback(
    async (
      request: IgnoreRuleRequest,
      message: string,
      shown?: (rule: IgnoreRule | null) => void,
    ) => {
      const { data, error, response } = await api.POST('/api/ignore-rules', { body: request })
      if (data === undefined) {
        if (response.status !== 401) {
          toast.error(problemMessage(error, UNEXPECTED_ERROR))
        }
        return
      }
      shown?.(data)
      toast(message, {
        // Longer than a toast that only informs, so that "Undo" can be reached.
        duration: UNDO_MILLIS,
        action: {
          label: 'Undo',
          onClick: () => {
            void stopIgnoring([data.id]).then((undone) => {
              if (undone) {
                shown?.(null)
              } else {
                toast.error(UNEXPECTED_ERROR)
              }
            })
          },
        },
      })
      await showChange()
    },
    [showChange, stopIgnoring],
  )

  /** Ignores the author or the thread of the article. */
  const ignore = useCallback(
    (kind: 'AUTHOR' | 'THREAD', articleId: number, shown?: (rule: IgnoreRule | null) => void) =>
      create({ kind, articleId }, kind === 'THREAD' ? 'Thread ignored' : 'Author ignored', shown),
    [create],
  )

  /** Ignores a group in the group list (spec 022). */
  const ignoreGroup = useCallback(
    (groupId: number) => create({ kind: 'GROUP', groupId }, 'Group ignored'),
    [create],
  )

  /**
   * Ignores a word or phrase in subjects, for all groups or for one (spec 023).
   *
   * @returns the rule, or the message of the backend when it refused the word
   */
  const ignoreWord = useCallback(
    async (text: string, groupId: number | null): Promise<IgnoreRule | string> => {
      const { data, error, response } = await api.POST('/api/ignore-rules', {
        body: { kind: 'SUBJECT', text, ...(groupId === null ? {} : { groupId }) },
      })
      if (data === undefined) {
        if (response.status === 401) {
          return ''
        }
        return problemFieldErrors(error)[0]?.message ?? problemMessage(error, UNEXPECTED_ERROR)
      }
      await showChange()
      return data
    },
    [showChange],
  )

  return { ignore, ignoreGroup, ignoreWord, stopIgnoring }
}
