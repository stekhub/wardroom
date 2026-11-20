import { CalendarSearch } from 'lucide-react'
import { type SyntheticEvent, useId, useState } from 'react'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { dayOf, startOfDay } from '@/groups/days'

/**
 * The date picker of the group header (spec 005, "Jump to date"). Choosing a day opens the date
 * view of the group, which asks the backend for the jump.
 */
export function JumpToDate({ groupId }: { groupId: number }) {
  const navigate = useNavigate()
  const fieldId = useId()
  const [open, setOpen] = useState(false)
  const [day, setDay] = useState('')
  // A day in the future has no articles yet.
  const [today] = useState(() => dayOf(Date.now()))
  const chosen = startOfDay(day) !== undefined

  function jump(event: SyntheticEvent) {
    event.preventDefault()
    if (!chosen) {
      return
    }
    setOpen(false)
    void navigate(`/groups/${String(groupId)}?from=${day}`)
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <Tooltip>
        <TooltipTrigger asChild>
          <PopoverTrigger asChild>
            <Button type="button" variant="ghost" size="icon-sm" aria-label="Jump to date">
              <CalendarSearch />
            </Button>
          </PopoverTrigger>
        </TooltipTrigger>
        <TooltipContent>Jump to date</TooltipContent>
      </Tooltip>
      <PopoverContent align="end">
        <form onSubmit={jump} className="flex flex-col gap-2.5">
          <Label htmlFor={fieldId}>Read the group from</Label>
          <Input
            id={fieldId}
            type="date"
            max={today}
            value={day}
            onChange={(event) => {
              setDay(event.target.value)
            }}
          />
          <Button type="submit" size="sm" disabled={!chosen}>
            Jump
          </Button>
        </form>
      </PopoverContent>
    </Popover>
  )
}
