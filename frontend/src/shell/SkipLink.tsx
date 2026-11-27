import { MAIN_CONTENT_ID } from '@/shell/pageFocus'

/**
 * The link "Skip to content", the first element of every page (spec 015). It is shown only while
 * it has the focus, and moves the focus to the main area, past the sidebar.
 */
export function SkipLink() {
  return (
    <a
      href={`#${MAIN_CONTENT_ID}`}
      className="sr-only rounded-md bg-background px-3 py-2 text-sm font-medium text-foreground shadow-md ring-2 ring-ring outline-none focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50"
      onClick={(event) => {
        // The address stays as it is: the pages keep their state in it.
        event.preventDefault()
        document.getElementById(MAIN_CONTENT_ID)?.focus()
      }}
    >
      Skip to content
    </a>
  )
}
