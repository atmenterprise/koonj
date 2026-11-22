import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '../../api/hooks/queries'
import { catalogService } from '../../api/services/catalogService'
import type { CatalogLocationDTO, FileMetadataDTO, FileMovementDTO } from '../../api/types'
import { LoadingRows } from '../../shared/ui/feedback'
import { Badge, Button, Icon, bytes, date, time } from '../../shared/ui/primitives'
import { SymlinkTarget } from './SymlinkTarget'

const labels: Record<FileMovementDTO['operation'], string> = {
  ARCHIVE_COPY: 'Copied to S3',
  ARCHIVE_PURGE: 'Archived to S3 with symlink',
  RECALL: 'Recalled from S3',
}

function LineageNode({ label, location, path }: { label: string; location?: string; path: ReactNode }) {
  return <div className="lineage-node">
    <small>{label}</small>
    {location && <strong>{location}</strong>}
    <span>{path}</span>
  </div>
}

function Movement({ movement }: { movement: FileMovementDTO }) {
  const verified = movement.outcome === 'VERIFIED'
  const recall = movement.operation === 'RECALL'
  const mismatch = movement.sourceChecksum && movement.targetChecksum && movement.sourceChecksum !== movement.targetChecksum
  const replacedLink = recall && movement.symlinkReference
  const createdLink = verified && movement.operation === 'ARCHIVE_PURGE' && movement.symlinkReference
  return <li>
    <div className="lineage-title">
      <span className={`icon-tile ${recall ? 'blue' : 'yellow'}`}><Icon name={recall ? 'download' : 'upload'} size={16} /></span>
      <div><strong>{verified ? labels[movement.operation] : `Failed · ${recall ? 'Recall' : 'Archive'}`}</strong>
        <small><time dateTime={movement.recordedAt}>{date(movement.recordedAt)} · {time(movement.recordedAt)}</time> · {bytes(movement.sizeBytes)}</small>
      </div>
      <Badge tone={verified ? 'green' : 'red'} dot>{verified ? 'Checksums match' : mismatch ? 'Checksum mismatch' : 'Transfer failed'}</Badge>
    </div>
    <div className="lineage-flow">
      <LineageNode label={createdLink ? 'Source file replaced by symlink' : recall ? 'Archive source' : 'Source file'}
        location={movement.sourceLocation} path={movement.sourcePath} />
      <Icon name="arrow" size={16} />
      {replacedLink && <>
        <LineageNode label={verified ? 'Previous symlink' : 'Existing symlink'} location={movement.targetLocation} path={movement.targetPath} />
        <Icon name="arrow" size={16} />
      </>}
      <LineageNode label={verified ? recall ? 'Restored file' : 'Archive destination' : 'Intended destination'}
        location={movement.targetLocation} path={movement.targetPath} />
    </div>
    <dl className="lineage-checksums">
      <div><dt>Source SHA-256</dt><dd>{movement.sourceChecksum ? <code>{movement.sourceChecksum}</code> : 'Not recorded'}</dd></div>
      <div><dt>Destination SHA-256</dt><dd>{movement.targetChecksum ? <code>{movement.targetChecksum}</code> : 'Not recorded'}</dd></div>
      {movement.symlinkReference && <div><dt>Symlink reference</dt><dd>{movement.symlinkReference}</dd></div>}
      <div><dt>Job ID</dt><dd>{movement.jobId}</dd></div>
    </dl>
    {movement.archiveRemoved && <p>S3 copy removed after verification.</p>}
    {movement.error && <p className="lineage-error">{movement.error}</p>}
  </li>
}

export default function FileLineage({ file, source, locations, navigate }: {
  file: FileMetadataDTO; source: CatalogLocationDTO; locations: CatalogLocationDTO[]; navigate: (path: string) => void
}) {
  const [page, setPage] = useState(0)
  const history = useQuery({ queryKey: [...queryKeys.files, 'lineage', source.id, file.relativePath, page],
    meta: { errorTitle: 'Could not load file lineage' },
    queryFn: ({ signal }) => catalogService.getLineage(source.id, file.relativePath, page, signal) })
  if (history.isPending) return <LoadingRows label="Loading file lineage" />
  if (history.isError) return <div className="lineage-message">
    <p>File lineage could not be loaded.</p><Button icon="retry" onClick={() => void history.refetch()}>Retry</Button>
  </div>
  const { content, totalPages } = history.data
  return <>
    {file.entryType === 'SYMLINK' && file.symlinkTarget && <div className="lineage-message">
      <h4>Current symlink</h4>
      <div className="lineage-flow">
        <LineageNode label="Symlink location" location={source.name} path={file.relativePath} />
        <Icon name="arrow" size={16} />
        <LineageNode label="Symlink target" path={<SymlinkTarget file={file} locations={locations} navigate={navigate} />} />
      </div>
    </div>}
    {content.length > 0 ? <ol className="file-lineage">{content.map(movement => <Movement key={movement.id} movement={movement} />)}</ol>
      : <p className="lineage-message">No transfer verification records are available for this file.
        {file.archivedAt && <> The catalog lists an archive date of {date(file.archivedAt)}.</>}
      </p>}
    {totalPages > 1 && <div className="lineage-pagination">
      <Button disabled={!page} onClick={() => setPage(page - 1)}>Previous</Button>
      <span aria-live="polite">Page {page + 1} of {totalPages}</span>
      <Button disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next</Button>
    </div>}
  </>
}

export async function downloadFolderReport(connectionId: string, path: string) {
  const blob = await catalogService.getLineageReport(connectionId, path)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'folder-checksum-report.csv'
  document.body.append(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 30_000)
}
