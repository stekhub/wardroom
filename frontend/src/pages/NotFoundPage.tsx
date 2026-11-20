import { Link } from 'react-router'
import { PageHeader } from '@/pages/PageHeader'

export function NotFoundPage() {
  return (
    <div className="p-6">
      <PageHeader title="Page not found" />
      <p className="text-sm text-muted-foreground">
        This address does not belong to a view.{' '}
        <Link to="/" className="text-primary underline-offset-4 hover:underline">
          Go to the start page
        </Link>
      </p>
    </div>
  )
}
