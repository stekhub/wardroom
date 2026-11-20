import { User } from 'lucide-react'
import { authorColors } from '@/articles/authorColors'
import { cn } from '@/lib/utils'

const SIZES = {
  /** Thread cards: 24 px. */
  small: { circle: 'size-6', symbol: 'size-3.5' },
  /** Next to each article on the thread page: 32 px. */
  medium: { circle: 'size-8', symbol: 'size-[1.125rem]' },
} as const

interface AuthorIconProps {
  /** The author key the backend computed, or `null` for a stub, whose author is not known. */
  authorKey: string | null
  size?: keyof typeof SIZES
  className?: string
}

/**
 * The round icon of an author: the same person looks the same in every group and thread. A stub
 * gets a gray circle. The icon is decorative; the name next to it carries the meaning.
 */
export function AuthorIcon({ authorKey, size = 'medium', className }: AuthorIconProps) {
  const colors = authorKey === null ? null : authorColors(authorKey)
  return (
    <span
      aria-hidden="true"
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-full',
        colors === null && 'bg-muted-foreground/40 text-background',
        SIZES[size].circle,
        className,
      )}
      style={colors === null ? undefined : { backgroundColor: colors.circle, color: colors.symbol }}
    >
      <User className={SIZES[size].symbol} strokeWidth={2.25} />
    </span>
  )
}
