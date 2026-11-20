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

/**
 * The result of a connection test (spec 002): that it worked and whether the server offers
 * posting, or what failed with the cause the backend names.
 */
export function ConnectionTestMessage({ result }: { result: ConnectionTestResult }) {
  // The backend sends `null` for a field that does not apply.
  if (result.success || result.failure == null) {
    return (
      <Alert>
        <CircleCheck />
        <AlertTitle>Connection successful</AlertTitle>
        <AlertDescription>
          {result.postingAllowed === true
            ? 'The server allows posting.'
            : 'The server does not allow posting.'}
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
