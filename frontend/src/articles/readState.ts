import { api } from '@/api/client'

/**
 * Marks an article as read (spec 007).
 *
 * @returns whether the backend did it
 */
export async function markArticleRead(articleId: number): Promise<boolean> {
  const { response } = await api.PUT('/api/articles/{articleId}/read', {
    params: { path: { articleId } },
  })
  return response.ok
}

/**
 * Marks an article as unread again (spec 014).
 *
 * @returns the status of the answer: `204` when it is unread, `404` when the article is gone
 */
export async function markArticleUnread(articleId: number): Promise<number> {
  const { response } = await api.DELETE('/api/articles/{articleId}/read', {
    params: { path: { articleId } },
  })
  return response.status
}
