/** The heading of a view in the main area. */
export function PageHeader({ title }: { title: string }) {
  return <h1 className="mb-6 text-xl font-semibold tracking-tight">{title}</h1>
}
