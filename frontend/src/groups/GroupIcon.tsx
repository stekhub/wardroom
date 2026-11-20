import { groupIcon } from '@/groups/groupIconRules'
import { GROUP_ICON_SYMBOLS } from '@/groups/groupIconSymbols'
import { cn } from '@/lib/utils'

/**
 * The circle colors in the order of the Group Icons document. Each reaches a contrast of at
 * least 3:1 against the white symbol. The class names are written out so that Tailwind finds them.
 */
const CIRCLE_COLORS = [
  'bg-red-600',
  'bg-orange-600',
  'bg-amber-600',
  'bg-green-600',
  'bg-teal-600',
  'bg-cyan-600',
  'bg-sky-600',
  'bg-blue-600',
  'bg-indigo-600',
  'bg-violet-600',
  'bg-fuchsia-600',
  'bg-rose-600',
] as const

const SIZES = {
  /** Sidebar entries: 20 px. */
  small: { circle: 'size-5', symbol: 'size-3' },
  /** Discover rows: 40 px, the height of the two lines of text next to it. */
  medium: { circle: 'size-10', symbol: 'size-6' },
  /** The group header: 64 px. */
  large: { circle: 'size-16', symbol: 'size-9' },
} as const

interface GroupIconProps {
  name: string
  description?: string | null
  size?: keyof typeof SIZES
  className?: string
}

/**
 * The round icon of a newsgroup: a white symbol on a colored circle. It is decorative; the group
 * name next to it carries the meaning.
 */
export function GroupIcon({ name, description, size = 'small', className }: GroupIconProps) {
  const { symbol, colorIndex } = groupIcon(name, description)
  const Symbol = GROUP_ICON_SYMBOLS[symbol]
  return (
    <span
      aria-hidden="true"
      data-symbol={symbol}
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-full text-white',
        CIRCLE_COLORS[colorIndex],
        SIZES[size].circle,
        className,
      )}
    >
      <Symbol className={SIZES[size].symbol} strokeWidth={2.25} />
    </span>
  )
}
