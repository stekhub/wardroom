import { TriangleAlert } from 'lucide-react'
import { Link } from 'react-router'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { useServers } from '@/server/serverState'

/**
 * Shown on every view while the login of the server is known not to work: the server rejected
 * it, or the stored password can no longer be read (spec 002). It leads to the server settings.
 */
export function CredentialsNotice() {
  const servers = useServers()

  if (servers.data?.some((server) => server.credentialsInvalid) !== true) {
    return null
  }
  return (
    <div className="px-6 pt-6">
      <Alert variant="destructive">
        <TriangleAlert />
        <AlertTitle>Wardroom cannot log in to your server</AlertTitle>
        <AlertDescription>
          The server rejected the username and password, or the stored password can no longer be
          read. <Link to="/settings/server">Enter them again in the server settings.</Link>
        </AlertDescription>
      </Alert>
    </div>
  )
}
