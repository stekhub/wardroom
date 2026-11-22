import { CheckCheck, EyeOff } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { UNEXPECTED_ERROR } from '@/api/problem'
import { AuthorIcon } from '@/articles/AuthorIcon'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { useIgnoring } from '@/ignoring/ignoreState'
import { cn } from '@/lib/utils'
import { type ThreadCard, useShownReadState } from '@/threads/threadState'
import { Time } from '@/threads/Time'
import { authorName } from '@/threads/times'

const COUNT = new Intl.NumberFormat('en')

/**
 * The card of a thread in the list of a group (spec 008, "Thread card"). The whole card opens the
 * thread page. Subject, author and excerpt come from an article and are shown as text only.
 */
export function DiscussionCard({ groupId, card }: { groupId: number; card: ThreadCard }) {
  const { markThreadRead } = useShownReadState()
  const { ignore } = useIgnoring()
  const unread = card.unreadCount > 0

  async function markRead() {
    if (!(await markThreadRead(groupId, card.rootId, card.rootId))) {
      toast.error(UNEXPECTED_ERROR)
    }
  }

  return (
    <article className="group relative rounded-xl border bg-card p-4 transition-shadow duration-150 focus-within:shadow-md hover:shadow-md motion-reduce:transition-none">
      <h2 className={cn('pr-20 text-base leading-snug', unread ? 'font-bold' : 'font-medium')}>
        {/* The link covers the card, so a click anywhere on it opens the thread. */}
        <Link
          to={`/groups/${String(groupId)}/threads/${String(card.rootId)}`}
          state={{ fromList: true }}
          className="rounded-sm outline-none after:absolute after:inset-0 after:rounded-xl focus-visible:after:ring-2 focus-visible:after:ring-ring"
        >
          {card.subject ?? '(no subject)'}
        </Link>
      </h2>
      <p className="mt-1.5 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-sm text-muted-foreground">
        <AuthorIcon authorKey={card.authorKey ?? null} size="small" />
        <span className="max-w-64 truncate text-foreground">{authorName(card.from)}</span>
        <span aria-hidden="true">·</span>
        <Time millis={card.date} />
        <span aria-hidden="true">·</span>
        <span>
          {COUNT.format(card.messageCount)} {card.messageCount === 1 ? 'message' : 'messages'}
        </span>
        {card.messageCount > 1 &&
          card.latestActivity !== null &&
          card.latestActivity !== undefined && (
            <>
              <span aria-hidden="true">·</span>
              <span>
                latest <Time millis={card.latestActivity} />
              </span>
            </>
          )}
        {unread && (
          <>
            <span aria-hidden="true">·</span>
            <span className="font-medium text-primary">
              <span aria-hidden="true">● </span>
              {COUNT.format(card.unreadCount)} new
            </span>
          </>
        )}
      </p>
      {card.excerpt !== null && card.excerpt !== undefined && (
        <p className="mt-2 line-clamp-2 text-sm break-words text-muted-foreground">
          {card.excerpt}
        </p>
      )}
      <div className="absolute top-2.5 right-2.5 flex gap-1 opacity-0 transition-opacity duration-150 group-focus-within:opacity-100 group-hover:opacity-100 motion-reduce:transition-none">
        {unread && (
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label="Mark thread as read"
                onClick={() => void markRead()}
              >
                <CheckCheck />
              </Button>
            </TooltipTrigger>
            <TooltipContent>Mark thread as read</TooltipContent>
          </Tooltip>
        )}
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label="Ignore thread"
              onClick={() => void ignore('THREAD', card.cardArticleId)}
            >
              <EyeOff />
            </Button>
          </TooltipTrigger>
          <TooltipContent>Ignore thread</TooltipContent>
        </Tooltip>
      </div>
    </article>
  )
}
