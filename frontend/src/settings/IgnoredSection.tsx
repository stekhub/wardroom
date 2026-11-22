import { toast } from 'sonner'
import { UNEXPECTED_ERROR } from '@/api/problem'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDay } from '@/groups/days'
import { type IgnoreRule, useIgnoreRules, useIgnoring } from '@/ignoring/ignoreState'
import { SettingsSectionFrame } from '@/settings/SettingsSectionFrame'

/**
 * The settings section "Ignored": the authors and threads the owner ignores, each with a way to
 * stop ignoring it (spec 010). Labels come from articles and are shown as text only.
 */
export function IgnoredSection() {
  const rules = useIgnoreRules()

  return (
    <SettingsSectionFrame title="Ignored">
      <p className="text-sm text-muted-foreground">
        Ignoring hides articles and threads in every group. It deletes nothing: when you stop
        ignoring, everything is shown as before.
      </p>
      {rules.data === undefined ? (
        rules.isError ? (
          <p role="alert" className="text-sm text-destructive">
            The ignored authors and threads could not be loaded.
          </p>
        ) : (
          <div role="status" aria-label="Loading" className="grid gap-3">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        )
      ) : (
        <>
          <RuleList
            title="Authors"
            empty="You ignore no authors."
            rules={rules.data.filter((rule) => rule.kind === 'AUTHOR')}
          />
          <RuleList
            title="Threads"
            empty="You ignore no threads."
            rules={rules.data.filter((rule) => rule.kind === 'THREAD')}
          />
        </>
      )}
    </SettingsSectionFrame>
  )
}

function RuleList({ title, empty, rules }: { title: string; empty: string; rules: IgnoreRule[] }) {
  const { stopIgnoring } = useIgnoring()
  const titleId = `ignored-${title.toLowerCase()}`

  async function remove(rule: IgnoreRule) {
    if (!(await stopIgnoring([rule.id]))) {
      toast.error(UNEXPECTED_ERROR)
    }
  }

  return (
    <div className="grid gap-2">
      <h3 id={titleId} className="text-sm font-medium">
        {title}
      </h3>
      {rules.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <ul aria-labelledby={titleId} className="divide-y rounded-lg border">
          {rules.map((rule) => (
            <li key={rule.id} className="flex items-center justify-between gap-4 px-3 py-2">
              <div className="min-w-0">
                <p className="truncate text-sm font-medium">{rule.label}</p>
                <p className="text-xs text-muted-foreground">Added {formatDay(rule.createdAt)}</p>
              </div>
              <Button
                type="button"
                variant="outline"
                size="sm"
                aria-label={`Stop ignoring ${rule.label}`}
                onClick={() => void remove(rule)}
              >
                Remove
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
