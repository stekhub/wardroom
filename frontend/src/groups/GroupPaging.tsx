import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react'
import { type SyntheticEvent, useId, useState } from 'react'
import { LabelledIconButton } from '@/components/LabelledIconButton'
import { Input } from '@/components/ui/input'

const COUNT = new Intl.NumberFormat('en')

interface GroupPagingProps {
  /** The current page, counted from 1. It can lie beyond the last page when the address says so. */
  page: number
  pageCount: number
  onPage: (page: number) => void
}

/**
 * The paging row of the group list (spec 022): "First", "Previous", a field with the page number,
 * "Next" and "Last". The field opens the page that is typed into it when Enter is pressed. A
 * dropdown is not used, because the list can have thousands of pages.
 */
export function GroupPaging({ page, pageCount, onPage }: GroupPagingProps) {
  const fieldId = useId()
  const errorId = useId()
  const [typed, setTyped] = useState(String(page))
  const [error, setError] = useState('')
  // After another page was opened, the field shows it.
  const [shownPage, setShownPage] = useState(page)
  if (page !== shownPage) {
    setShownPage(page)
    setTyped(String(page))
    setError('')
  }

  function openTypedPage(event: SyntheticEvent) {
    event.preventDefault()
    const typedPage = /^\d+$/.test(typed.trim()) ? Number(typed.trim()) : Number.NaN
    if (!Number.isInteger(typedPage) || typedPage < 1 || typedPage > pageCount) {
      setError(`Enter a page between 1 and ${COUNT.format(pageCount)}.`)
      return
    }
    setError('')
    onPage(typedPage)
  }

  return (
    <nav aria-label="Pages" className="mt-4 grid justify-items-center gap-2 text-sm">
      <div className="flex flex-wrap items-center justify-center gap-2">
        <LabelledIconButton
          icon={<ChevronsLeft />}
          label="First"
          disabled={page <= 1}
          onClick={() => {
            onPage(1)
          }}
        />
        <LabelledIconButton
          icon={<ChevronLeft />}
          label="Previous"
          disabled={page <= 1}
          onClick={() => {
            onPage(Math.min(page - 1, pageCount))
          }}
        />
        <form className="flex items-center gap-2" noValidate onSubmit={openTypedPage}>
          <label htmlFor={fieldId}>Page</label>
          <Input
            id={fieldId}
            inputMode="numeric"
            autoComplete="off"
            className="h-8 w-20 text-center"
            value={typed}
            aria-invalid={error !== ''}
            aria-describedby={error === '' ? undefined : errorId}
            onChange={(event) => {
              setTyped(event.target.value)
              setError('')
            }}
          />
          <span>of {COUNT.format(pageCount)}</span>
        </form>
        <LabelledIconButton
          icon={<ChevronRight />}
          label="Next"
          disabled={page >= pageCount}
          onClick={() => {
            onPage(page + 1)
          }}
        />
        <LabelledIconButton
          icon={<ChevronsRight />}
          label="Last"
          disabled={page === pageCount}
          onClick={() => {
            onPage(pageCount)
          }}
        />
      </div>
      {error !== '' && (
        <p id={errorId} role="alert" className="text-destructive">
          {error}
        </p>
      )}
    </nav>
  )
}
