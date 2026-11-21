import { useVirtualizer } from '@tanstack/react-virtual'
import { useMemo, useRef, useState } from 'react'
import { useScanMutation } from '../../api/hooks/mutations'
import { useCatalogSnapshot, useCatalogLocations, useTransferJobs } from '../../api/hooks/queries'
import { folderTree, isObjectStorage, stateLabels, type FolderNode } from '../../api/mappers'
import type { CatalogMetadataTarget, CatalogMetadataValues } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { RequestError } from '../../shared/ui/feedback'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { Badge, Button, Icon, bytes, count, date, storageName, temperatureTone } from '../../shared/ui/primitives'
import { FileInspector } from './FileInspector'
import { directMetadataFor, metadataFor, metadataIndex, useCatalogMetadata } from './catalogMetadata'
import { catalogColumns, compareCatalogValues, type CatalogColumn, type CatalogSort, type SortValue } from './catalogSort'
import { BulkMetadataDialog } from './MetadataForm'
import { folderContents, folderTotals } from './folderContents'
import { planTransfers, transferTargets } from './transferSelection'
import { SymlinkTarget } from './SymlinkTarget'
import { TransferDialog, type CatalogTransferAction } from './TransferDialog'

function Folders({ nodes, prefix, onChoose }: { nodes: FolderNode[]; prefix: string; onChoose: (prefix: string) => void }) {
  return <ul className="live-folders">{nodes.map(node => <FolderBranch key={node.prefix} node={node}
    prefix={prefix} onChoose={onChoose} />)}</ul>
}

function FolderBranch({ node, prefix, onChoose }: { node: FolderNode; prefix: string; onChoose: (prefix: string) => void }) {
  const [open, setOpen] = useState<boolean>()
  const expanded = open ?? prefix.startsWith(node.prefix)
  return <li>
    <div className={node.prefix === prefix ? 'tree-item selected' : 'tree-item'}>
      {node.children.length > 0 ? <button type="button" className="tree-toggle" aria-expanded={expanded}
        aria-label={(expanded ? 'Collapse ' : 'Expand ') + node.name} onClick={() => setOpen(!expanded)}>
        <Icon name={expanded ? 'down' : 'chevron'} size={13} />
      </button> : <span className="tree-toggle" />}
      <button type="button" className="tree-name" aria-current={node.prefix === prefix ? 'true' : undefined}
        onClick={() => { setOpen(true); onChoose(node.prefix) }}>
        <Icon name="folder" size={14} /><span>{node.name}</span>
      </button>
    </div>
    {expanded && node.children.length > 0 && <Folders nodes={node.children} prefix={prefix} onChoose={onChoose} />}
  </li>
}

function visibleFolder(nodes: FolderNode[], requested: string, parent = ''): string {
  const child = nodes.find(node => requested.startsWith(node.prefix))
  return child ? visibleFolder(child.children, requested, child.prefix) : parent
}

function temperatureOf(modified: string | null) {
  if (!modified) return undefined
  const age = (Date.now() - Date.parse(modified)) / 86_400_000
  if (!Number.isFinite(age)) return undefined
  return age < 15 ? 'HOT' : age < 30 ? 'WARM' : 'COLD'
}

function MetadataCell({ values, failed }: { values?: CatalogMetadataValues; failed: boolean }) {
  if (!values?.tags.length && !values?.properties.length) {
    return <span role="cell" className="catalog-metadata-empty">{failed ? 'Unavailable' : values ? '—' : 'Loading…'}</span>
  }

  return <div role="cell" className="catalog-metadata-cell">
    <div className="catalog-metadata-scroll" tabIndex={0} role="group" aria-label="Tags and custom properties"
      onClick={event => event.stopPropagation()}>
      {values.tags.length > 0 && <ul className="catalog-tags" aria-label="Tags">
        {values.tags.map(tag => <li key={tag}><Badge tone="blue">{tag}</Badge></li>)}
      </ul>}
      {values.properties.length > 0 && <dl className="catalog-properties" aria-label="Custom properties">
        {values.properties.map(property => <div key={property.name}><dt>{property.name}</dt><dd>{property.value}</dd></div>)}
      </dl>}
    </div>
  </div>
}

export default function StorageBrowserPage({ initialConnection, initialEntry, initialPath, navigate }: {
  initialConnection: string | null; initialEntry: string | null; initialPath: string | null; navigate: (path: string) => void
}) {
  const { hasRole } = useAuth()
  const canTransfer = hasRole('KOONJ_ADMIN') || hasRole('KOONJ_OPERATOR')
  const connections = useCatalogLocations()
  const endpoints = connections.data ?? []
  const [endpointId, setEndpointId] = useState(initialConnection)
  const [requestedFolder, setFolder] = useState(() => initialPath?.split('/').slice(0, -1).map(part => part + '/').join('') ?? '')
  const [sort, setSort] = useState<CatalogSort>()
  const [page, setPage] = useState<number>()
  const [pageSize, setPageSize] = useState(50)
  const [width, setWidth] = useState(250)
  const [fullscreen, setFullscreen] = useState(false)
  const drag = useRef<{ x: number; width: number } | null>(null)
  const [selection, setSelection] = useState<Set<string>>(() => new Set())
  const [inspectedId, setInspectedId] = useState<string | undefined>(initialEntry ?? (initialPath?.endsWith('/') ? undefined : initialPath ?? undefined))
  const [editingSelection, setEditingSelection] = useState(false)
  const [action, setAction] = useState<CatalogTransferAction>()
  const selectedEndpoint = endpoints.find(item => item.id === endpointId) ?? endpoints[0]
  const scan = useScanMutation()
  const jobs = useTransferJobs(canTransfer)
  const scanning = jobs.data?.some(job => job.operation === 'SCAN' && job.sourceConnectionId === selectedEndpoint?.id
    && (job.status === 'PENDING' || job.status === 'RUNNING'))
  const snapshot = useCatalogSnapshot(selectedEndpoint?.id, !!selectedEndpoint)
  const annotations = useCatalogMetadata(selectedEndpoint?.id)
  const index = useMemo(() => metadataIndex(annotations.data ?? []), [annotations.data])
  const folders = useMemo(() => folderTree(snapshot.data?.map(file => file.entryType === 'DIRECTORY' ? file.relativePath.replace(/\/+$/, '') + '/' : file.relativePath) ?? []), [snapshot.data])
  const folder = snapshot.data ? visibleFolder(folders, requestedFolder) : requestedFolder
  const totalsByFolder = useMemo(() => folderTotals(folders, snapshot.data ?? []), [folders, snapshot.data])
  const locationTotals = totalsByFolder.get(folder)
  const files = snapshot.data ?? []
  const contents = useMemo(() => folderContents(folders, files, folder), [folders, files, folder])
  const ordered = useMemo(() => {
    const entries = [
      ...contents.folders.map(node => ({ kind: 'folder' as const, key: node.prefix, node })),
      ...contents.files.map(file => ({ kind: 'file' as const, key: file.id, file })),
    ]
    if (!sort) return entries
    const { column, direction } = sort

    function sortValue(entry: typeof entries[number]): SortValue {
      const file = entry.kind === 'file' ? entry.file : undefined
      switch (column) {
        case 'name': return entry.kind === 'folder' ? entry.node.name : entry.file.fileName
        case 'size': return entry.kind === 'folder' ? totalsByFolder.get(entry.node.prefix)?.sizeBytes : file?.sizeBytes
        case 'state': return file ? stateLabels[file.storageState] : undefined
        case 'temperature': {
          const temperature = temperatureOf(file?.lastModifiedAt ?? null)
          return temperature ? { COLD: 0, WARM: 1, HOT: 2 }[temperature] : undefined
        }
        case 'modified': return file?.lastModifiedAt ? Date.parse(file.lastModifiedAt) : undefined
        case 'checksum': return file ? Number(!!file.checksumSha256) : undefined
        case 'metadata': {
          const target: CatalogMetadataTarget = entry.kind === 'folder' ? { scope: 'FOLDER', path: entry.node.prefix }
            : { scope: 'FILE', path: entry.file.relativePath }
          const direct = directMetadataFor(index, target)
          return [...direct.tags, ...direct.properties.map(property => property.name + ' ' + property.value)].join(' ') || undefined
        }
      }
    }

    return entries.map(entry => ({ entry, value: sortValue(entry) }))
      .sort((left, right) => compareCatalogValues(left.value, right.value, direction))
      .map(({ entry }) => entry)
  }, [contents, index, sort, totalsByFolder])
  const total = ordered.length
  const totalPages = Math.max(1, Math.ceil(total / pageSize))
  const initialIndex = ordered.findIndex(entry => entry.key === initialEntry
    || (!initialEntry && entry.kind === 'file' && entry.file.relativePath === initialPath))
  const currentPage = Math.min(page ?? Math.max(0, Math.floor(initialIndex / pageSize)), totalPages - 1)
  const visible = ordered.slice(currentPage * pageSize, (currentPage + 1) * pageSize)
  const visibleFolders = visible.flatMap(entry => entry.kind === 'folder' ? [entry.node] : [])
  const rows = visible.flatMap(entry => entry.kind === 'file' ? [entry.file] : [])
  const segments = folder.split('/').filter(Boolean)
  const selected = rows.filter(file => selection.has(file.id))
  const selectedFolders = visibleFolders.filter(node => selection.has(node.prefix))
  const selectedTargets: CatalogMetadataTarget[] = [...selected.map(file => ({ scope: 'FILE' as const, path: file.relativePath })),
    ...selectedFolders.map(node => ({ scope: 'FOLDER' as const, path: node.prefix }))]
  const inspected = files.find(file => file.id === inspectedId || (inspectedId === initialPath && file.relativePath === initialPath))
  const inspectedFolder = visibleFolders.find(node => node.prefix === inspectedId)
  const inspectedTarget: CatalogMetadataTarget | undefined = inspected ? { scope: 'FILE', path: inspected.relativePath }
    : inspectedFolder ? { scope: 'FOLDER', path: inspectedFolder.prefix } : undefined
  const plans = planTransfers(selectedEndpoint, endpoints, selected, selectedFolders, files)
  const inspectorPlans = inspectedTarget ? planTransfers(selectedEndpoint, endpoints, inspected ? [inspected] : [],
    inspectedFolder ? [inspectedFolder] : [], files) : undefined
  const transfer = action && plans[action]
  const targets = action && transfer ? transferTargets(action, transfer.source, endpoints)
    .filter(target => !transfer.destinationId || target.id === transfer.destinationId) : []
  const itemKeys = visible.map(entry => entry.key)

  function toggle(key: string, checked: boolean) {
    setSelection(previous => {
      const next = new Set(previous)
      if (checked) next.add(key)
      else next.delete(key)
      return next
    })
  }

  function transferInspected(operation: CatalogTransferAction) {
    if (!inspectedId) return
    setSelection(new Set([inspected?.id ?? inspectedId]))
    setInspectedId(undefined)
    setAction(operation)
  }

  const viewport = useRef<HTMLDivElement>(null)
  const virtual = useVirtualizer({ count: visible.length, getScrollElement: () => viewport.current, estimateSize: () => 58, overscan: 8 })

  function goToPage(index: number) {
    setPage(index)
    setSelection(new Set())
    setInspectedId(undefined)
    if (viewport.current) viewport.current.scrollTop = 0
  }

  function openFolder(prefix: string) {
    setFolder(prefix)
    goToPage(0)
  }

  function sortBy(column: CatalogColumn) {
    setSort(previous => ({ column, direction: previous?.column === column && previous.direction === 'ascending'
      ? 'descending' : 'ascending' }))
    goToPage(0)
  }

  const resize = (size: number) => setWidth(Math.min(360, Math.max(210, size)))

  const failed = connections.isError ? connections : snapshot.isError ? snapshot : undefined
  if (failed?.error && !action) return <ApiPageState loadingLabel="Loading catalog" error={failed.error}
    retry={() => void failed.refetch()} retrying={failed.isFetching} />
  if (connections.isPending || (selectedEndpoint && snapshot.isPending && !action)) {
    return <ApiPageState loadingLabel="Loading catalog" />
  }

  return <div className={fullscreen ? 'explorer-fullscreen' : undefined} onKeyDown={event => {
    if (event.key === 'Escape' && fullscreen && !event.currentTarget.querySelector('dialog[open]')) setFullscreen(false)
  }}>
    <div className="page-heading"><div><h1>Catalog</h1><p>Browse, inspect and move files across servers.</p></div>
      <Button icon={fullscreen ? 'minimize' : 'maximize'} aria-pressed={fullscreen} onClick={() => setFullscreen(!fullscreen)}>
        {fullscreen ? 'Exit full screen' : 'Full screen'}
      </Button>
    </div>
    {!selectedEndpoint
      ? <p className="panel empty">Add a storage connection to browse its catalog.</p>
      : <div className="panel explorer" style={{ gridTemplateColumns: `${width}px 7px minmax(0, 1fr)` }}>
        <aside className="catalog-tree" aria-label="Storage folders">
          <div className="tree-header"><Icon name="folder" size={17} /><h2>Storage catalog</h2></div>
          {[false, true].map(cloud => <section className="tree-section" key={String(cloud)}>
            <h2 className="tree-section-label">{cloud ? 'S3 storage' : 'On-premise storage'}</h2>
            {endpoints.filter(item => isObjectStorage(item.providerType) === cloud).map(item => <div key={item.id}>
              <div className={item.id === selectedEndpoint.id && !folder ? 'tree-item selected' : 'tree-item'}>
                <button className="tree-name" onClick={() => { setEndpointId(item.id); openFolder('') }}>
                  <Icon name={cloud ? 'cloud' : 'drive'} size={15} /><span>{item.name}</span><small>{item.providerType}</small>
                </button>
              </div>
              {item.id === selectedEndpoint.id && <>
                <Folders nodes={folders} prefix={folder}
                  onChoose={openFolder} />
              </>}
            </div>)}
          </section>)}
        </aside>
        <div className="tree-resizer" role="separator" aria-label="Resize storage catalog" aria-orientation="vertical"
          aria-valuemin={210} aria-valuemax={360} aria-valuenow={width} tabIndex={0}
          onPointerDown={event => { drag.current = { x: event.clientX, width }; event.currentTarget.setPointerCapture(event.pointerId) }}
          onPointerMove={event => { if (drag.current) resize(drag.current.width + event.clientX - drag.current.x) }}
          onLostPointerCapture={() => { drag.current = null }}
          onKeyDown={event => {
            if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
            event.preventDefault()
            resize(width + (event.key === 'ArrowLeft' ? -12 : 12))
          }} />
        <section className="catalog-main" aria-label="Catalog contents">
          <div className="catalog-heading">
            <nav className="breadcrumbs" aria-label="Catalog path">
              <button type="button" onClick={() => openFolder('')}>
                <Icon name={isObjectStorage(selectedEndpoint.providerType) ? 'cloud' : 'server'} size={16} />{selectedEndpoint.name}
              </button>
              {segments.map((segment, index) => {
                const prefix = segments.slice(0, index + 1).join('/') + '/'
                return <span key={prefix}><Icon name="chevron" size={13} />
                  <button type="button" title={segment} aria-current={prefix === folder ? 'location' : undefined} onClick={() => openFolder(prefix)}>
                    <span className="breadcrumb-segment">{segment}</span>
                  </button>
                </span>
              })}
            </nav>
            <div className="catalog-location-bar">
              <div className="catalog-title"><h2>{segments.at(-1) ?? selectedEndpoint.name}</h2><Badge>{selectedEndpoint.providerType}</Badge></div>
              {locationTotals && <dl className="catalog-location-totals" aria-label="Catalog totals including subfolders">
                <div><dt>Size</dt><dd>{bytes(locationTotals.sizeBytes)}</dd></div>
                <div><dt>Total files</dt><dd>{count(locationTotals.files)}</dd></div>
                <div><dt>Total folders</dt><dd>{count(locationTotals.folders)}</dd></div>
              </dl>}
            </div>
            {canTransfer && <div className="row-actions">
              <Button icon="scan" disabled={!selectedEndpoint.active || scan.isPending || scanning}
                onClick={() => scan.mutate(selectedEndpoint.id)}>{scanning ? 'Scanning…' : 'Scan'}</Button>
              <RequestError error={scan.error} />
            </div>}
          </div>
          <div className="catalog-viewport" ref={viewport}>
            <div className="catalog-table catalog-table-metadata" role="table" aria-label="Catalog files">
              <div className="catalog-row catalog-table-header" role="row">
                <span role="columnheader"><input type="checkbox" aria-label="Select all items on this page"
                  disabled={!canTransfer || !itemKeys.length} checked={itemKeys.length > 0 && selectedTargets.length === itemKeys.length}
                  onChange={event => setSelection(event.target.checked ? new Set(itemKeys) : new Set())} /></span>
                {(Object.keys(catalogColumns) as CatalogColumn[]).map(column => {
                  const direction = sort?.column === column ? sort.direction : undefined
                  return <span role="columnheader" key={column} aria-sort={direction}>
                    <button type="button" className="catalog-sort" onClick={() => sortBy(column)}>
                      {catalogColumns[column]} <span aria-hidden="true">{direction === 'ascending' ? '↑' : direction === 'descending' ? '↓' : '↕'}</span>
                    </button>
                  </span>
                })}
              </div>
              <div className="virtual-items" role="rowgroup" style={{ height: virtual.getTotalSize() }}>
                {virtual.getVirtualItems().map(item => {
                  const entry = visible[item.index]
                  const target: CatalogMetadataTarget = entry.kind === 'folder' ? { scope: 'FOLDER', path: entry.node.prefix }
                    : { scope: 'FILE', path: entry.file.relativePath }
                  const metadata = <MetadataCell values={annotations.data ? directMetadataFor(index, target) : undefined}
                    failed={annotations.isError} />
                  const position = { transform: `translateY(${item.start}px)`, height: item.size }
                  if (entry.kind === 'folder') {
                    const { node } = entry
                    return <div className={`catalog-row virtual-row ${selection.has(node.prefix) ? 'checked' : ''}`}
                      role="row" key={node.prefix} style={position} onClick={() => openFolder(node.prefix)}>
                      <span role="cell" onClick={event => event.stopPropagation()}><input type="checkbox" aria-label={'Select folder ' + node.name}
                        disabled={!canTransfer} checked={canTransfer && selection.has(node.prefix)}
                        onChange={event => toggle(node.prefix, event.target.checked)} /></span>
                      <span role="cell" className="catalog-name">
                        <button type="button" className="text-button" onClick={event => { event.stopPropagation(); openFolder(node.prefix) }}>
                          <Icon name="folder" size={17} /><span>{node.name}</span>
                        </button>
                        <Button className="icon-button folder-open" variant="quiet" icon="file" aria-label={'Inspect folder ' + node.name}
                          onClick={event => { event.stopPropagation(); setInspectedId(node.prefix) }} />
                      </span>
                      <span role="cell">{bytes(totalsByFolder.get(node.prefix)?.sizeBytes ?? 0)}</span>
                      {Array.from({ length: 4 }, (_, index) => <span role="cell" key={index}>—</span>)}
                      {metadata}
                    </div>
                  }
                  const { file } = entry
                  const stateLabel = stateLabels[file.storageState]
                  const temperature = temperatureOf(file.lastModifiedAt)
                  const storageClass = file.s3StorageClass
                  const archived = file.storageState === 'ARCHIVED'
                  const symlink = file.entryType === 'SYMLINK'
                  return <div className={`catalog-row virtual-row ${selection.has(file.id) ? 'checked' : ''}`} role="row" key={file.id}
                    style={position} onClick={() => setInspectedId(file.id)}>
                    <span role="cell" onClick={event => event.stopPropagation()}><input type="checkbox" aria-label={'Select ' + file.fileName} disabled={!canTransfer} checked={canTransfer && selection.has(file.id)}
                      onChange={event => toggle(file.id, event.target.checked)} /></span>
                    <div className="catalog-entry-name" role="cell">
                      <div className="catalog-name"><button className="text-button" title={file.relativePath}
                        onClick={() => setInspectedId(file.id)}><Icon name={symlink ? 'link' : 'file'} size={15} /><span>{file.fileName}</span></button>
                        {symlink && <Badge tone="blue">SYMLINK</Badge>}
                      </div>
                      {symlink && <SymlinkTarget file={file} locations={endpoints} navigate={navigate} />}
                    </div>
                    <span role="cell">{bytes(file.sizeBytes)}</span>
                    <span role="cell"><Badge dot tone={archived || file.storageState === 'ARCHIVED_COPY' ? 'green' : file.storageState === 'FAILED' ? 'red' : 'neutral'}>
                      {archived && storageClass ? stateLabel + ' · ' + (storageName[storageClass] ?? storageClass) : stateLabel}
                    </Badge></span>
                    <span role="cell">{temperature ? <Badge tone={temperatureTone[temperature]}>{temperature}</Badge> : '—'}</span>
                    <span role="cell">{file.lastModifiedAt ? date(file.lastModifiedAt) : '—'}</span>
                    <span role="cell">{file.checksumSha256 ? <Badge tone="green">Recorded</Badge> : '—'}</span>
                    {metadata}
                  </div>
                })}
              </div>
            </div>
            {total === 0 && <div className="empty catalog-empty">
              <Icon name="folder" size={26} /><strong>This folder is empty</strong>
            </div>}
          </div>
          <div className="catalog-pagination">
            <span>{count(total)} {total === 1 ? 'item' : 'items'}</span>
            <label>Per page<select value={pageSize} onChange={event => { setPageSize(Number(event.target.value)); goToPage(0) }}>
              {[50, 100, 200].map(value => <option key={value}>{value}</option>)}
            </select></label>
            <div className="catalog-page-actions">
              <Button disabled={currentPage === 0} onClick={() => goToPage(currentPage - 1)}>Previous</Button>
              <span>Page {currentPage + 1} of {totalPages}</span>
              <Button disabled={currentPage + 1 >= totalPages} onClick={() => goToPage(currentPage + 1)}>Next</Button>
            </div>
          </div>
        </section>
      </div>}
    {canTransfer && selectedTargets.length > 0 && <div className="bulk-bar" aria-label="Selection actions">
      <strong>{count(selectedTargets.length)} selected</strong>
      <Button icon="close" aria-label="Clear selection" onClick={() => setSelection(new Set())} />
      <Button variant="primary" icon="layers" onClick={() => setEditingSelection(true)}>Edit metadata</Button>
      {plans.archive && <Button icon="upload" onClick={() => setAction('archive')}>Archive to S3</Button>}
      {plans.recall && <Button icon="download" onClick={() => setAction('recall')}>Recall to Source</Button>}
    </div>}
    {selectedEndpoint && inspectedTarget && <FileInspector key={inspectedId} file={inspected} target={inspectedTarget}
      location={selectedEndpoint} connections={endpoints} navigate={navigate} metadata={annotations.data ? metadataFor(index, inspectedTarget) : undefined}
      metadataError={annotations.error} onRetry={() => void annotations.refetch()} writable={canTransfer}
      onClose={() => setInspectedId(undefined)} onOpen={inspectedFolder ? () => openFolder(inspectedFolder.prefix) : undefined}
      onArchive={canTransfer && inspectorPlans?.archive ? () => transferInspected('archive') : undefined}
      onRecall={canTransfer && inspectorPlans?.recall ? () => transferInspected('recall') : undefined} />}
    {canTransfer && selectedEndpoint && editingSelection && selectedTargets.length > 0 &&
      <BulkMetadataDialog connectionId={selectedEndpoint.id} targets={selectedTargets} onClose={() => setEditingSelection(false)}
        onSaved={() => { setEditingSelection(false); setSelection(new Set()) }} />}
    {canTransfer && action && transfer && <TransferDialog action={action} source={transfer.source}
      fileIds={transfer.fileIds} paths={transfer.paths} selectionCount={selectedTargets.length} targets={targets}
      onClose={() => setAction(undefined)} onSubmitted={() => {
        setSelection(new Set())
        setAction(undefined)
      }} />}
  </div>
}
