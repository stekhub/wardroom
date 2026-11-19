import { PageHeader } from '@/pages/PageHeader'

/** The discover view. Its content, the searchable group list, is built with newsgroup discovery (spec 003). */
export function DiscoverPage() {
  return (
    <div className="p-6">
      <PageHeader title="Discover groups" />
      <p className="text-sm text-muted-foreground">
        The groups of your server will be listed here.
      </p>
    </div>
  )
}
