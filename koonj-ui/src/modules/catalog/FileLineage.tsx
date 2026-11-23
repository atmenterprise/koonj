import { useId, useRef, useState, type ReactNode } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { queryKeys } from '../../api/hooks/queries'
import { catalogService } from '../../api/services/catalogService'
import type { CatalogLocationDTO, FileMetadataDTO, FileMovementDTO } from '../../api/types'
import { LoadingRows } from '../../shared/ui/feedback'
import { Badge, Button, Icon, bytes, count, date, time } from '../../shared/ui/primitives'
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

function Movement({ movement, expanded, onToggle }: { movement: FileMovementDTO; expanded: boolean; onToggle: () => void }) {
  const detailsId = useId()
  const verified = movement.outcome === 'VERIFIED'
  const recall = movement.operation === 'RECALL'
  const mismatch = movement.sourceChecksum && movement.targetChecksum && movement.sourceChecksum !== movement.targetChecksum
  const replacedLink = recall && movement.symlinkReference
  const createdLink = verified && movement.operation === 'ARCHIVE_PURGE' && movement.symlinkReference
  return <li className="lineage-record">
    <button type="button" className="lineage-title" aria-expanded={expanded} aria-controls={detailsId} onClick={onToggle}>
      <span className={`icon-tile ${recall ? 'blue' : 'yellow'}`}><Icon name={recall ? 'download' : 'upload'} size={16} /></span>
      <span className="lineage-summary"><strong>{labels[movement.operation]}</strong>
        <small><time dateTime={movement.recordedAt}>{date(movement.recordedAt)} · {time(movement.recordedAt)}</time> · {bytes(movement.sizeBytes)}</small>
        <small className="lineage-route"><span>{movement.sourceLocation}</span><Icon name="arrow" size={12} /><span>{movement.targetLocation}</span></small>
      </span>
      <span className="lineage-outcome"><Badge tone={verified ? 'green' : 'red'} dot>{verified ? 'Verified' : mismatch ? 'Checksum mismatch' : 'Failed'}</Badge></span>
      <span className="lineage-chevron"><Icon name="down" size={16} /></span>
    </button>
    <div className="lineage-record-details" id={detailsId} hidden={!expanded}>
      {expanded && <>
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
        {verified && <p className="lineage-verification">Source and destination SHA-256 checksums match.</p>}
        {movement.archiveRemoved && <p>S3 copy removed after verification.</p>}
        {movement.error && <p className="lineage-error">{movement.error}</p>}
      </>}
    </div>
  </li>
}

export default function FileLineage({ file, source, locations, navigate }: {
  file: FileMetadataDTO; source: CatalogLocationDTO; locations: CatalogLocationDTO[]; navigate: (path: string) => void
}) {
  const [page, setPage] = useState(0)
  const [pageInput, setPageInput] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<number>()
  const records = useRef<HTMLDivElement>(null)
  const headingId = useId()
  const history = useQuery({ queryKey: [...queryKeys.files, 'lineage', source.id, file.relativePath, page],
    meta: { errorTitle: 'Could not load file lineage' },
    placeholderData: keepPreviousData,
    queryFn: ({ signal }) => catalogService.getLineage(source.id, file.relativePath, page, signal) })

  function goToPage(next: number) {
    if (next === page) return
    setPage(next)
    setExpanded(undefined)
    setPageInput(null)
    records.current?.scrollTo({ top: 0 })
  }

  const { content = [], totalPages = 0, totalElements = 0, size = 0 } = history.data ?? {}
  const loadingPage = history.isPending || history.isPlaceholderData
  const range = totalElements && content.length ? `${count(page * size + 1)}–${count(page * size + content.length)} of ${count(totalElements)}` : ''
  return <div className="lineage-history">
    <div className="lineage-toolbar">
      <div><h4 id={headingId}>Transfer history</h4>
        <p aria-live="polite">{loadingPage ? `Loading page ${count(page + 1)}…`
          : history.isError ? 'History unavailable' : range ? `${range} records · Newest first` : page > 0 ? 'No records on this page' : 'No records'}</p>
      </div>
      {(totalPages > 1 || page > 0) && <nav className="lineage-pagination" aria-label="Transfer history pages">
        <Button disabled={page === 0} onClick={() => goToPage(page - 1)}>Previous</Button>
        <form className="lineage-page-jump" onSubmit={event => {
          event.preventDefault()
          const requested = Number(pageInput ?? page + 1)
          if (Number.isInteger(requested) && requested >= 1 && requested <= totalPages) {
            setPageInput(null)
            goToPage(requested - 1)
          }
        }}>
          <label htmlFor={headingId + '-page'}>Page</label>
          <input id={headingId + '-page'} type="number" required min={1} max={totalPages || undefined}
            value={pageInput ?? page + 1} onChange={event => setPageInput(event.target.value)} />
          {totalPages > 0 && <span>of {count(totalPages)}</span>}
          <Button type="submit" disabled={totalPages < 2}>Go</Button>
        </form>
        <Button disabled={page + 1 >= totalPages} onClick={() => goToPage(page + 1)}>Next</Button>
      </nav>}
    </div>
    <div className="lineage-records" role="region" tabIndex={0} aria-labelledby={headingId}
      aria-busy={loadingPage} ref={records}>
      {file.entryType === 'SYMLINK' && file.symlinkTarget && <details className="lineage-current-link">
        <summary>Current symlink</summary>
        <div className="lineage-flow">
          <LineageNode label="Symlink location" location={source.name} path={file.relativePath} />
          <Icon name="arrow" size={16} />
          <LineageNode label="Symlink target" path={<SymlinkTarget file={file} locations={locations} navigate={navigate} />} />
        </div>
      </details>}
      {loadingPage ? <LoadingRows label="Loading file lineage" /> : history.isError ? <div className="lineage-message">
        <p>File lineage could not be loaded.</p><Button icon="retry" onClick={() => void history.refetch()}>Retry</Button>
      </div> : content.length > 0 ? <ol className="file-lineage lineage-record-list">{content.map(movement =>
        <Movement key={movement.id} movement={movement} expanded={expanded === movement.id}
          onToggle={() => setExpanded(current => current === movement.id ? undefined : movement.id)} />)}</ol>
        : page > 0 ? <p className="lineage-message">No records on this page.</p>
        : <p className="lineage-message">No transfer verification records are available for this file.
          {file.archivedAt && <> The catalog lists an archive date of {date(file.archivedAt)}.</>}
        </p>}
    </div>
  </div>
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
