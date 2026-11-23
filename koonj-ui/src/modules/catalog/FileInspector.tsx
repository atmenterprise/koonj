import { lazy, Suspense, useId, useState, type ReactNode } from 'react'
import { useMutation } from '@tanstack/react-query'
import { isObjectStorage, stateLabels } from '../../api/mappers'
import { useCatalogFileAttributes } from '../../api/hooks/queries'
import type { TemperaturePolicy } from '../../api/services/managementService'
import type { FileMetadataDTO, CatalogLocationDTO, CatalogMetadataTarget } from '../../api/types'
import { Dialog, LoadingRows } from '../../shared/ui/feedback'
import { useActivityNotifications } from '../../shared/notifications/ActivityNotifications'
import { errorDetail } from '../../shared/notifications/activity'
import { Badge, Button, Icon, bytes, date, temperatureTone, time } from '../../shared/ui/primitives'

import { SymlinkTarget } from './SymlinkTarget'
import { ReadOnlyMetadata } from './MetadataForm'
import MetadataEditor, { MetadataTags } from './MetadataEditor'
import { useSaveCatalogMetadata, type metadataFor } from './catalogMetadata'
import { fileAge, temperatureOf } from './fileDates'

const FileLineage = lazy(() => import('./FileLineage'))

const tabs = [
  { id: 'metadata', label: 'Metadata' },
  { id: 'lineage', label: 'Lineage' },
  { id: 'properties', label: 'Properties' },
] as const

function dateTime(value?: string | null) {
  return value ? `${date(value)} · ${time(value)}` : undefined
}

export function FileInspector({ file, target, location, connections, metadata, writable, folderDetails, favoriteControl, temperaturePolicy,
  metadataError, navigate, onRetry, onClose, onOpen, onArchive, onRecall }: {
  file?: FileMetadataDTO; target: CatalogMetadataTarget; location: CatalogLocationDTO; connections: CatalogLocationDTO[]
  temperaturePolicy?: TemperaturePolicy
  metadata?: ReturnType<typeof metadataFor>; writable: boolean; metadataError: Error | null; folderDetails?: string; favoriteControl?: ReactNode
  navigate: (path: string) => void; onRetry: () => void; onClose: () => void; onOpen?: () => void; onArchive?: () => void; onRecall?: () => void
}) {
  const { notify } = useActivityNotifications()
  const failure = file?.errorMessage
  const [tab, setTab] = useState<typeof tabs[number]['id']>('metadata')
  const [lineageOpened, setLineageOpened] = useState(false)
  const inspectorId = useId()
  const formId = inspectorId + '-form'
  const attributes = useCatalogFileAttributes(file?.entryType === 'FILE' ? file.id : undefined)
  const save = useSaveCatalogMetadata(location.id)
  const report = useMutation({
    mutationFn: async () => {
      const { downloadFolderReport } = await import('./FileLineage')
      await downloadFolderReport(location.id, target.path)
    },
    onError: error => notify({ tone: 'error', title: 'Could not download checksum report', detail: errorDetail(error) }),
  })
  const source = connections.find(connection => connection.id === file?.connectionId) ?? location
  const archive = connections.find(connection => connection.id === file?.targetConnectionId)
  const bucket = archive ?? (isObjectStorage(source.providerType) ? source : undefined)
  const temperature = temperatureOf(file?.lastModifiedAt, temperaturePolicy)
  const physical = attributes.data
  const fields: (readonly [string, ReactNode])[] = file ? [
    ['Relative path', file.relativePath],
    ['Storage location', source.name],
    ['Archive connection', archive?.name ?? file.targetConnectionId],
    ['Size', bytes(file.sizeBytes)],
    ...(file.entryType === 'SYMLINK' ? [] : [['S3 URI', bucket?.bucket && file.s3Key ? `s3://${bucket.bucket}/${file.s3Key}` : null] as const]),
    ['Storage class', file.s3Key ? file.s3StorageClass : null],
    ['Created', dateTime(physical?.createdAt)],
    ['Modified', dateTime(file.lastModifiedAt)],
    ['Last accessed', dateTime(physical?.lastAccessedAt)],
    ['File age', fileAge(physical?.createdAt, file.lastModifiedAt)],
    ['Temperature', temperature && <Badge tone={temperatureTone[temperature]}>{temperature}</Badge>],
    ['Archived', file.archivedAt ? date(file.archivedAt) : null],
    ['Integrity', file.checksumSha256 && <code>{file.checksumSha256}</code>],
  ] : [['Relative path', target.path], ['Storage location', source.name]]
  const people: [string, string | null | undefined][] = [
    ['Owner', physical?.owner],
    ['Author', physical?.author],
    ['Created by', physical?.createdBy],
    ['Last modified by', physical?.lastModifiedBy],
    ['Last accessed by', physical?.lastAccessedBy],
  ]
  const name = file?.fileName ?? target.path.replace(/\/+$/, '').split('/').at(-1)
  const details = file ? bytes(file.sizeBytes) : folderDetails
  const editing = tab !== 'lineage' && writable && !!metadata
  const retryMetadata = metadataError && !metadata && tab !== 'lineage'
  const retryAttributes = attributes.isError && tab === 'metadata'
  const exportFolder = !file && tab === 'lineage'

  function selectTab(next: typeof tabs[number]['id']) {
    if (next === 'lineage') setLineageOpened(true)
    setTab(next)
  }

  function attribution(label: string, value?: string | null) {
    if (value?.trim()) return value
    if (label === 'Owner') return '—'
    const entered = metadata?.direct.properties.find(property => property.name.toLowerCase() === label.toLowerCase())
    return entered ? <>{entered.value} <small className="inspector-attribute-source">Catalog property</small></> : '—'
  }

  return <Dialog title="Metadata inspector" className={file && tab === 'lineage' ? 'catalog-inspector catalog-inspector--lineage' : 'catalog-inspector'} onClose={onClose}>
    <div className="catalog-inspector-body">
      <div className="inspector-title"><span className="icon-tile yellow"><Icon name={file?.entryType === 'SYMLINK' ? 'link' : file ? 'file' : 'folder'} size={23} /></span>
        <div><h3>{name}</h3><p>{details ? `${details} · ${source.name}` : source.name}</p></div>
        {favoriteControl}
      </div>
      <div className="row-actions">
        <Badge>{source.providerType}</Badge>{file?.entryType === 'SYMLINK' && <Badge tone="blue">SYMLINK</Badge>}{file && <Badge dot>{stateLabels[file.storageState]}</Badge>}
      </div>
      <div className="filters inspector-tabs" role="tablist" aria-label="Inspector views">
        {tabs.map(({ id, label }, index) => <button type="button" key={id} id={inspectorId + '-tab-' + id}
          role="tab" tabIndex={tab === id ? 0 : -1} aria-selected={tab === id}
          aria-controls={inspectorId + '-panel-' + id} onClick={() => selectTab(id)} onKeyDown={event => {
            if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
            const size = tabs.length
            const offset = event.key === 'ArrowLeft' ? -1 : 1
            const next = tabs[event.key === 'Home' ? 0 : event.key === 'End' ? size - 1 : (index + offset + size) % size].id
            event.preventDefault()
            selectTab(next)
            document.getElementById(inspectorId + '-tab-' + next)?.focus()
          }}>{label}</button>)}
      </div>
      <section role="tabpanel" id={inspectorId + '-panel-metadata'} aria-labelledby={inspectorId + '-tab-metadata'} hidden={tab !== 'metadata'}>
        {metadata ? <div className="inspector-summary">
          {writable ? <label className="inspector-description">
            <span>Description</span>
            <textarea name="description" form={formId} defaultValue={metadata.direct.description ?? ''}
              rows={3} maxLength={4000} disabled={save.isPending} placeholder={`Describe this ${file ? 'file' : 'folder'}`} />
          </label> : <div className="inspector-description">
            <h4>Description</h4><p>{metadata.direct.description || 'No description added.'}</p>
          </div>}
          <div className="inspector-summary-tags">
            <div className="inspector-tags-heading">
              <h4>Tags</h4>
              {writable && <Button variant="quiet" onClick={() => {
                selectTab('properties')
                document.getElementById(inspectorId + '-tab-properties')?.focus()
              }}>Edit tags</Button>}
            </div>
            {metadata.effective.tags.length ? <MetadataTags tags={metadata.effective.tags} /> : <p>No tags added.</p>}
          </div>
        </div> : metadataError ? <p className="metadata-scope">Description and tags could not be loaded.</p>
          : <LoadingRows label="Loading description and tags" />}
        <dl className="live-metadata">
          {file?.entryType === 'SYMLINK' && <div><dt>Symlink target</dt><dd><SymlinkTarget file={file} locations={connections} navigate={navigate} /></dd></div>}
          {fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>)}
        </dl>
        {file && <section className="inspector-provenance" aria-label="Attribution">
          <h4>Attribution</h4>
          {attributes.isLoading && <p role="status">Loading attributes from storage…</p>}
          {attributes.isError && <p>Storage attributes could not be loaded.</p>}
          <dl className="live-metadata">{people.map(([label, value]) => <div key={label}>
            <dt>{label}</dt><dd>{attribution(label, value)}</dd>
          </div>)}</dl>
        </section>}
      </section>
      <section role="tabpanel" id={inspectorId + '-panel-lineage'} className="inspector-lineage-panel" aria-labelledby={inspectorId + '-tab-lineage'} hidden={tab !== 'lineage'}>
        {lineageOpened && (file ? <Suspense fallback={<LoadingRows label="Loading file lineage" />}>
          <FileLineage file={file} source={source} locations={connections} navigate={navigate} />
        </Suspense>
          : <div className="lineage-message">
            <h4>Folder checksum report</h4>
            <p>Download a CSV of recorded file movements and their SHA-256 checksums, including subfolders.
              Catalog files without movement history are marked as no record.</p>
          </div>)}
      </section>
      <section role="tabpanel" id={inspectorId + '-panel-properties'} aria-labelledby={inspectorId + '-tab-properties'} hidden={tab !== 'properties'}>
        {!file && <p className="metadata-scope">Folder tags and custom properties are inherited by files and subfolders.</p>}
        {metadata ? writable
          ? <MetadataEditor formId={formId} initial={metadata.direct} inherited={metadata.inherited} pending={save.isPending}
            onSave={async values => { await save.mutateAsync({ targets: [target], values, mode: 'REPLACE' }) }} />
          : <ReadOnlyMetadata direct={metadata.direct} inherited={metadata.inherited} />
          : metadataError ? <p className="metadata-scope">Properties could not be loaded.</p>
            : <LoadingRows label="Loading properties" />}
      </section>
    </div>
    {(exportFolder || editing || retryMetadata || retryAttributes || onOpen || onArchive || onRecall || failure) && <div className="dialog-footer">
      {retryAttributes && <Button icon="retry" onClick={() => void attributes.refetch()}>Retry file attributes</Button>}
      {retryMetadata && <Button icon="retry" onClick={onRetry}>Retry metadata</Button>}
      {file && failure && <Button icon="warning" onClick={() => notify({
        tone: 'error', title: `Transfer failed for ${file.fileName}`, detail: failure,
      })}>View failure details</Button>}
      {onOpen && <Button icon="folder" onClick={onOpen}>Open folder</Button>}
      {onArchive && <Button icon="upload" onClick={onArchive}>Archive to S3</Button>}
      {onRecall && <Button icon="download" onClick={onRecall}>Recall to Source</Button>}
      {exportFolder && <Button variant="primary" icon="download" disabled={report.isPending}
        onClick={() => report.mutate()}>{report.isPending ? 'Preparing report…' : 'Download checksum report'}</Button>}
      {editing && <Button variant="primary" type="submit" form={formId} disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save metadata'}</Button>}
    </div>}
  </Dialog>
}
