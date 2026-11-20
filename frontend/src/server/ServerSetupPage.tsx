import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { EntryPage } from '@/auth/EntryPage'
import { WizardSteps } from '@/auth/WizardSteps'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ServerForm } from '@/server/ServerForm'
import { useSetServers } from '@/server/serverState'

/**
 * Step 2 of the first-run wizard: the server (spec 002, UI concept). It cannot be skipped,
 * because Wardroom is unusable without a server. Afterwards the discover view is shown.
 */
export function ServerSetupPage() {
  const setServers = useSetServers()
  const navigate = useNavigate()

  return (
    <EntryPage wide>
      <div className="grid gap-6">
        <WizardSteps current="Server" />
        <Card>
          <CardHeader>
            <CardTitle>
              <h1>Add your server</h1>
            </CardTitle>
            <CardDescription>
              Wardroom reads newsgroups from one NNTP server. Your Usenet provider tells you the
              host, the port and your login.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <ServerForm
              saveLabel="Save and continue"
              onSaved={(saved) => {
                if (saved.connectionTest.success) {
                  toast.success('Server added')
                } else {
                  // The server is saved anyway, so one that is down for the moment can be set up.
                  toast.warning(
                    'Server added, but the connection test failed. Check the server settings.',
                  )
                }
                setServers([saved.server])
                void navigate('/discover', { replace: true })
              }}
            />
          </CardContent>
        </Card>
      </div>
    </EntryPage>
  )
}
