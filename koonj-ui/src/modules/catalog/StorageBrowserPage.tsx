import { useVirtualizer } from '@tanstack/react-virtual'
import { useMemo, useRef, useState } from 'react'
import { useCatalogSnapshot, useCatalogLocations } from '../../api/hooks/queries'
import { folderTree, isObjectStorage, stateLabels, type FolderNode } from '../../api/mappers'
import type { CatalogMetadataTarget } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { LoadingRows } from '../../shared/ui/feedback'
import { Badge, Button, Icon, bytes, count, date, storageName, temperatureTone } from '../../shared/ui/primitives'
import { FileInspector } from './FileInspector'
import { metadataFor, metadataIndex, useCatalogMetadata } from './catalogMetadata'
import { BulkMetadataDialog } from './MetadataForm'
import { folderContents } from './folderContents'
import { planTransfers, transferTargets } from './transferSelection'
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

function temperatureOf(modified: string | null) {
  if (!modified) return undefined
  const age = (Date.now() - Date.parse(modified)) / 86_400_000
  if (!Number.isFinite(age)) return undefined
  return age < 15 ? 'HOT' : age < 30 ? 'WARM' : 'COLD'
}

export default function StorageBrowserPage({ initialConnection, initialEntry, initialPath }: {
  initialConnection: string | null; initialEntry: string | null; initialPath: string | null
}) {
  const { hasRole } = useAuth()
  const canTransfer = hasRole('KOONJ_ADMIN') || hasRole('KOONJ_OPERATOR')
  const connections = useCatalogLocations()
  const endpoints = connections.data ?? []
  const [endpointId, setEndpointId] = useState(initialConnection)
  const [folder, setFolder] = useState(() => initialPath?.split('/').slice(0, -1).map(part => part + '/').join('') ?? '')
  const [search, setSearch] = useState(() => initialPath?.split('/').at(-1) ?? '')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(50)
  const [width, setWidth] = useState(250)
  const [fullscreen, setFullscreen] = useState(false)
  const drag = useRef<{ x: number; width: number } | null>(null)
  const [selection, setSelection] = useState<Set<string>>(() => new Set())
  const [inspectedId, setInspectedId] = useState<string | undefined>(initialEntry ?? undefined)
  const [editingSelection, setEditingSelection] = useState(false)
  const [action, setAction] = useState<CatalogTransferAction>()
  const selectedEndpoint = endpoints.find(item => item.id === endpointId) ?? endpoints[0]
  const term = search.trim().toLowerCase().replace(/^\/+/, '')
  const snapshot = useCatalogSnapshot(selectedEndpoint?.id, !!selectedEndpoint)
  const annotations = useCatalogMetadata(selectedEndpoint?.id)
  const index = useMemo(() => metadataIndex(annotations.data ?? []), [annotations.data])
  const folders = useMemo(() => folderTree(snapshot.data?.map(file => file.relativePath) ?? []), [snapshot.data])
  const files = snapshot.data ?? []
  const contents = useMemo(() => folderContents(folders, files, folder, target => {
    if (!term) return true
    const metadata = metadataFor(index, target).effective
    return [target.path, ...metadata.tags, ...metadata.properties.flatMap(item => [item.name, item.value])]
      .some(value => value.toLowerCase().includes(term))
  }), [folders, files, folder, index, term])
  const total = contents.folders.length + contents.files.length
  const totalPages = Math.max(1, Math.ceil(total / pageSize))
  const currentPage = Math.min(page, totalPages - 1)
  const start = currentPage * pageSize
  const stop = start + pageSize
  const visibleFolders = contents.folders.slice(start, stop)
  const rows = contents.files.slice(Math.max(0, start - contents.folders.length), Math.max(0, stop - contents.folders.length))
  const segments = folder.split('/').filter(Boolean)
  const selected = rows.filter(file => selection.has(file.id))
  const selectedFolders = visibleFolders.filter(node => selection.has(node.prefix))
  const selectedTargets: CatalogMetadataTarget[] = [...selected.map(file => ({ scope: 'FILE' as const, path: file.relativePath })),
    ...selectedFolders.map(node => ({ scope: 'FOLDER' as const, path: node.prefix }))]
  const inspected = files.find(file => file.id === inspectedId)
  const inspectedFolder = visibleFolders.find(node => node.prefix === inspectedId)
  const inspectedTarget: CatalogMetadataTarget | undefined = inspected ? { scope: 'FILE', path: inspected.relativePath }
    : inspectedFolder ? { scope: 'FOLDER', path: inspectedFolder.prefix } : undefined
  const plans = planTransfers(selectedEndpoint, endpoints, selected, selectedFolders, files)
  const inspectorPlans = inspectedTarget ? planTransfers(selectedEndpoint, endpoints, inspected ? [inspected] : [],
    inspectedFolder ? [inspectedFolder] : [], files) : undefined
  const transfer = action && plans[action]
  const targets = action && transfer ? transferTargets(action, transfer.source, endpoints) : []
  const itemKeys = [...visibleFolders.map(node => node.prefix), ...rows.map(file => file.id)]

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
    setSelection(new Set([inspectedId]))
    setInspectedId(undefined)
    setAction(operation)
  }

  const viewport = useRef<HTMLDivElement>(null)
  const virtual = useVirtualizer({ count: rows.length + visibleFolders.length, getScrollElement: () => viewport.current, estimateSize: () => 44, overscan: 8 })

  function goToPage(index: number) {
    setPage(index)
    setSelection(new Set())
    setInspectedId(undefined)
    if (viewport.current) viewport.current.scrollTop = 0
  }

  function openFolder(prefix: string) {
    setFolder(prefix)
    setSearch('')
    goToPage(0)
  }

  const resize = (size: number) => setWidth(Math.min(360, Math.max(210, size)))

  const failed = connections.isError ? connections : snapshot.isError ? snapshot : term ? annotations : undefined
  if (failed?.error && !action) return <ApiPageState loadingLabel="Loading catalog" error={failed.error}
    retry={() => void failed.refetch()} retrying={failed.isFetching}
    reset={failed === annotations ? () => { setSearch(''); goToPage(0) } : undefined} />
  if (connections.isPending || (selectedEndpoint && snapshot.isPending && !action)) {
    return <ApiPageState loadingLabel="Loading catalog" />
  }

  return <div className={fullscreen ? 'explorer-fullscreen' : undefined} onKeyDown={event => {
    if (event.key === 'Escape' && fullscreen && !event.currentTarget.querySelector('dialog[open]')) setFullscreen(false)
  }}>
    <div className="page-heading"><div><h1>Catalog explorer</h1><p>Browse, inspect and move files across source servers and S3.</p></div>
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
            <div className="catalog-title"><h2>{segments.at(-1) ?? selectedEndpoint.name}</h2><Badge>{selectedEndpoint.providerType}</Badge></div>
          </div>
          <div className="table-tools catalog-tools">
            <label className="search catalog-search"><Icon name="search" size={16} />
              <input type="search" aria-label="Search this location" placeholder="Search names, tags or properties" value={search}
                onChange={event => { setSearch(event.target.value); goToPage(0) }} /></label>
          </div>
          <div className="catalog-viewport" ref={viewport}>
            <div className="catalog-table" role="table" aria-label="Catalog files">
              <div className="catalog-row catalog-table-header" role="row">
                <span role="columnheader"><input type="checkbox" aria-label="Select all items on this page"
                  disabled={!canTransfer || !itemKeys.length} checked={itemKeys.length > 0 && selectedTargets.length === itemKeys.length}
                  onChange={event => setSelection(event.target.checked ? new Set(itemKeys) : new Set())} /></span>
                {['Name', 'Size', 'Storage state', 'Temperature', 'Last modified date', 'Checksum'].map(label => <span role="columnheader" key={label}>{label}</span>)}
              </div>
              <div className="virtual-items" role="rowgroup" style={{ height: virtual.getTotalSize() }}>
                {virtual.getVirtualItems().map(item => {
                  const node = visibleFolders[item.index]
                  const position = { transform: `translateY(${item.start}px)`, height: item.size }
                  if (node) return <div className={`catalog-row virtual-row ${selection.has(node.prefix) ? 'checked' : ''}`}
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
                    {Array.from({ length: 5 }, (_, index) => <span role="cell" key={index}>—</span>)}
                  </div>
                  const file = rows[item.index - visibleFolders.length]
                  const stateLabel = stateLabels[file.storageState]
                  const temperature = temperatureOf(file.lastModifiedAt)
                  const storageClass = file.s3StorageClass
                  const archived = file.storageState === 'ARCHIVED'
                  return <div className={`catalog-row virtual-row ${selection.has(file.id) ? 'checked' : ''}`} role="row" key={file.id}
                    style={position} onClick={() => setInspectedId(file.id)}>
                    <span role="cell" onClick={event => event.stopPropagation()}><input type="checkbox" aria-label={'Select ' + file.fileName} disabled={!canTransfer} checked={canTransfer && selection.has(file.id)}
                      onChange={event => toggle(file.id, event.target.checked)} /></span>
                    <span className="catalog-name" role="cell"><button className="text-button" title={file.relativePath}
                      onClick={() => setInspectedId(file.id)}><Icon name="file" size={15} /><span>{file.fileName}</span></button></span>
                    <span role="cell">{bytes(file.sizeBytes)}</span>
                    <span role="cell"><Badge dot tone={archived ? 'green' : file.storageState === 'FAILED' ? 'red' : 'neutral'}>
                      {archived && storageClass ? stateLabel + ' · ' + (storageName[storageClass] ?? storageClass) : stateLabel}
                    </Badge></span>
                    <span role="cell">{temperature ? <Badge tone={temperatureTone[temperature]}>{temperature}</Badge> : '—'}</span>
                    <span role="cell">{file.lastModifiedAt ? date(file.lastModifiedAt) : '—'}</span>
                    <span role="cell">{file.checksumSha256 ? <Badge tone="green">Recorded</Badge> : '—'}</span>
                  </div>
                })}
              </div>
            </div>
            {term && annotations.isPending ? <LoadingRows label="Loading files" /> : total === 0 &&
              <div className="empty catalog-empty"><Icon name="folder" size={26} /><strong>No matching items</strong>
                <span>Try a different search or open another folder.</span></div>}
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
      <Button icon="upload" disabled={!plans.archive} onClick={() => setAction('archive')}>
        {selectedFolders.length ? 'Archive folders' : 'Archive to S3'}
      </Button>
      <Button icon="download" disabled={!plans.recall} onClick={() => setAction('recall')}>
        {selectedFolders.length ? 'Recall folders' : 'Recall to Local/NAS'}
      </Button>
    </div>}
    {selectedEndpoint && inspectedTarget && <FileInspector key={inspectedId} file={inspected} target={inspectedTarget}
      location={selectedEndpoint} connections={endpoints} metadata={annotations.data ? metadataFor(index, inspectedTarget) : undefined}
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
