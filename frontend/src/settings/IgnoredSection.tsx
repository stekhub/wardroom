import { type SyntheticEvent, useId, useState } from 'react'
import { toast } from 'sonner'
import { problemMessage, UNEXPECTED_ERROR } from '@/api/problem'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDay } from '@/groups/days'
import { useSubscriptions } from '@/groups/subscriptionState'
import { type IgnoreRule, useIgnoreRules, useIgnoring } from '@/ignoring/ignoreState'
import { SettingsSectionFrame } from '@/settings/SettingsSectionFrame'
import { type Settings, useSaveSettings, useSettings } from '@/settings/settingsState'

/** The choice of the group field that stands for a word that is ignored in all groups. */
const ALL_GROUPS = ''

/** A word or phrase is at most this long (spec 023). */
const WORD_MAX_LENGTH = 100

/**
 * The settings section "Ignored": the setting that hides binary groups in the group list (spec
 * 022), and what the owner ignores, each with a way to stop ignoring it: authors and threads (spec
 * 010), groups (spec 022) and words in subjects (spec 023). Labels come from articles and from the
 * server and are shown as text only.
 */
export function IgnoredSection() {
  const rules = useIgnoreRules()
  const settings = useSettings()

  return (
    <SettingsSectionFrame title="Ignored">
      <p className="text-sm text-muted-foreground">
        Ignoring hides groups, threads and articles. It deletes nothing: when you stop ignoring,
        everything is shown as before.
      </p>
      {settings.data !== undefined && <BinaryGroupsForm settings={settings.data} />}
      {settings.data === undefined && settings.isError && (
        <p role="alert" className="text-sm text-destructive">
          The setting for binary groups could not be loaded.
        </p>
      )}
      {rules.data === undefined ? (
        rules.isError ? (
          <p role="alert" className="text-sm text-destructive">
            The ignored authors, threads, groups and words could not be loaded.
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
          <RuleList
            title="Groups"
            empty="You ignore no groups. To ignore one, use the button in its row of the group list."
            rules={rules.data.filter((rule) => rule.kind === 'GROUP')}
          />
          <RuleList
            title="Words in subjects"
            empty="You ignore no words."
            rules={rules.data.filter((rule) => rule.kind === 'SUBJECT')}
            detail={(rule) => rule.groupName ?? 'All groups'}
          >
            <WordForm />
          </RuleList>
        </>
      )}
    </SettingsSectionFrame>
  )
}

/** "Hide binary groups", with the save button every settings section has (UI concept). */
function BinaryGroupsForm({ settings }: { settings: Settings }) {
  const saveSettings = useSaveSettings()
  const checkboxId = useId()
  const hintId = useId()
  const [hideBinaryGroups, setHideBinaryGroups] = useState(settings.hideBinaryGroups)
  const [saving, setSaving] = useState(false)

  async function save(event: SyntheticEvent) {
    event.preventDefault()
    setSaving(true)
    const { data, error, response } = await saveSettings(settings, { hideBinaryGroups })
    setSaving(false)
    if (data !== undefined) {
      toast.success('Settings saved')
    } else if (response.status !== 401) {
      toast.error(problemMessage(error, UNEXPECTED_ERROR))
    }
  }

  return (
    <form className="grid gap-3" onSubmit={(event) => void save(event)}>
      <div className="grid gap-1">
        <div className="flex items-center gap-2">
          <Checkbox
            id={checkboxId}
            aria-describedby={hintId}
            checked={hideBinaryGroups}
            onCheckedChange={(checked) => {
              setHideBinaryGroups(checked === true)
            }}
          />
          <Label htmlFor={checkboxId}>Hide binary groups</Label>
        </div>
        <p id={hintId} className="text-xs text-muted-foreground">
          Hides groups whose names show that they carry files, such as alt.binaries.*.
        </p>
      </div>
      <div>
        <Button type="submit" disabled={hideBinaryGroups === settings.hideBinaryGroups || saving}>
          Save
        </Button>
      </div>
    </form>
  )
}

/** Adds a word or phrase, for all groups or for one subscribed group (spec 023). */
function WordForm() {
  const { ignoreWord } = useIgnoring()
  const subscriptions = useSubscriptions()
  const wordId = useId()
  const groupFieldId = useId()
  const errorId = useId()
  const [text, setText] = useState('')
  const [group, setGroup] = useState(ALL_GROUPS)
  const [error, setError] = useState('')
  const [adding, setAdding] = useState(false)

  async function add(event: SyntheticEvent) {
    event.preventDefault()
    const word = text.trim()
    if (word === '') {
      setError('Enter a word or a phrase.')
      return
    }
    if (!/[\p{L}\p{N}]/u.test(word)) {
      setError('The word must have at least one letter or digit.')
      return
    }
    setAdding(true)
    const answer = await ignoreWord(word, group === ALL_GROUPS ? null : Number(group))
    setAdding(false)
    if (typeof answer === 'string') {
      setError(answer)
      return
    }
    setError('')
    setText('')
  }

  return (
    <form className="grid gap-2" noValidate onSubmit={(event) => void add(event)}>
      <div className="flex flex-wrap items-end gap-3">
        <div className="grid min-w-40 flex-1 gap-1.5">
          <Label htmlFor={wordId}>Word or phrase</Label>
          <Input
            id={wordId}
            value={text}
            maxLength={WORD_MAX_LENGTH}
            aria-invalid={error !== ''}
            aria-describedby={error === '' ? undefined : errorId}
            onChange={(event) => {
              setText(event.target.value)
              setError('')
            }}
          />
        </div>
        <div className="grid min-w-40 flex-1 gap-1.5">
          <Label htmlFor={groupFieldId}>In</Label>
          <NativeSelect
            id={groupFieldId}
            value={group}
            onChange={(event) => {
              setGroup(event.target.value)
            }}
          >
            <NativeSelectOption value={ALL_GROUPS}>All groups</NativeSelectOption>
            {subscriptions.data?.map((subscription) => (
              <NativeSelectOption key={subscription.groupId} value={String(subscription.groupId)}>
                {subscription.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </div>
        <Button type="submit" variant="outline" disabled={adding}>
          Add
        </Button>
      </div>
      {error !== '' && (
        <p id={errorId} role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      <p className="text-xs text-muted-foreground">
        A thread is hidden when its subject has the word. Whole words match, and upper and lower
        case do not matter: “java” hides “Java 25 released”, not “JavaScript tips”.
      </p>
    </form>
  )
}

interface RuleListProps {
  title: string
  empty: string
  rules: IgnoreRule[]
  /** What stands before the date of a rule, for example the group of a word. */
  detail?: (rule: IgnoreRule) => string
  /** Shown between the title and the list, for example the form that adds a rule. */
  children?: React.ReactNode
}

function RuleList({ title, empty, rules, detail, children }: RuleListProps) {
  const { stopIgnoring } = useIgnoring()
  const titleId = useId()

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
      {children}
      {rules.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <ul aria-labelledby={titleId} className="divide-y rounded-lg border">
          {rules.map((rule) => (
            <li key={rule.id} className="flex items-center justify-between gap-4 px-3 py-2">
              <div className="min-w-0">
                <p className="truncate text-sm font-medium" title={rule.label}>
                  {rule.label}
                </p>
                <p className="truncate text-xs text-muted-foreground">
                  {detail === undefined ? '' : `${detail(rule)} · `}Added{' '}
                  {formatDay(rule.createdAt)}
                </p>
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
