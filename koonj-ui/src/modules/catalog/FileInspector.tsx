import { useState } from 'react'
import { isObjectStorage, stateLabels } from '../../api/mappers'
import type { FileMetadataDTO, CatalogLocationDTO, CatalogMetadataTarget } from '../../api/types'
import { Dialog, LoadingRows, RequestError } from '../../shared/ui/feedback'
import { Badge, Button, Icon, bytes, date } from '../../shared/ui/primitives'

import { MetadataForm } from './MetadataForm'
import type { metadataFor } from './catalogMetadata'

export function FileInspector({ file, target, location, connections, metadata, writable,
  metadataError, onRetry, onClose, onOpen, onArchive, onRecall }: {
  file?: FileMetadataDTO; target: CatalogMetadataTarget; location: CatalogLocationDTO; connections: CatalogLocationDTO[]
  metadata?: ReturnType<typeof metadataFor>; writable: boolean; metadataError: Error | null
  onRetry: () => void; onClose: () => void; onOpen?: () => void; onArchive?: () => void; onRecall?: () => void
}) {
  const [tab, setTab] = useState<'metadata' | 'lineage'>('metadata')
  const source = connections.find(connection => connection.id === file?.sourceConnectionId) ?? location
  const archive = connections.find(connection => connection.id === file?.targetConnectionId)
  const bucket = archive ?? (isObjectStorage(source.providerType) ? source : undefined)
  const fields = file ? [
    ['Relative path', file.relativePath],
    ['Source connection', source?.name ?? file.sourceConnectionId],
    ['Archive connection', archive?.name ?? file.targetConnectionId],
    ['Size', bytes(file.sizeBytes)],
    ['S3 URI', bucket?.bucket && file.s3Key ? `s3://${bucket.bucket}/${file.s3Key}` : null],
    ['Storage class', file.s3Key ? file.s3StorageClass : null],
    ['Modified', file.lastModifiedAt ? date(file.lastModifiedAt) : null],
    ['Archived', file.archivedAt ? date(file.archivedAt) : null],
    ['SHA-256', file.checksumSha256],
  ] : []
  const name = file?.fileName ?? target.path.replace(/\/+$/, '').split('/').at(-1)
  return <Dialog title="Metadata inspector" drawer onClose={onClose}>
    <div className="drawer-body">
      <div className="inspector-title"><span className="icon-tile yellow"><Icon name={file ? 'file' : 'folder'} size={23} /></span>
        <div><h3>{name}</h3><p>{file ? bytes(file.sizeBytes) + ' · ' : ''}{source.name}</p></div>
      </div>
      <div className="row-actions">
        <Badge>{source.providerType}</Badge>{file && <Badge dot>{stateLabels[file.storageState]}</Badge>}
      </div>
      {file && <div className="filters inspector-tabs" role="group" aria-label="Inspector views">
        <button type="button" aria-pressed={tab === 'metadata'} onClick={() => setTab('metadata')}>Metadata</button>
        <button type="button" aria-pressed={tab === 'lineage'} onClick={() => setTab('lineage')}>Lineage</button>
      </div>}
      {tab === 'metadata'
        ? <>
          {!file && <p className="metadata-scope">Folder metadata is inherited by files and subfolders.</p>}
          <RequestError error={metadataError} retry={onRetry} />
          {metadata ? <MetadataForm connectionId={location.id} targets={[target]} direct={metadata.direct}
            inherited={metadata.inherited} writable={writable} /> : !metadataError && <LoadingRows label="Loading custom metadata" />}
          {fields.length > 0 && <dl className="live-metadata">{fields.map(([label, value]) =>
            <div key={label}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>)}</dl>}
        </>
        : file?.archivedAt ? <ol className="file-lineage"><li>
          <div className="lineage-title"><span className="icon-tile yellow"><Icon name="upload" size={16} /></span>
            <div><strong>Archived to S3</strong><small>{date(file.archivedAt)}</small></div>
          </div>
          <dl className="lineage-paths"><div><dt>From</dt><dd>{source?.name ?? file.relativePath}</dd></div>
            <div><dt>To</dt><dd>{archive?.name ?? file.s3Key ?? '—'}</dd></div></dl>
        </li></ol> : <p className="empty compact">No archive timestamp is recorded for this file.</p>}
      {file?.errorMessage && <p className="error-text" role="alert">{file.errorMessage}</p>}
    </div>
    {(onOpen || onArchive || onRecall) && <div className="dialog-footer">
      {onOpen && <Button icon="folder" onClick={onOpen}>Open folder</Button>}
      {onArchive && <Button icon="upload" onClick={onArchive}>{file ? 'Archive' : 'Archive folder'}</Button>}
      {onRecall && <Button icon="download" onClick={onRecall}>{file ? 'Recall' : 'Recall folder'}</Button>}
    </div>}
  </Dialog>
}
