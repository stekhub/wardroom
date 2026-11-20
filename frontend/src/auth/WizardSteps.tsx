import { cn } from '@/lib/utils'

const WIZARD_STEPS = ['Account', 'Server'] as const

/** The step indicator of the first-run wizard: 1 Account · 2 Server (UI concept). */
export function WizardSteps({ current }: { current: (typeof WIZARD_STEPS)[number] }) {
  return (
    <ol aria-label="Setup steps" className="flex items-center justify-center gap-3 text-sm">
      {WIZARD_STEPS.map((step, index) => {
        const isCurrent = step === current
        return (
          <li
            key={step}
            aria-current={isCurrent ? 'step' : undefined}
            className={cn(
              'flex items-center gap-2',
              isCurrent ? 'font-medium text-foreground' : 'text-muted-foreground',
            )}
          >
            <span
              className={cn(
                'flex size-6 items-center justify-center rounded-full text-xs',
                isCurrent ? 'bg-primary text-primary-foreground' : 'bg-muted',
              )}
            >
              {index + 1}
            </span>
            {step}
          </li>
        )
      })}
    </ol>
  )
}
