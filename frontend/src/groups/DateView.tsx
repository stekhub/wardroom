import { useQuery, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import type { components } from '@/api/schema'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { dayOf, formatDay } from '@/groups/days'
import { ThreadCards } from '@/threads/ThreadCards'
import { LIST_KEPT_MILLIS, threadListsOfGroup, useThreadList } from '@/threads/threadState'

type JumpResult = components['schemas']['JumpResult']
type Range = components['schemas']['Range']

/** A jump the backend refused, with its reason for the owner. */
class JumpFailure extends Error {}

function jumpKey(groupId: number, day: string) {
  return ['jump', groupId, day] as const
}

/** The view of a day in the cache of thread lists. */
function dateListView(day: string) {
  return `until:${day}`
}

/**
 * The date view of a group (spec 008, "Date view"): the group up to a day. It asks the backend for
 * the jump when the day is chosen (spec 005), says up to when it shows the group, lists the threads
 * of the range of the jump that began on or before that day, newest first, and offers "Load older
 * discussions".
 *
 * The jump is kept as long as its list, so going back from a thread shows the view at once. Only
 * the date view of the last jump is kept per group: a later jump can join the range of an earlier
 * one with its own, and the earlier range is then gone. "Load older discussions" in the normal
 * view can do the same; when the backend says that the range is gone, the jump is run again.
 *
 * @param day   the chosen day as `YYYY-MM-DD`
 * @param until the end of that day in the time zone of the browser, in milliseconds
 */
export function DateView({ groupId, day, until }: { groupId: number; day: string; until: number }) {
  const queryClient = useQueryClient()
  const jump = useQuery({
    queryKey: jumpKey(groupId, day),
    queryFn: async (): Promise<JumpResult> => {
      const { data, error } = await api.POST('/api/groups/{groupId}/jump', {
        params: { path: { groupId } },
        body: { until },
      })
      if (data === undefined) {
        throw new JumpFailure(problemMessage(error, UNEXPECTED_ERROR))
      }
      forgetDateViews(groupId, day)
      return data
    },
    // Asked for once when the day is chosen, and not again behind the owner's back.
    staleTime: Infinity,
    gcTime: LIST_KEPT_MILLIS,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  const range = jump.data?.range ?? null

  /**
   * After a jump, the jumps of other days and every date view list of the group are forgotten:
   * their ranges may be gone, and the list of this day is asked for with the new range.
   */
  function forgetDateViews(ofGroupId: number, jumpedDay: string) {
    queryClient.removeQueries({
      predicate: (query) => {
        const key = query.queryKey
        if (key[0] === 'jump') {
          return key[1] === ofGroupId && key[2] !== jumpedDay
        }
        const listKey = threadListsOfGroup(ofGroupId)
        return (
          key[0] === listKey[0] &&
          key[1] === listKey[1] &&
          typeof key[2] === 'string' &&
          key[2].startsWith('until:')
        )
      },
    })
  }

  /** The kept range is gone: the jump is run again, and the view starts over with its range. */
  const jumpAgain = useCallback(() => {
    void queryClient.resetQueries({ queryKey: jumpKey(groupId, day), exact: true })
  }, [queryClient, groupId, day])

  return (
    <section aria-label="Date view">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-1 rounded-lg border bg-muted/50 px-3 py-2 text-sm">
        {/* The last millisecond of the chosen day. */}
        <p>Showing up to {formatDay(until - 1)}</p>
        <Link
          to={`/groups/${String(groupId)}`}
          className="font-medium underline underline-offset-4"
        >
          Back to the newest
        </Link>
      </div>
      {jump.isPending && (
        <>
          {/* The search asks the server several times and can take seconds (spec 005). */}
          <p role="status" className="mb-4 flex items-center gap-2 text-sm text-muted-foreground">
            <LoaderCircle
              aria-hidden="true"
              className="size-4 animate-spin motion-reduce:animate-none"
            />
            Searching the server for this day…
          </p>
          <div aria-hidden="true" className="flex flex-col gap-3">
            {[0, 1, 2].map((card) => (
              <div key={card} className="rounded-xl border p-4">
                <Skeleton className="h-5 w-3/5" />
                <Skeleton className="mt-3 h-4 w-2/5" />
                <Skeleton className="mt-3 h-4 w-11/12" />
              </div>
            ))}
          </div>
        </>
      )}
      {jump.isError && (
        <p role="alert" className="text-sm text-destructive">
          {jump.error instanceof JumpFailure ? jump.error.message : UNEXPECTED_ERROR}
        </p>
      )}
      {jump.data !== undefined &&
        (range === null ? (
          <BeforeOldestArticle groupId={groupId} oldestDate={jump.data.oldestDate ?? null} />
        ) : (
          <DateThreads
            groupId={groupId}
            day={day}
            until={until}
            range={range}
            onRangeGone={jumpAgain}
          />
        ))}
    </section>
  )
}

interface DateThreadsProps {
  groupId: number
  day: string
  until: number
  range: Range
  onRangeGone: () => void
}

/** The threads of the range up to the day, and "Load older discussions" at their end. */
function DateThreads({ groupId, day, until, range, onRangeGone }: DateThreadsProps) {
  const queryClient = useQueryClient()
  const [loadingOlder, setLoadingOlder] = useState(false)
  // The list is asked for again with the range as it is after "Load older discussions".
  const rangeId = useRef(range.rangeId)
  const list = useThreadList(groupId, dateListView(day), () => ({
    until: new Date(until).toISOString(),
    rangeId: rangeId.current,
  }))
  const { refetch } = list
  const lastPage = list.data?.pages.at(-1)

  // The range was joined with another one since the jump (ADR 0033).
  const rangeGone = list.error?.status === 400 && list.error.fields.includes('rangeId')
  useEffect(() => {
    if (rangeGone) {
      onRangeGone()
    }
  }, [rangeGone, onRangeGone])

  async function loadOlder() {
    setLoadingOlder(true)
    const { data, error, response } = await api.POST(
      '/api/groups/{groupId}/ranges/{rangeId}/extend',
      { params: { path: { groupId, rangeId: rangeId.current } } },
    )
    if (data === undefined) {
      if (response.status === 404) {
        // The range is gone, or the group with it; the jump says which.
        onRangeGone()
        return
      }
      if (response.status !== 401) {
        toast.error(problemMessage(error, UNEXPECTED_ERROR))
      }
    } else {
      rangeId.current = data.rangeId
      // A view opened again from the cache continues with the range as it is now.
      queryClient.setQueryData<JumpResult>(jumpKey(groupId, day), (shown) =>
        shown === undefined ? shown : { ...shown, range: data },
      )
      await refetch()
    }
    setLoadingOlder(false)
  }

  return (
    <ThreadCards
      groupId={groupId}
      list={list}
      empty={<p className="text-sm text-muted-foreground">No discussions up to this day</p>}
      end={
        lastPage?.lowWatermarkReached === false && (
          <div className="mt-6 flex justify-center">
            <Button
              type="button"
              variant="outline"
              disabled={loadingOlder}
              onClick={() => void loadOlder()}
            >
              {loadingOlder ? 'Loading…' : 'Load older discussions'}
            </Button>
          </div>
        )
      }
    />
  )
}

/** The chosen day lies before everything the server has (spec 005). */
function BeforeOldestArticle({
  groupId,
  oldestDate,
}: {
  groupId: number
  oldestDate: number | null
}) {
  if (oldestDate === null) {
    return <p className="text-sm">This day is before the oldest article on the server.</p>
  }
  return (
    <p className="text-sm">
      This day is before the oldest article on the server, which is from {formatDay(oldestDate)}.{' '}
      <Link
        to={`/groups/${String(groupId)}?until=${dayOf(oldestDate)}`}
        className="font-medium whitespace-nowrap underline underline-offset-4"
      >
        Show that day
      </Link>
    </p>
  )
}
