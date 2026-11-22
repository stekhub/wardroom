import { CircleCheck, TriangleAlert } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import type { ConnectionTestResult } from '@/server/serverState'

type FailureType = NonNullable<ConnectionTestResult['failure']>['type']

const FAILURE_TITLES: Record<FailureType, string> = {
  UNREACHABLE: 'The server cannot be reached',
  TIMEOUT: 'The server did not answer in time',
  TLS_CERTIFICATE: 'The certificate of the server is not trusted',
  TLS_HANDSHAKE: 'A secure connection could not be set up',
  AUTH_FAILED: 'The server rejected the login',
  SERVER_REFUSED: 'The server refused the connection',
  PROTOCOL: 'The server gave an unexpected answer',
}

/** Said wherever a server does not take posts (spec 009). */
const SEPARATE_SERVER_HINT =
  'Some providers take posts only on a separate server. If yours does, set it in the server settings with "Use a separate server for posting".'

/**
 * The result of a connection test (spec 002): that it worked and whether the server offers
 * posting, or what failed with the cause the backend names. With a separate posting server, the
 * posting line is about that server; its failure does not make the test fail, because reading
 * works without it.
 */
export function ConnectionTestMessage({ result }: { result: ConnectionTestResult }) {
  // The backend sends `null` for a field that does not apply.
  if (result.success || result.failure == null) {
    return (
      <Alert>
        <CircleCheck />
        <AlertTitle>Connection successful</AlertTitle>
        <AlertDescription>
          {/* What the server says now; it may still refuse a post later (spec 009). */}
          {result.postingServerFailure != null ? (
            <>
              <p>Posting: the posting server cannot be used</p>
              <p className="break-words">
                {FAILURE_TITLES[result.postingServerFailure.type]}:{' '}
                {result.postingServerFailure.message}
              </p>
              <p>Reading works. Check the settings of the posting server.</p>
            </>
          ) : result.postingAllowed === true ? (
            <p>Posting: available</p>
          ) : (
            <>
              <p>Posting: not offered by this server</p>
              <p>
                Your provider does not allow posting with this account. Some providers require
                posting to be activated in the account settings, or do not include it in trial or
                block accounts. Check your provider&apos;s account page or support.
              </p>
              <p>{SEPARATE_SERVER_HINT}</p>
            </>
          )}
        </AlertDescription>
      </Alert>
    )
  }
  return (
    <Alert variant="destructive">
      <TriangleAlert />
      <AlertTitle>{FAILURE_TITLES[result.failure.type]}</AlertTitle>
      <AlertDescription>{result.failure.message}</AlertDescription>
    </Alert>
  )
}
