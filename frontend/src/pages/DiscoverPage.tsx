import { Search } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { GroupIcon } from '@/groups/GroupIcon'
import { GroupListRefresh } from '@/groups/GroupListRefresh'
import {
  type Group,
  GROUPS_PER_PAGE,
  useGroupListRefreshStatus,
  useGroupPage,
} from '@/groups/groupListState'
import { PageHeader } from '@/pages/PageHeader'
import { useServers } from '@/server/serverState'

const COUNT = new Intl.NumberFormat('en')

/** The search starts this long after the last key press. */
const SEARCH_DELAY_MILLIS = 300

/** Rows of the placeholder that stands in for the list while it loads. */
const SKELETON_ROWS = 8

/**
 * The discover view (UI concept, spec 003): the groups of the server by name, 50 per page, with
 * a search over names and descriptions. Search text and page are part of the URL, so the back
 * button and bookmarks work.
 */
export function DiscoverPage() {
  const server = useServers().data?.[0]

  if (server === undefined) {
    return null
  }
  return <GroupList serverId={server.id} />
}

function GroupList({ serverId }: { serverId: number }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const query = searchParams.get('q') ?? ''
  const page = pageNumber(searchParams.get('page'))
  const [searchText, setSearchText] = useState(query)
  // After the back button changed the search in the URL, the search box shows it.
  const [shownQuery, setShownQuery] = useState(query)
  if (query !== shownQuery) {
    setShownQuery(query)
    if (searchText.trim() !== query) {
      setSearchText(query)
    }
  }
  const groups = useGroupPage(serverId, query, page - 1)
  const refreshRunning = useGroupListRefreshStatus(serverId).data?.running === true

  // The search follows the typing with a short delay, and starts again at the first page.
  useEffect(() => {
    if (searchText.trim() === query) {
      return
    }
    const timer = window.setTimeout(() => {
      const text = searchText.trim()
      setSearchParams(text === '' ? {} : { q: text }, { replace: true })
    }, SEARCH_DELAY_MILLIS)
    return () => {
      window.clearTimeout(timer)
    }
  }, [searchText, query, setSearchParams])

  function showPage(newPage: number) {
    const parameters: Record<string, string> = {}
    if (query !== '') {
      parameters.q = query
    }
    if (newPage > 1) {
      parameters.page = String(newPage)
    }
    setSearchParams(parameters)
  }

  const total = groups.data?.total ?? 0
  const pageCount = Math.max(1, Math.ceil(total / GROUPS_PER_PAGE))

  return (
    <div className="p-6">
      <PageHeader title="Discover groups" />
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="relative w-full max-w-sm">
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="search"
            aria-label="Search groups"
            placeholder="Search by name or description"
            className="pl-8"
            maxLength={200}
            value={searchText}
            onChange={(event) => {
              setSearchText(event.target.value)
            }}
          />
        </div>
        <GroupListRefresh serverId={serverId} />
      </div>

      {groups.data === undefined ? (
        groups.isError ? (
          <p className="text-sm text-destructive">The groups could not be loaded. Try again.</p>
        ) : (
          <GroupListSkeleton />
        )
      ) : groups.data.items.length === 0 ? (
        <EmptyList query={query} refreshRunning={refreshRunning} />
      ) : (
        <>
          <ul
            aria-label="Groups"
            className="divide-y rounded-lg border bg-card"
            aria-busy={groups.isPlaceholderData}
          >
            {groups.data.items.map((group) => (
              <GroupRow key={group.id} group={group} />
            ))}
          </ul>
          <nav
            aria-label="Pages"
            className="mt-4 flex items-center justify-between gap-3 text-sm text-muted-foreground"
          >
            <span>
              {COUNT.format(total)} {total === 1 ? 'group' : 'groups'} · Page {COUNT.format(page)}{' '}
              of {COUNT.format(pageCount)}
            </span>
            <span className="flex gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={page <= 1}
                onClick={() => {
                  showPage(page - 1)
                }}
              >
                Previous
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={page >= pageCount}
                onClick={() => {
                  showPage(page + 1)
                }}
              >
                Next
              </Button>
            </span>
          </nav>
        </>
      )}
    </div>
  )
}

/** Name and description come from the server and are shown as text only (spec 003). */
function GroupRow({ group }: { group: Group }) {
  const description = group.description ?? ''
  return (
    <li className="flex items-center gap-3 px-4 py-2.5">
      <GroupIcon name={group.name} description={group.description} size="medium" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium" title={group.name}>
          {group.name}
        </p>
        {description !== '' && (
          <p className="truncate text-sm text-muted-foreground" title={description}>
            {description}
          </p>
        )}
      </div>
      {group.subscribed && <Badge variant="secondary">Subscribed</Badge>}
      {group.postingStatus === 'MODERATED' && <Badge variant="outline">Moderated</Badge>}
      {group.postingStatus === 'NOT_ALLOWED' && <Badge variant="outline">No posting</Badge>}
    </li>
  )
}

function EmptyList({ query, refreshRunning }: { query: string; refreshRunning: boolean }) {
  if (query !== '') {
    return <p className="text-sm text-muted-foreground">No group matches your search.</p>
  }
  if (refreshRunning) {
    return (
      <>
        <p className="mb-4 text-sm text-muted-foreground">
          The group list is being fetched from your server. The groups appear here when it is
          complete.
        </p>
        <GroupListSkeleton />
      </>
    )
  }
  return (
    <p className="text-sm text-muted-foreground">
      No groups yet. Refresh the group list to fetch them from your server.
    </p>
  )
}

function GroupListSkeleton() {
  return (
    <div aria-hidden="true" className="divide-y rounded-lg border bg-card">
      {Array.from({ length: SKELETON_ROWS }, (_, row) => (
        <div key={row} className="flex items-center gap-3 px-4 py-2.5">
          <Skeleton className="size-10 rounded-full" />
          <div className="grid flex-1 gap-1.5">
            <Skeleton className="h-4 w-48" />
            <Skeleton className="h-4 w-72 max-w-full" />
          </div>
        </div>
      ))}
    </div>
  )
}

/** The page in the URL counts from 1; anything else than a positive number means the first page. */
function pageNumber(parameter: string | null): number {
  const page = Number(parameter)
  return Number.isInteger(page) && page >= 1 ? page : 1
}
