import type { CatalogLocationDTO, FileMetadataDTO } from '../../api/types'
import { linkedBucket } from './transferSelection'

export function SymlinkTarget({ file, locations, navigate }: {
  file: FileMetadataDTO; locations: CatalogLocationDTO[]; navigate: (path: string) => void
}) {
  const reference = file.symlinkTarget
  if (!reference) return null
  const bucket = linkedBucket(file, locations)
  if (!bucket) return <span className="symlink-target" title={reference}>{reference}</span>
  const path = reference.slice(('s3://' + bucket.bucket + '/').length)
  const href = '/explorer?' + new URLSearchParams({ endpoint: bucket.id, path })
  return <a className="symlink-target" title={reference} href={href}
    onClick={event => {
      event.stopPropagation()
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) return
      event.preventDefault()
      navigate(href)
    }}>{reference}</a>
}
