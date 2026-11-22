import { useQuery } from '@tanstack/react-query'
import { ExternalLink } from 'lucide-react'
import type { ReactNode } from 'react'
import { api } from '@/api/client'
import type { components } from '@/api/schema'
import headerLogo from '@/assets/wardroom-header.svg'
import { Skeleton } from '@/components/ui/skeleton'
import { SettingsSectionFrame } from '@/settings/SettingsSectionFrame'

type About = components['schemas']['About']

/**
 * The repository of Wardroom. A modified version keeps this link as its "Based on Wardroom" and
 * adds its own (ADR 0011).
 */
export const REPOSITORY = 'https://github.com/stekhub/wardroom'

const ABOUT_KEY = ['about'] as const

async function fetchAbout(): Promise<About> {
  const { data, response } = await api.GET('/api/about')
  if (data === undefined) {
    throw new Error(`Unexpected answer ${String(response.status)} to the about request`)
  }
  return data
}

/** The settings section "About": version, license and the links to the repository (UI concept). */
export function AboutSection() {
  const about = useQuery({ queryKey: ABOUT_KEY, queryFn: fetchAbout, retry: false })

  return (
    <SettingsSectionFrame title="About">
      <div className="grid gap-2">
        <img src={headerLogo} alt="Wardroom" className="h-12 w-fit" />
        {about.data !== undefined && (
          <p className="text-sm">
            {about.data.version == null ? 'Development build' : `Version ${about.data.version}`}
          </p>
        )}
        {about.data === undefined && about.isError && (
          <p className="text-sm text-destructive">The version could not be loaded.</p>
        )}
        {about.data === undefined && !about.isError && (
          <Skeleton aria-hidden="true" className="h-5 w-32" />
        )}
      </div>
      <p className="text-sm text-muted-foreground">
        Wardroom is free software under the GNU Affero General Public License, version 3 or later:{' '}
        <RepositoryLink path="/blob/main/LICENSE">license</RepositoryLink> and{' '}
        <RepositoryLink path="/blob/main/LICENSE-ADDITIONAL-TERMS.md">
          additional terms
        </RepositoryLink>
        .
      </p>
      <ul className="grid gap-2 text-sm">
        <li>
          <RepositoryLink path="">Source code</RepositoryLink>
        </li>
        <li>
          <RepositoryLink path="/blob/main/docs/user-guide.md">User guide</RepositoryLink>
        </li>
        <li>
          <RepositoryLink path="/issues">Report a problem</RepositoryLink>
        </li>
      </ul>
    </SettingsSectionFrame>
  )
}

/** A link into the repository, opened in a new tab. */
function RepositoryLink({ path, children }: { path: string; children: ReactNode }) {
  return (
    <a
      href={REPOSITORY + path}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex items-center gap-1 text-primary underline underline-offset-2 hover:no-underline"
    >
      {children} <ExternalLink aria-hidden="true" className="size-3.5" />
      <span className="sr-only">(opens in a new tab)</span>
    </a>
  )
}
