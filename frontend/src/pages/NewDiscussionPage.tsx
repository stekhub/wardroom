import { ArrowLeft } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router'
import { Skeleton } from '@/components/ui/skeleton'
import { useSubscriptions } from '@/groups/subscriptionState'
import { ReadingColumn } from '@/pages/ReadingColumn'
import { ArticleEditor } from '@/posting/ArticleEditor'

/**
 * The page a new discussion is written on (spec 009), `/groups/{id}/new`. The discussion is posted
 * to this group only. After sending, the owner lands on the thread page of the new discussion.
 */
export function NewDiscussionPage() {
  const groupId = Number(useParams().groupId)
  const navigate = useNavigate()
  const subscriptions = useSubscriptions()

  if (subscriptions.data === undefined) {
    return (
      <ReadingColumn>
        {subscriptions.isError ? (
          <p className="text-sm text-destructive">The group could not be loaded.</p>
        ) : (
          <Skeleton className="h-6 w-64 max-w-full" />
        )}
      </ReadingColumn>
    )
  }
  const subscription = subscriptions.data.find((candidate) => candidate.groupId === groupId)
  const groupPath = `/groups/${String(groupId)}`
  if (subscription === undefined || subscription.postingStatus === 'NOT_ALLOWED') {
    return (
      <ReadingColumn>
        <h1 className="mb-2 text-xl font-semibold tracking-tight">
          {subscription === undefined
            ? 'Not a subscribed group'
            : 'This group does not allow posting'}
        </h1>
        <p className="text-sm text-muted-foreground">
          <Link
            to={subscription === undefined ? '/discover' : groupPath}
            className="font-medium text-foreground underline underline-offset-4"
          >
            {subscription === undefined ? 'Discover groups' : 'Back to the group'}
          </Link>
        </p>
      </ReadingColumn>
    )
  }
  return (
    <ReadingColumn>
      <Link
        to={groupPath}
        aria-label={`Back to ${subscription.name}`}
        className="mb-4 inline-flex min-h-6 max-w-full items-center gap-1.5 rounded-sm text-sm text-muted-foreground outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ArrowLeft aria-hidden="true" className="size-4 shrink-0" />
        {/* The group name comes from the server and is shown as text only. */}
        <span className="truncate">{subscription.name}</span>
      </Link>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">New discussion</h1>
      <ArticleEditor
        groupId={groupId}
        parentId={null}
        initialSubject=""
        initialBody=""
        targetGroups={[subscription.name]}
        moderated={subscription.postingStatus === 'MODERATED'}
        onPosted={(posted) => {
          void navigate(`${groupPath}/threads/${String(posted.articleId)}`)
        }}
        onClose={() => {
          void navigate(groupPath)
        }}
      />
    </ReadingColumn>
  )
}
