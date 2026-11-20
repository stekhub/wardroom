import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import type { components } from '@/api/schema'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { dayOf, formatDay } from '@/groups/days'

type JumpResult = components['schemas']['JumpResult']

/** A jump the backend refused, with its reason for the owner. */
class JumpFailure extends Error {}

/**
 * The date view of a group (spec 008, "Date view"): the group from a day on. It asks the backend
 * for the jump every time it opens (spec 005), says from when it shows the group, and offers
 * "Load more". The threads themselves are added by the discussion view.
 *
 * @param day   the chosen day as `YYYY-MM-DD`
 * @param from  the start of that day in the time zone of the browser, in milliseconds
 */
export function DateView({ groupId, day, from }: { groupId: number; day: string; from: number }) {
  const queryClient = useQueryClient()
  const [loadingMore, setLoadingMore] = useState(false)
  const jumpKey = ['jump', groupId, day] as const
  const jump = useQuery({
    queryKey: jumpKey,
    queryFn: async (): Promise<JumpResult> => {
      const { data, error } = await api.POST('/api/groups/{groupId}/jump', {
        params: { path: { groupId } },
        body: { from },
      })
      if (data === undefined) {
        throw new JumpFailure(problemMessage(error, UNEXPECTED_ERROR))
      }
      return data
    },
    // A jump is asked for when the view opens, and not again behind the owner's back.
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  async function loadMore(rangeId: number) {
    setLoadingMore(true)
    const { data, error, response } = await api.POST(
      '/api/groups/{groupId}/ranges/{rangeId}/extend',
      { params: { path: { groupId, rangeId } }, body: { direction: 'NEWER' } },
    )
    setLoadingMore(false)
    if (data === undefined) {
      if (response.status !== 401) {
        toast.error(problemMessage(error, UNEXPECTED_ERROR))
      }
      return
    }
    queryClient.setQueryData<JumpResult>(jumpKey, (shown) =>
      shown === undefined ? shown : { ...shown, range: data },
    )
  }

  return (
    <section aria-label="Date view">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-1 rounded-lg border bg-muted/50 px-3 py-2 text-sm">
        <p>Showing from {formatDay(from)}</p>
        <Link
          to={`/groups/${String(groupId)}`}
          className="font-medium underline underline-offset-4"
        >
          Back to the newest
        </Link>
      </div>
      {jump.isPending && (
        <div role="status" aria-label="Searching for the date">
          <Skeleton className="h-4 w-72 max-w-full" />
        </div>
      )}
      {jump.isError && (
        <p role="alert" className="text-sm text-destructive">
          {jump.error instanceof JumpFailure ? jump.error.message : UNEXPECTED_ERROR}
        </p>
      )}
      {jump.data !== undefined && (
        <>
          <OldestArticleNote jump={jump.data} day={day} />
          <p className="text-sm text-muted-foreground">
            The threads of this group are not shown yet. The articles{' '}
            {String(jump.data.range.firstNumber)} to {String(jump.data.range.lastNumber)} are
            loaded.
          </p>
          {!jump.data.range.atNewest && (
            <div className="mt-6 flex justify-center">
              <Button
                type="button"
                variant="outline"
                disabled={loadingMore}
                onClick={() => void loadMore(jump.data.range.rangeId)}
              >
                {loadingMore ? 'Loading…' : 'Load more'}
              </Button>
            </div>
          )}
        </>
      )}
    </section>
  )
}

/** Says from when the oldest article is, when the chosen day lies before it (spec 005). */
function OldestArticleNote({ jump, day }: { jump: JumpResult; day: string }) {
  if (!jump.clampedToOldest) {
    return null
  }
  if (jump.startDate === undefined || jump.startDate === null) {
    return <p className="mb-4 text-sm">This day is before the oldest article on the server.</p>
  }
  // An article of the chosen day itself is what the owner asked for.
  if (dayOf(jump.startDate) === day) {
    return null
  }
  return (
    <p className="mb-4 text-sm">
      The oldest article on the server is from {formatDay(jump.startDate)}.
    </p>
  )
}
