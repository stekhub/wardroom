import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { z } from 'zod'
import { api } from '@/api/client'
import { problemFieldErrors, problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import type { components } from '@/api/schema'

export type PostingIdentity = components['schemas']['PostingIdentity']
export type ArticleDraft = components['schemas']['ArticleDraft']
export type PostedArticle = components['schemas']['PostedArticle']

/** Why the server did not take an article (spec 009, "Sending and errors"). */
export const SERVER_FAILURES = [
  'POSTING_NOT_ALLOWED',
  'AUTH_REQUIRED',
  'POSTING_FAILED',
  'UNREACHABLE',
] as const
export type ServerFailure = (typeof SERVER_FAILURES)[number]

/** A post that did not go through. */
export interface PostingFailure {
  /** Set when the server was asked and did not take the article; trying again can help then. */
  serverFailure: ServerFailure | null
  /** The message of the server, or of the backend. */
  message: string
  /** The Message-ID to try again with, so that a second attempt cannot create a second article. */
  messageId: string | null
  /** The fields of the draft the backend names as invalid, each with its rule. */
  fields: { field: string; message: string }[]
}

const serverFailure = z.object({ type: z.enum(SERVER_FAILURES), messageId: z.string() })

function identityKey(serverId: number) {
  return ['posting-identity', serverId] as const
}

async function fetchIdentity(serverId: number): Promise<PostingIdentity> {
  const { data, response } = await api.GET('/api/servers/{serverId}/identity', {
    params: { path: { serverId } },
  })
  if (data === undefined) {
    throw new Error(`Unexpected answer ${String(response.status)} to the identity request`)
  }
  return data
}

/** Whether the owner has set the name and the email address to post with. */
export function identityIsSet(identity: PostingIdentity): boolean {
  return (identity.name ?? null) !== null && (identity.email ?? null) !== null
}

/** The posting identity of a server. Its fields are `null` until the owner has set one. */
export function usePostingIdentity(serverId: number | undefined) {
  return useQuery({
    queryKey: identityKey(serverId ?? 0),
    queryFn: () => fetchIdentity(serverId ?? 0),
    enabled: serverId !== undefined,
    staleTime: Infinity,
    retry: false,
  })
}

/** Stores the posting identity and puts the stored one in place of the loaded one. */
export function useSaveIdentity(serverId: number) {
  const queryClient = useQueryClient()
  return useCallback(
    async (
      identity: PostingIdentity,
    ): Promise<{ fields: { field: string; message: string }[]; message: string } | null> => {
      const { data, error, response } = await api.PUT('/api/servers/{serverId}/identity', {
        params: { path: { serverId } },
        body: identity,
      })
      if (data === undefined) {
        // An ended session is handled by the API client.
        return response.status === 401
          ? { fields: [], message: '' }
          : { fields: problemFieldErrors(error), message: problemMessage(error, UNEXPECTED_ERROR) }
      }
      queryClient.setQueryData(identityKey(serverId), data)
      return null
    },
    [queryClient, serverId],
  )
}

/**
 * Posts a new discussion or a reply.
 *
 * @returns the posted article, or why it was not posted
 */
export async function postArticle(
  groupId: number,
  draft: ArticleDraft,
): Promise<{ posted: PostedArticle } | { failure: PostingFailure }> {
  const { data, error } = await api.POST('/api/groups/{groupId}/articles', {
    params: { path: { groupId } },
    body: draft,
  })
  if (data !== undefined) {
    return { posted: data }
  }
  const refused = serverFailure.safeParse(error)
  return {
    failure: {
      serverFailure: refused.success ? refused.data.type : null,
      message: problemMessage(error, UNEXPECTED_ERROR),
      messageId: refused.success ? refused.data.messageId : null,
      fields: problemFieldErrors(error),
    },
  }
}
