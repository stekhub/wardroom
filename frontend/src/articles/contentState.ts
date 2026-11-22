import { useQuery } from '@tanstack/react-query'
import { api } from '@/api/client'
import { problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import type { components } from '@/api/schema'

/** The content of an article as the backend returns it. */
export type Content = components['schemas']['ArticleContent']

/** Why the content of an article could not be shown. */
export class ContentError extends Error {
  /** Whether the server answered that it no longer has the article (410). */
  readonly gone: boolean

  constructor(message: string, gone: boolean) {
    super(message)
    this.gone = gone
  }
}

export function contentKey(articleId: number) {
  return ['article-content', articleId] as const
}

export async function fetchContent(articleId: number, askAgain: boolean): Promise<Content> {
  const parameters = { params: { path: { articleId } } }
  const { data, error, response } = askAgain
    ? await api.POST('/api/articles/{articleId}/content/retry', parameters)
    : await api.GET('/api/articles/{articleId}/content', parameters)
  if (data === undefined) {
    throw new ContentError(problemMessage(error, UNEXPECTED_ERROR), response.status === 410)
  }
  return data
}

/**
 * The content of an article when it has been loaded for display, otherwise `undefined`. It never
 * asks the backend: what is not displayed yet cannot be answered (spec 009).
 */
export function useLoadedContent(articleId: number): Content | undefined {
  return useQuery<Content, ContentError>({
    queryKey: contentKey(articleId),
    queryFn: () => fetchContent(articleId, false),
    enabled: false,
    staleTime: Infinity,
    retry: false,
  }).data
}
