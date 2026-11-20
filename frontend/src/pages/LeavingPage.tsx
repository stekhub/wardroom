import { useNavigate, useSearchParams } from 'react-router'
import { openableUrl } from '@/articles/textRules'
import { Button } from '@/components/ui/button'

/**
 * The page a link in an article leads to (spec 007, "Leaving page"). It shows where the link
 * goes and opens the target only after a click.
 *
 * Anyone can build a link to this page with any target, so nothing in its address is trusted: the
 * target is checked again, shown as text only and never opened by itself.
 */
export function LeavingPage() {
  const [searchParameters] = useSearchParams()
  const navigate = useNavigate()
  const address = searchParameters.get('url') ?? ''
  const target = openableUrl(address)

  const stay = () => {
    window.close()
    // A browser closes only tabs that a page has opened; in any other tab this leads to the start page.
    void navigate('/')
  }

  return (
    <main className="flex min-h-svh items-center justify-center bg-muted/40 p-6">
      <div className="w-full max-w-lg space-y-4 rounded-xl border border-border bg-background p-6 shadow-sm">
        {target === null ? (
          <>
            <h1 className="text-lg font-semibold">This link cannot be opened</h1>
            <p className="text-sm text-muted-foreground">
              Wardroom opens only addresses that start with http:// or https://.
            </p>
            {address !== '' && <Address address={address} />}
            <div className="flex justify-end">
              <Button variant="outline" onClick={stay}>
                Stay
              </Button>
            </div>
          </>
        ) : (
          <>
            <h1 className="text-lg font-semibold">You are leaving Wardroom</h1>
            <p className="text-sm text-muted-foreground">This link leads to</p>
            <p className="text-xl font-semibold break-all" data-testid="target-domain">
              {target.hostname}
            </p>
            {/* The address as the browser reads it: what is shown is what "Continue" opens. */}
            <Address address={target.href} />
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={stay}>
                Stay
              </Button>
              <Button asChild>
                {/* The target gets no referrer and no handle on a tab of Wardroom. */}
                <a href={target.href} rel="noopener noreferrer">
                  Continue
                </a>
              </Button>
            </div>
          </>
        )}
      </div>
    </main>
  )
}

/** The complete address as plain text. It can be long, so it breaks anywhere. */
function Address({ address }: { address: string }) {
  return (
    <p
      className="rounded-md bg-muted p-3 font-mono text-xs break-all text-muted-foreground"
      data-testid="target-address"
    >
      {address}
    </p>
  )
}
