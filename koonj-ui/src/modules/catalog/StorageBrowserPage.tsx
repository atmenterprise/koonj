import { useVirtualizer } from '@tanstack/react-virtual'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useScanMutation } from '../../api/hooks/mutations'
import { useCatalogSnapshot, useCatalogLocations, useTransferJobs } from '../../api/hooks/queries'
import { folderTree, isObjectStorage, stateLabels, type FolderNode } from '../../api/mappers'
import type { CatalogMetadataTarget, ProviderType } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { Badge, Button, Icon, bytes, count, date, storageName, temperatureTone } from '../../shared/ui/primitives'
import { FileInspector } from './FileInspector'
import { FavoriteButton } from './FavoriteButton'
import { favoriteKey, useCatalogFavorites } from './catalogFavorites'
import { directMetadataFor, metadataFor, metadataIndex, useCatalogMetadata } from './catalogMetadata'
import { catalogColumns, compareCatalogValues, type CatalogColumn, type CatalogSort, type SortValue } from './catalogSort'
import { BulkMetadataDialog } from './MetadataForm'
import { folderTotals, indexFolderContents } from './folderContents'
import { temperatureOf } from './fileDates'
import { planTransfers, transferTargets, type TransferPlan } from './transferSelection'
import { SymlinkTarget } from './SymlinkTarget'
import { TransferDialog, type CatalogTransferAction } from './TransferDialog'

function Folders({ nodes, prefix, onOpen }: { nodes: FolderNode[]; prefix: string; onOpen: (prefix: string) => void }) {
  return <ul className="live-folders">{nodes.map(node => <FolderBranch key={node.prefix} node={node}
    prefix={prefix} onOpen={onOpen} />)}</ul>
}

function FolderBranch({ node, prefix, onOpen }: { node: FolderNode; prefix: string; onOpen: (prefix: string) => void }) {
  const [open, setOpen] = useState<boolean>()
  const expanded = open ?? prefix.startsWith(node.prefix)
  return <li>
    <div className={node.prefix === prefix ? 'tree-item selected' : 'tree-item'}>
      {node.children.length > 0 ? <button type="button" className="tree-toggle" aria-expanded={expanded}
        aria-label={(expanded ? 'Collapse ' : 'Expand ') + node.name} onClick={() => setOpen(!expanded)}>
        <Icon name={expanded ? 'down' : 'chevron'} size={13} />
      </button> : <span className="tree-toggle" aria-hidden="true" />}
      <button type="button" className="tree-name" aria-current={node.prefix === prefix ? 'location' : undefined} onClick={() => onOpen(node.prefix)}>
        <Icon name="folder" size={14} /><span>{node.name}</span>
      </button>
    </div>
    {expanded && node.children.length > 0 && <Folders nodes={node.children} prefix={prefix} onOpen={onOpen} />}
  </li>
}

function visibleFolder(nodes: FolderNode[], requested: string, parent = ''): string {
  const child = nodes.find(node => requested.startsWith(node.prefix))
  return child ? visibleFolder(child.children, requested, child.prefix) : parent
}

function storageLocationIcon(provider: ProviderType) {
  return isObjectStorage(provider) ? 'cloud' : 'drive'
}

function folderSummary(totals: { files: number; folders: number } | undefined) {
  const { files = 0, folders = 0 } = totals ?? {}
  return [files && `${count(files)} ${files === 1 ? 'file' : 'files'}`,
    folders && `${count(folders)} ${folders === 1 ? 'folder' : 'folders'}`].filter(Boolean).join(' · ') || 'Empty folder'
}

function TagsCell({ tags, failed }: { tags?: string[]; failed: boolean }) {
  if (!tags?.length) {
    return <span role="cell" className="catalog-tags-empty">{failed ? 'Unavailable' : tags ? '—' : 'Loading…'}</span>
  }

  return <div role="cell" className="catalog-tags-cell">
    <ul className="catalog-tags" tabIndex={0} aria-label="Custom tags">
      {tags.map(tag => <li key={tag}><Badge tone="blue">{tag}</Badge></li>)}
    </ul>
  </div>
}

function MetadataButton({ name, onClick }: { name: string; onClick: () => void }) {
  return <Button icon="info" variant="quiet" className="icon-button catalog-info-button" title="View metadata"
    aria-label={`View metadata for ${name}`} aria-haspopup="dialog" onClick={onClick} />
}

export default function StorageBrowserPage({ initialConnection, initialEntry, initialPath, navigate }: {
  initialConnection: string | null; initialEntry: string | null; initialPath: string | null; navigate: (path: string) => void
}) {
  const { hasRole } = useAuth()
  const canTransfer = hasRole('KOONJ_ADMIN') || hasRole('KOONJ_OPERATOR')
  const connections = useCatalogLocations()
  const endpoints = connections.data ?? []
  const [endpointId, setEndpointId] = useState(initialConnection)
  const [requestedFolder, setFolder] = useState<string>()
  const [sort, setSort] = useState<CatalogSort>()
  const [page, setPage] = useState<number>()
  const [pageSize, setPageSize] = useState(50)
  const [width, setWidth] = useState(250)
  const [fullscreen, setFullscreen] = useState(false)
  const drag = useRef<{ x: number; width: number } | null>(null)
  const [selection, setSelection] = useState<Set<string>>(() => new Set())
  const [expandedFolders, setExpandedFolders] = useState<Set<string>>(() => new Set())
  const [inspectedId, setInspectedId] = useState<string>()
  const [editingSelection, setEditingSelection] = useState(false)
  const [transferRequest, setTransferRequest] = useState<{
    action: CatalogTransferAction
    plan: TransferPlan
    selectedItems: CatalogMetadataTarget[]
    selectedLocation: string
  }>()
  const selectedEndpoint = endpoints.find(item => item.id === endpointId) ?? endpoints[0]
  const scan = useScanMutation()
  const jobs = useTransferJobs(canTransfer)
  const scanning = jobs.data?.some(job => job.operation === 'SCAN' && job.sourceConnectionId === selectedEndpoint?.id
    && (job.status === 'PENDING' || job.status === 'RUNNING'))
  const snapshot = useCatalogSnapshot(selectedEndpoint?.id, !!selectedEndpoint)
  const annotations = useCatalogMetadata(selectedEndpoint?.id)
  const { favorites, change: changeFavorite } = useCatalogFavorites()
  const favoriteKeys = useMemo(() => new Set(favorites.data?.map(favoriteKey)), [favorites.data])
  const index = useMemo(() => metadataIndex(annotations.data ?? []), [annotations.data])
  const folders = useMemo(() => folderTree(snapshot.data?.map(file => file.entryType === 'DIRECTORY' ? file.relativePath.replace(/\/+$/, '') + '/' : file.relativePath) ?? []), [snapshot.data])
  const initialLocation = initialPath ?? (initialEntry && requestedFolder === undefined
    ? snapshot.data?.find(file => file.id === initialEntry)?.relativePath : undefined) ?? ''
  const requestedPath = requestedFolder ?? initialLocation.slice(0, initialLocation.lastIndexOf('/') + 1)
  const folder = snapshot.data ? visibleFolder(folders, requestedPath) : requestedPath
  const locationName = folder.split('/').filter(Boolean).at(-1) ?? selectedEndpoint?.name ?? ''
  const totalsByFolder = useMemo(() => folderTotals(folders, snapshot.data ?? []), [folders, snapshot.data])
  const locationTotals = totalsByFolder.get(folder)
  const catalog = useMemo(() => indexFolderContents(folders, snapshot.data ?? []), [folders, snapshot.data])
  const files = useMemo(() => snapshot.data ?? [], [snapshot.data])
  const orderContents = useMemo(() => (prefix: string) => {
    const contents = catalog.contents(prefix)
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
        case 'size': return entry.kind === 'folder' ? totalsByFolder.get(entry.node.prefix)?.sizeBytes : file && (file.entryType === 'SYMLINK' ? 0 : file.sizeBytes)
        case 'state': return file ? stateLabels[file.storageState] : undefined
        case 'temperature': {
          const temperature = temperatureOf(file?.lastModifiedAt ?? null)
          return temperature ? { COLD: 0, WARM: 1, HOT: 2 }[temperature] : undefined
        }
        case 'modified': return file?.lastModifiedAt ? Date.parse(file.lastModifiedAt) : undefined
        case 'integrity': return file ? Number(!!file.checksumSha256) : undefined
        case 'tags': {
          const target: CatalogMetadataTarget = entry.kind === 'folder' ? { scope: 'FOLDER', path: entry.node.prefix }
            : { scope: 'FILE', path: entry.file.relativePath }
          return directMetadataFor(index, target).tags.join(' ') || undefined
        }
      }
    }

    return entries.map(entry => ({ entry, value: sortValue(entry) }))
      .sort((left, right) => compareCatalogValues(left.value, right.value, direction))
      .map(({ entry }) => entry)
  }, [catalog, index, sort, totalsByFolder])
  const ordered = useMemo(() => orderContents(folder), [orderContents, folder])
  const total = ordered.length
  const totalPages = Math.max(1, Math.ceil(total / pageSize))
  const initialIndex = page === undefined && (initialEntry || initialPath) ? ordered.findIndex(entry => entry.key === initialEntry
    || (!initialEntry && entry.kind === 'file' && entry.file.relativePath === initialPath)) : -1
  const currentPage = Math.min(page ?? Math.max(0, Math.floor(initialIndex / pageSize)), totalPages - 1)
  const pageEntries = useMemo(() => ordered.slice(currentPage * pageSize, (currentPage + 1) * pageSize), [ordered, currentPage, pageSize])
  const visible = useMemo(() => {
    const rows: (typeof ordered[number] & { depth: number })[] = []
    function append(entries: typeof ordered, depth: number) {
      for (const entry of entries) {
        rows.push({ ...entry, depth })
        if (entry.kind === 'folder' && expandedFolders.has(entry.key)) append(orderContents(entry.key), depth + 1)
      }
    }
    append(pageEntries, 0)
    return rows
  }, [pageEntries, orderContents, expandedFolders])
  const segments = folder.split('/').filter(Boolean)
  const currentFolder = catalog.foldersByPath.get(folder)
  const [selected, selectedFolders] = useMemo(() => {
    const selectedFiles = []
    const selectedFolders = []
    if (currentFolder && selection.has(currentFolder.prefix)) selectedFolders.push(currentFolder)
    if (selection.size) {
      for (const entry of visible) {
        if (!selection.has(entry.key)) continue
        if (entry.kind === 'folder') selectedFolders.push(entry.node)
        else selectedFiles.push(entry.file)
      }
    }
    return [selectedFiles, selectedFolders] as const
  }, [visible, selection, currentFolder])
  const selectedTargets: CatalogMetadataTarget[] = [...selected.map(file => ({ scope: 'FILE' as const, path: file.relativePath })),
    ...selectedFolders.map(node => ({ scope: 'FOLDER' as const, path: node.prefix }))]
  const inspectedFolder = inspectedId ? catalog.foldersByPath.get(inspectedId) : undefined
  const inspected = inspectedId && !inspectedFolder
    ? files.find(file => file.entryType !== 'DIRECTORY' && file.id === inspectedId) : undefined
  const inspectedTarget: CatalogMetadataTarget | undefined = inspected ? { scope: 'FILE', path: inspected.relativePath }
    : inspectedFolder ? { scope: 'FOLDER', path: inspectedFolder.prefix } : undefined
  const plans = useMemo(() => planTransfers(selectedEndpoint, endpoints, selected, selectedFolders, files),
    [selectedEndpoint, endpoints, selected, selectedFolders, files])
  const inspectorPlans = useMemo(() => planTransfers(selectedEndpoint, endpoints, inspected ? [inspected] : [],
    inspectedFolder ? [inspectedFolder] : [], files), [selectedEndpoint, endpoints, inspected, inspectedFolder, files])
  const targets = transferRequest ? transferTargets(transferRequest.action, transferRequest.plan.source, endpoints)
    .filter(target => !transferRequest.plan.destinationId || target.id === transferRequest.plan.destinationId) : []
  const itemKeys = pageEntries.map(entry => entry.key)
  const wholeFolderSelected = !!currentFolder && selection.has(currentFolder.prefix)

  function favoriteButton(target: CatalogMetadataTarget, name: string) {
    if (!selectedEndpoint) return null
    const item = { ...target, connectionId: selectedEndpoint.id }
    const favorited = favoriteKeys.has(favoriteKey(item))
    return <FavoriteButton name={name} favorited={favorited} disabled={!favorites.data || changeFavorite.isPending}
      onClick={() => changeFavorite.mutate({ ...item, favorite: !favorited })} />
  }

  function toggle(key: string, checked: boolean) {
    setSelection(previous => {
      const next = new Set(previous)
      if (checked) next.add(key)
      else next.delete(key)
      return next
    })
  }

  function startTransfer(action: CatalogTransferAction, plan: TransferPlan | undefined, selectedItems: CatalogMetadataTarget[]) {
    if (!plan || !selectedEndpoint) return
    setInspectedId(undefined)
    setTransferRequest({ action, plan, selectedItems, selectedLocation: selectedEndpoint.name })
  }

  function toggleFolder(prefix: string) {
    const next = new Set(expandedFolders)
    if (next.delete(prefix)) {
      setSelection(previous => {
        const selected = new Set(previous)
        for (const entry of visible) {
          const path = entry.kind === 'folder' ? entry.node.prefix : entry.file.relativePath
          if (path !== prefix && path.startsWith(prefix)) selected.delete(entry.key)
        }
        return selected
      })
    } else next.add(prefix)
    setExpandedFolders(next)
  }

  const viewport = useRef<HTMLDivElement>(null)
  const getItemKey = useCallback((index: number) => visible[index].key, [visible])
  const virtual = useVirtualizer({ count: visible.length, getScrollElement: () => viewport.current, estimateSize: () => 58,
    getItemKey, overscan: 8 })
  const locatedKey = ordered[initialIndex]?.key
  useEffect(() => {
    if (initialIndex >= 0) virtual.scrollToIndex(initialIndex % pageSize, { align: 'center' })
  }, [initialIndex, pageSize, virtual])

  function goToPage(index: number, preserveFolder = true) {
    setPage(index)
    if (!preserveFolder || !wholeFolderSelected) setSelection(new Set())
    setExpandedFolders(new Set())
    setInspectedId(undefined)
    if (viewport.current) viewport.current.scrollTop = 0
  }

  function openFolder(prefix: string) {
    setFolder(prefix)
    goToPage(0, false)
    if (viewport.current) viewport.current.scrollLeft = 0
  }

  function sortBy(column: CatalogColumn) {
    setSort(previous => ({ column, direction: previous?.column === column && previous.direction === 'ascending'
      ? 'descending' : 'ascending' }))
    goToPage(0)
  }

  const resize = (size: number) => setWidth(Math.min(360, Math.max(210, size)))

  const failed = connections.isError ? connections : snapshot.isError ? snapshot : undefined
  if (failed?.error && !transferRequest) return <ApiPageState loadingLabel="Loading catalog" error={failed.error}
    retry={() => void failed.refetch()} retrying={failed.isFetching} />
  if (connections.isPending || (selectedEndpoint && snapshot.isPending && !transferRequest)) {
    return <ApiPageState loadingLabel="Loading catalog" />
  }

  return <div className={fullscreen ? 'explorer-fullscreen' : undefined} onKeyDown={event => {
    if (event.key === 'Escape' && fullscreen && !event.currentTarget.querySelector('dialog[open]')) setFullscreen(false)
  }}>
    <div className="page-heading"><div><h1>Catalog</h1><p>Browse, inspect and move files across storage locations.</p></div>
      <Button icon={fullscreen ? 'minimize' : 'maximize'} aria-pressed={fullscreen} onClick={() => setFullscreen(!fullscreen)}>
        {fullscreen ? 'Exit full screen' : 'Full screen'}
      </Button>
    </div>
    {!selectedEndpoint
      ? <p className="panel empty">Add a storage connection to browse its catalog.</p>
      : <div className="panel explorer storage-explorer" style={{ gridTemplateColumns: `${width}px 7px minmax(0, 1fr)` }}>
        <aside className="catalog-tree" aria-label="Storage folders">
          <div className="tree-header"><Icon name="layers" size={17} /><h2>Storage locations</h2></div>
          {[false, true].map(cloud => {
            const locations = endpoints.filter(item => isObjectStorage(item.providerType) === cloud)
            return locations.length > 0 && <section className="tree-section" key={String(cloud)}>
              <h2 className="tree-section-label">{cloud ? 'S3 storage' : 'On-premise storage'}</h2>
              {locations.map(item => {
                const current = item.id === selectedEndpoint.id
                return <div key={item.id}>
                  <div className={`tree-item storage-root${current && !folder ? ' selected' : ''}`}>
                    <button type="button" className="tree-name" aria-current={current && !folder ? 'location' : undefined}
                      onClick={() => { setEndpointId(item.id); openFolder('') }}>
                      <Icon name={storageLocationIcon(item.providerType)} size={15} /><span>{item.name}</span><small>{item.providerType}</small>
                    </button>
                  </div>
                  {current && <Folders nodes={folders} prefix={folder} onOpen={openFolder} />}
                </div>
              })}
            </section>
          })}
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
              <button type="button" aria-current={!folder ? 'location' : undefined} onClick={() => openFolder('')}>
                <Icon name={storageLocationIcon(selectedEndpoint.providerType)} size={15} />{selectedEndpoint.name}
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
              <div className="catalog-location-identity">
                <span className="catalog-location-icon"><Icon name={folder ? 'folder' : storageLocationIcon(selectedEndpoint.providerType)} size={23} /></span>
                <div className="catalog-title"><h2>{locationName}</h2><Badge>{selectedEndpoint.providerType}</Badge></div>
              </div>
              {canTransfer && currentFolder && !!locationTotals?.files && <Button icon="folder" aria-pressed={wholeFolderSelected}
                onClick={() => setSelection(wholeFolderSelected ? new Set() : new Set([currentFolder.prefix]))}>
                {wholeFolderSelected ? 'Folder selected' : 'Select entire folder'}
              </Button>}
              <div className="catalog-location-tools">
                {locationTotals && <dl className="catalog-location-totals" aria-label="Catalog totals including subfolders">
                  <div><dt>Total size</dt><dd>{bytes(locationTotals.sizeBytes)}</dd></div>
                  <div><dt>Files</dt><dd>{count(locationTotals.files)}</dd></div>
                  <div><dt>Folders</dt><dd>{count(locationTotals.folders)}</dd></div>
                </dl>}
                {canTransfer && <Button icon="scan" disabled={!selectedEndpoint.active || scan.isPending || scanning}
                  onClick={() => scan.mutate(selectedEndpoint.id)}>{scanning ? 'Scanning…' : 'Scan'}</Button>}
                {folder && <MetadataButton name={locationName} onClick={() => setInspectedId(folder)} />}
              </div>
            </div>
          </div>
          <div className="catalog-viewport" ref={viewport} tabIndex={0} role="region" aria-label="Scrollable catalog table">
            <div className="catalog-table catalog-table-tags" role="table" aria-label="Catalog folders and files"
              aria-rowcount={visible.length + 1}>
              <div className="catalog-row catalog-table-header" role="row" aria-rowindex={1}>
                <span role="columnheader"><input type="checkbox" aria-label="Select all items on this page"
                  disabled={!canTransfer || wholeFolderSelected || !itemKeys.length} checked={itemKeys.length > 0 && itemKeys.every(key => selection.has(key))}
                  onChange={event => setSelection(event.target.checked ? new Set(itemKeys) : new Set())} /></span>
                {(Object.keys(catalogColumns) as CatalogColumn[]).map(column => {
                  const direction = sort?.column === column ? sort.direction : undefined
                  return <span role="columnheader" key={column} aria-sort={direction} className={`catalog-column-${column}`}>
                    <button type="button" className="catalog-sort" onClick={() => sortBy(column)}>
                      {catalogColumns[column]} <span aria-hidden="true">{direction === 'ascending' ? '↑' : direction === 'descending' ? '↓' : '↕'}</span>
                    </button>
                  </span>
                })}
                <span role="columnheader" className="catalog-action-cell"><span className="sr-only">Favorite and metadata</span></span>
              </div>
              <div className="virtual-items" role="rowgroup" style={{ height: virtual.getTotalSize() }}>
                {virtual.getVirtualItems().map(item => {
                  const entry = visible[item.index]
                  const target: CatalogMetadataTarget = entry.kind === 'folder' ? { scope: 'FOLDER', path: entry.node.prefix }
                    : { scope: 'FILE', path: entry.file.relativePath }
                  const tags = <TagsCell tags={annotations.data ? directMetadataFor(index, target).tags : undefined}
                    failed={annotations.isError} />
                  const position = { transform: `translateY(${item.start}px)`, height: item.size }
                  const indent = { paddingInlineStart: Math.min(entry.depth, 5) * 12 }
                  const rowState = (wholeFolderSelected || selection.has(entry.key) ? ' checked' : '') + (entry.key === locatedKey ? ' located' : '')
                  if (entry.kind === 'folder') {
                    const { node } = entry
                    const totals = totalsByFolder.get(node.prefix)
                    const expandable = Boolean(totals && (totals.files || totals.folders))
                    const expanded = expandedFolders.has(node.prefix)
                    const summary = folderSummary(totals)
                    return <div className={`catalog-row virtual-row catalog-folder-row${rowState}`}
                      role="row" aria-rowindex={item.index + 2} key={node.prefix} style={position}>
                      <span role="cell"><input type="checkbox" aria-label={'Select folder ' + node.name}
                        disabled={!canTransfer || wholeFolderSelected} checked={canTransfer && (wholeFolderSelected || selection.has(node.prefix))}
                        onChange={event => toggle(node.prefix, event.target.checked)} /></span>
                      <div role="cell" className="catalog-name catalog-folder-name" style={indent}>
                        {expandable ? <button type="button" className="catalog-disclosure" aria-expanded={expanded}
                          aria-label={(expanded ? 'Collapse ' : 'Expand ') + node.name}
                          onClick={() => toggleFolder(node.prefix)}>
                          <Icon name={expanded ? 'down' : 'chevron'} size={15} />
                        </button> : <span className="catalog-disclosure" aria-hidden="true" />}
                        <button type="button" className="text-button" title={node.prefix} onClick={() => openFolder(node.prefix)}>
                          <span className="catalog-folder-icon"><Icon name="folder" size={17} /></span>
                          <span className="catalog-folder-label"><strong>{node.name}</strong><small>{summary}</small></span>
                        </button>
                      </div>
                      <span role="cell" className="catalog-column-size">{bytes(totals?.sizeBytes ?? 0)}</span>
                      {Array.from({ length: 4 }, (_, index) => <span role="cell" key={index}>—</span>)}
                      {tags}
                      <div role="cell" className="catalog-action-cell">
                        {favoriteButton(target, node.name)}
                        <MetadataButton name={node.name} onClick={() => setInspectedId(node.prefix)} />
                      </div>
                    </div>
                  }
                  const { file } = entry
                  const stateLabel = stateLabels[file.storageState]
                  const temperature = temperatureOf(file.lastModifiedAt)
                  const storageClass = file.s3StorageClass
                  const archived = file.storageState === 'ARCHIVED'
                  const symlink = file.entryType === 'SYMLINK'
                  return <div className={`catalog-row virtual-row${rowState}`} role="row" aria-rowindex={item.index + 2} key={file.id} style={position}>
                    <span role="cell"><input type="checkbox" aria-label={'Select ' + file.fileName} disabled={!canTransfer || wholeFolderSelected} checked={canTransfer && (wholeFolderSelected || selection.has(file.id))}
                      onChange={event => toggle(file.id, event.target.checked)} /></span>
                    <div className="catalog-entry-name" role="cell" style={indent}>
                      <div className="catalog-file-name">
                        <div className="catalog-name catalog-file-label"><Icon name={symlink ? 'link' : 'file'} size={17} />
                          <span className="catalog-file-title" title={file.relativePath}>{file.fileName}</span>
                          {symlink && <Badge tone="blue">SYMLINK</Badge>}
                        </div>
                        {symlink && <SymlinkTarget file={file} locations={endpoints} navigate={navigate} />}
                      </div>
                    </div>
                    <span role="cell" className="catalog-column-size">{symlink ? '0B' : bytes(file.sizeBytes)}</span>
                    <span role="cell" className="catalog-file-state">
                      <Badge dot tone={archived || file.storageState === 'ARCHIVED_COPY' ? 'green' : file.storageState === 'FAILED' ? 'red' : 'neutral'}>
                        <span>{archived && storageClass ? stateLabel + ' · ' + (storageName[storageClass] ?? storageClass) : stateLabel}</span>
                      </Badge>
                    </span>
                    <span role="cell">{temperature ? <Badge tone={temperatureTone[temperature]}>{temperature}</Badge> : '—'}</span>
                    <span role="cell">{file.lastModifiedAt ? date(file.lastModifiedAt) : '—'}</span>
                    <span role="cell">{file.checksumSha256 ? <Badge tone="green">Recorded</Badge> : '—'}</span>
                    {tags}
                    <div role="cell" className="catalog-action-cell">
                      {favoriteButton(target, file.fileName)}
                      <MetadataButton name={file.fileName} onClick={() => setInspectedId(file.id)} />
                    </div>
                  </div>
                })}
              </div>
            </div>
            {total === 0 && <div className="empty catalog-empty">
              <Icon name="folder" size={26} /><strong>This folder is empty</strong>
            </div>}
          </div>
          <div className="catalog-pagination">
            <span aria-live="polite">{count(total)} {total === 1 ? 'item' : 'items'}</span>
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
      <strong>{wholeFolderSelected ? 'Entire folder selected' : `${count(selectedTargets.length)} selected`}</strong>
      <Button icon="close" aria-label="Clear selection" onClick={() => setSelection(new Set())} />
      <Button variant="primary" icon="layers" onClick={() => setEditingSelection(true)}>Edit metadata</Button>
      {plans.archive && <Button icon="upload" onClick={() => startTransfer('archive', plans.archive, selectedTargets)}>Archive to S3</Button>}
      {plans.recall && <Button icon="download" onClick={() => startTransfer('recall', plans.recall, selectedTargets)}>Recall to Source</Button>}
    </div>}
    {selectedEndpoint && inspectedTarget && <FileInspector key={inspectedId} file={inspected} target={inspectedTarget}
      favoriteControl={favoriteButton(inspectedTarget, inspected?.fileName ?? inspectedFolder?.name ?? inspectedTarget.path)}
      folderDetails={inspectedFolder ? folderSummary(totalsByFolder.get(inspectedFolder.prefix)) : undefined}
      location={selectedEndpoint} connections={endpoints} navigate={navigate} metadata={annotations.data ? metadataFor(index, inspectedTarget) : undefined}
      metadataError={annotations.error} onRetry={() => void annotations.refetch()} writable={canTransfer}
      onClose={() => setInspectedId(undefined)} onOpen={inspectedFolder && inspectedFolder.prefix !== folder ? () => openFolder(inspectedFolder.prefix) : undefined}
      onArchive={canTransfer && inspectorPlans.archive ? () => startTransfer('archive', inspectorPlans.archive, [inspectedTarget]) : undefined}
      onRecall={canTransfer && inspectorPlans.recall ? () => startTransfer('recall', inspectorPlans.recall, [inspectedTarget]) : undefined} />}
    {canTransfer && selectedEndpoint && editingSelection && selectedTargets.length > 0 &&
      <BulkMetadataDialog connectionId={selectedEndpoint.id} targets={selectedTargets} onClose={() => setEditingSelection(false)}
        onSaved={() => { setEditingSelection(false); setSelection(new Set()) }} />}
    {canTransfer && transferRequest && <TransferDialog action={transferRequest.action} plan={transferRequest.plan}
      selectedItems={transferRequest.selectedItems} selectedLocation={transferRequest.selectedLocation} targets={targets}
      onClose={() => setTransferRequest(undefined)} onSubmitted={() => {
        setSelection(new Set())
        setTransferRequest(undefined)
      }} />}
  </div>
}
