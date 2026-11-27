import type { ComponentProps, ReactNode } from 'react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { useIsMobile } from '@/hooks/use-mobile'

type LabelledIconButtonProps = Omit<ComponentProps<typeof Button>, 'children' | 'asChild'> & {
  icon: ReactNode
  label: string
  /** With an address, the button is a link to it. */
  to?: string
}

/**
 * A button with an icon and a label that shows only its icon below 768 pixels (spec 015), so a row
 * of buttons fits a phone. The label stays in the page for screen readers, and a tooltip shows it.
 */
export function LabelledIconButton({
  icon,
  label,
  to,
  variant = 'outline',
  size = 'sm',
  className,
  ...buttonProps
}: LabelledIconButtonProps) {
  const isMobile = useIsMobile()
  const content = (
    <>
      {icon}
      <span className="max-md:sr-only">{label}</span>
    </>
  )
  // Narrow, the button is a square like an icon button.
  const classes = `max-md:w-7 max-md:px-0 ${className ?? ''}`
  const button =
    to === undefined ? (
      <Button type="button" variant={variant} size={size} className={classes} {...buttonProps}>
        {content}
      </Button>
    ) : (
      <Button asChild variant={variant} size={size} className={classes} {...buttonProps}>
        <Link to={to}>{content}</Link>
      </Button>
    )
  if (!isMobile) {
    return button
  }
  return (
    <Tooltip>
      <TooltipTrigger asChild>{button}</TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  )
}
