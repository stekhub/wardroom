const BYTES_PER_MEGABYTE = 1024 * 1024
const BYTES_PER_GIGABYTE = 1024 * BYTES_PER_MEGABYTE

/** The size of the cache as the section shows it: "1.2 GB", "340 MB". */
export function formatCacheSize(bytes: number): string {
  if (bytes >= BYTES_PER_GIGABYTE) {
    return `${(bytes / BYTES_PER_GIGABYTE).toFixed(1)} GB`
  }
  if (bytes >= BYTES_PER_MEGABYTE) {
    return `${String(Math.round(bytes / BYTES_PER_MEGABYTE))} MB`
  }
  return 'less than 1 MB'
}
