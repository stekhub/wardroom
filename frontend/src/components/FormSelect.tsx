import { type ComponentProps, useId } from 'react'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'

type FormSelectProps = ComponentProps<typeof NativeSelect> & {
  label: string
  /** The message of a failed rule; the field is marked as invalid while it is set. */
  error?: string | undefined
}

/** A labelled select with its error message, connected for screen readers. */
export function FormSelect({ label, error, children, ...selectProps }: FormSelectProps) {
  const id = useId()
  const errorId = `${id}-error`

  return (
    <div className="grid gap-2">
      <Label htmlFor={id}>{label}</Label>
      <NativeSelect
        id={id}
        className="w-full"
        aria-invalid={error !== undefined}
        aria-describedby={error === undefined ? undefined : errorId}
        {...selectProps}
      >
        {children}
      </NativeSelect>
      {error !== undefined && (
        <p id={errorId} className="text-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}
