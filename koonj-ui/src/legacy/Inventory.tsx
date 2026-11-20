import { useVirtualizer } from '@tanstack/react-virtual'
import { useEffect, useId, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react'
import CatalogTree from './CatalogTree'
import { createCatalogSource, type CatalogPage } from './catalogSource'
import { inheritedMetadata, metadataForEntry, type CatalogMetadata, type CustomMetadata } from './catalogMetadata'
import { isMovement, resolveTransferSelection } from './fileMovements'
import MetadataEditor from './MetadataEditor'
import {
  entryName, fileTemperature, parentPath, type ChecksumResult, type Endpoint, type Entry, type Operation, type StorageClass,
} from './sampleData'
import { Badge, Button, Icon, bytes, count, date, statusTone, storageName, temperatureTone, time } from '../shared/ui/primitives'
import type { JobOptions, Workspace } from './workspace'

const filters = ['All', 'Local Only', 'Archived', 'Purged Stubs', 'In Progress']
const initialPath = 'projects/2026/raw-footage'
const entryIcon = (entry: Entry) => entry.folder ? 'folder' : /\.(mov|mp4)$/i.test(entry.path) ? 'film' : 'file'
const itemCount = (value: number) => count(value) + (value === 1 ? ' item' : ' items')

function CheckBox({ checked, mixed = false, disabled = false, label, onChange }: { checked: boolean; mixed?: boolean; disabled?: boolean; label: string; onChange: () => void }) {
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => { if (input.current) input.current.indeterminate = mixed }, [mixed])
  return <input ref={input} type="checkbox" checked={checked} disabled={disabled} onChange={onChange} aria-label={label} />
}

function Overlay({ title, onClose, children, drawer = false }: { title: string; onClose: () => void; children: ReactNode; drawer?: boolean }) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const element = dialog.current
    element?.showModal()
    return () => element?.close()
  }, [])
  return <dialog ref={dialog} aria-label={title} className={drawer ? 'drawer' : 'operation-dialog'}
    onCancel={event => { event.preventDefault(); onClose() }}
    onClick={event => { if (event.target === event.currentTarget) onClose() }}>
    <div className="dialog-content">{children}</div>
  </dialog>
}

function ChecksumIndicator({ result }: { result: ChecksumResult }) {
  const tone = result === 'Matched' ? 'green' : result === 'Mismatch' ? 'red' : 'neutral'
  return <Badge tone={tone} dot>{result === 'Unavailable' ? 'Not recorded' : result}</Badge>
}

function Lineage({ entry }: { entry: Entry }) {
  return entry.movements?.length ? <ol className="file-lineage">
    {entry.movements.map((movement, index) => <li key={movement.activityId ?? index}>
      <div className="lineage-title">
        <span className={`icon-tile ${movement.operation === 'ARCHIVE' ? 'yellow' : 'blue'}`}><Icon name={movement.operation === 'ARCHIVE' ? 'upload' : 'download'} size={16} /></span>
        <div><strong>{movement.operation === 'ARCHIVE' ? 'Archive to S3' : 'Recall from S3'}</strong>
          <small>{date(movement.at)} · {time(movement.at)}{movement.activityId && ' · ' + movement.activityId}</small></div>
        <ChecksumIndicator result={movement.checksum} />
      </div>
      <dl className="lineage-paths">
        <div><dt>From</dt><dd>{movement.source}</dd></div>
        <div><dt>To</dt><dd>{movement.destination}</dd></div>
        {movement.storageClass && <div><dt>S3 tier</dt><dd>{storageName[movement.storageClass]}</dd></div>}
      </dl>
      {movement.outcome === 'Failed' ? <p className="error-text">Transfer failed</p>
        : movement.purged && <p>Local copy removed after verification. The S3 copy remains available.</p>}
    </li>)}
  </ol> : <p className="empty compact">No archive or recall movements have been recorded for this file.</p>
}

function Inspector({ entry, endpoint, customMetadata, inherited, onSave, onClose, onOpen, onAction }: {
  entry: Entry; endpoint: Endpoint; customMetadata: CustomMetadata; inherited: CustomMetadata; onSave: (metadata: CustomMetadata) => boolean
  onClose: () => void; onOpen: () => void; onAction: (operation: Operation) => void
}) {
  const [tab, setTab] = useState<'metadata' | 'lineage'>('metadata')
  const metadataForm = useId()
  const uri = entry.bucket && entry.key ? `s3://${entry.bucket}/${entry.key}` : endpoint.origin === 'S3' ? `${endpoint.path}/${entry.path}` : undefined
  const temperature = entry.folder ? undefined : fileTemperature(entry.modified)?.temperature
  const details = [
    ['Source path', endpoint.origin !== 'S3' ? `${endpoint.path}/${entry.path}` : '—'],
    ['S3 URI', uri ?? '—'],
    ['Storage class', entry.storageClass ? storageName[entry.storageClass] : '—'],
    ['Last modified', date(entry.modified)],
    ['Archived at', entry.archivedAt ? date(entry.archivedAt) : '—'],
    ['ETag', entry.etag ?? '—'],
  ]
  return <Overlay title="Metadata inspector" drawer onClose={onClose}>
    <div className="dialog-header"><h2>Metadata inspector</h2><Button icon="close" className="icon-button" aria-label="Close inspector" onClick={onClose} /></div>
    <div className="drawer-body">
      <div className="inspector-title"><span className="icon-tile yellow"><Icon name={entryIcon(entry)} size={23} /></span><div><h3>{entryName(entry)}</h3><p>{entry.folder ? itemCount(entry.count ?? 0) : bytes(entry.bytes)} <span className="separator">·</span> {endpoint.name}</p></div></div>
      <div className="row-actions"><Badge>{endpoint.origin}</Badge>{!entry.folder && <Badge tone={statusTone[entry.state]} dot>{entry.state}</Badge>}{temperature && <Badge tone={temperatureTone[temperature]}>{temperature}</Badge>}</div>
      {!entry.folder && <div className="filters inspector-tabs" role="tablist" aria-label="File details">
        {(['metadata', 'lineage'] as const).map(item => <button id={'file-tab-' + item} key={item} type="button" role="tab"
          aria-selected={tab === item} aria-pressed={tab === item} aria-controls={'file-panel-' + item} tabIndex={tab === item ? 0 : -1}
          onClick={() => setTab(item)} onKeyDown={event => {
            if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
            event.preventDefault()
            const next = tab === 'metadata' ? 'lineage' : 'metadata'
            setTab(next)
            document.getElementById('file-tab-' + next)?.focus()
          }}>{item === 'metadata' ? 'Metadata' : 'Lineage'}</button>)}
      </div>}
      <div id="file-panel-metadata" role={entry.folder ? undefined : 'tabpanel'} aria-labelledby={entry.folder ? undefined : 'file-tab-metadata'} hidden={tab !== 'metadata'}>
        {entry.folder && <p className="metadata-scope">Folder metadata applies to all files inside, including subfolders.</p>}
        <MetadataEditor formId={metadataForm} initial={customMetadata} inherited={inherited} onSave={onSave} />
        <dl className="metadata">{details.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
      </div>
      {!entry.folder && <div id="file-panel-lineage" role="tabpanel" aria-labelledby="file-tab-lineage" hidden={tab !== 'lineage'}><Lineage entry={entry} /></div>}
    </div>
    <div className="dialog-footer">
      {entry.folder && <Button icon="folder" onClick={onOpen}>Open folder</Button>}
      {!entry.generated && <Button icon="upload" disabled={endpoint.origin === 'S3' || (!entry.folder && entry.state !== 'Local Only')} onClick={() => onAction('ARCHIVE')}>{entry.folder ? 'Archive folder' : 'Archive'}</Button>}
      {!entry.generated && <Button icon="download" disabled={!entry.folder && !['Archived', 'Purged Stubs'].includes(entry.state)} onClick={() => onAction('RECALL')}>{entry.folder ? 'Recall folder' : 'Recall'}</Button>}
      {tab === 'metadata' && <Button variant="primary" type="submit" form={metadataForm}>Save metadata</Button>}
    </div>
  </Overlay>
}

function OperationDialog({ operation, plan, endpoints, onClose, onSubmit }: {
  operation: Operation; plan: ReturnType<typeof resolveTransferSelection>; endpoints: Endpoint[]; onClose: () => void; onSubmit: (options: JobOptions) => void
}) {
  const { files, folders, folderPaths, skipped, copyEmpty } = plan
  const hasWork = files.length > 0 || copyEmpty
  const cloudEndpoints = endpoints.filter(endpoint => endpoint.origin === 'S3')
  const [bucketId, setBucketId] = useState(cloudEndpoints[0]?.id ?? '')
  const bucket = cloudEndpoints.find(endpoint => endpoint.id === bucketId)
  const [storageClass, setStorageClass] = useState<StorageClass>('STANDARD')
  const [purge, setPurge] = useState(false)
  const [destination, setDestination] = useState(endpoints.find(endpoint => endpoint.origin !== 'S3')?.path ?? '')
  const [retrieval, setRetrieval] = useState('Standard')
  const archive = operation === 'ARCHIVE'
  const recall = operation === 'RECALL'
  const validDestination = destination.trim().startsWith('/') && !destination.split('/').some(part => part.trim() === '..' || part.trim() === '.')
  const title = archive ? (folders.length ? 'Archive folder to S3' : 'Archive to S3')
    : recall ? (folders.length ? 'Recall folder from S3' : 'Recall to local storage') : 'Verify checksum / re-scan'
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!hasWork || (archive && !bucket) || (recall && !validDestination)) return
    if (archive) onSubmit({ bucket: bucket?.path.slice('s3://'.length), bucketId, storageClass, purge, folderPaths })
    else if (recall) onSubmit({ destination: destination.trim(), retrieval, folderPaths })
    else onSubmit({})
  }
  return <Overlay title={title} onClose={onClose}>
    <form onSubmit={submit}>
      <div className="dialog-header"><h2>{title}</h2><Button icon="close" className="icon-button" aria-label="Close confirmation" onClick={onClose} /></div>
      <div className="dialog-body">
        {folders.length > 0 && <p>Includes nested folders and files across all pages. Relative paths are preserved.</p>}
        {hasWork ? <p>{itemCount(files.length)} eligible · {bytes(files.reduce((sum, file) => sum + file.bytes, 0))}{copyEmpty && ' · Empty folders only'}</p>
          : <p className="empty compact">No items in this selection are eligible for this action.</p>}
        {skipped > 0 && <p>{itemCount(skipped)} skipped because of their current storage state.</p>}
        {archive && hasWork && <>
          <label>Target S3 bucket<select value={bucketId} onChange={event => setBucketId(event.target.value)}>{!cloudEndpoints.length && <option value="">No target buckets enabled</option>}{cloudEndpoints.map(endpoint => <option key={endpoint.id} value={endpoint.id}>{endpoint.name}</option>)}</select></label>
          <label>Storage class<select value={storageClass} onChange={event => setStorageClass(event.target.value as StorageClass)}>{Object.entries(storageName).map(([value, label]) => <option key={value} value={value}>{label} ({value})</option>)}</select></label>
          <label className="check-label"><input type="checkbox" checked={purge} onChange={event => setPurge(event.target.checked)} /> Purge local copy after verified upload</label>
        </>}
        {recall && hasWork && <>
          <label>Destination folder<input required pattern="/.*" value={destination} onChange={event => setDestination(event.target.value)} placeholder="/mnt/nas-01/restored" /></label>
          <label>Glacier retrieval speed<select value={retrieval} onChange={event => setRetrieval(event.target.value)}><option>Expedited</option><option>Standard</option><option>Bulk</option></select></label>
        </>}
      </div>
      <div className="dialog-footer"><Button onClick={onClose}>Cancel</Button><Button type="submit" variant="primary" disabled={!hasWork || (archive && !bucket) || (recall && !validDestination)}>{archive ? 'Queue archive' : recall ? 'Queue recall' : 'Queue verification'}</Button></div>
    </form>
  </Overlay>
}

function CatalogPagination({ page, size, setSize, goTo }: {
  page: CatalogPage; size: number; setSize: (size: number) => void; goTo: (offset: number) => void
}) {
  const last = Math.max(1, Math.ceil(page.total / size))
  const current = Math.floor(page.offset / size) + 1
  const [jump, setJump] = useState('')
  useEffect(() => { setJump('') }, [current, last, size])
  return <div className="catalog-pagination" aria-label="Catalog pagination">
    <span aria-live="polite">{page.total ? `${count(page.offset + 1)}–${count(page.offset + page.items.length)} of ${itemCount(page.total)}` : '0 items'}</span>
    <label>Per page<select value={size} onChange={event => setSize(Number(event.target.value))}>
      {[50, 100, 200].map(value => <option key={value}>{value}</option>)}
    </select></label>
    <div className="catalog-page-actions">
      <Button disabled={current === 1} onClick={() => goTo(page.offset - size)}>Previous</Button>
      {last > 1 ? <form onSubmit={event => {
        event.preventDefault()
        if (jump) {
          goTo((Math.max(1, Math.min(last, Math.floor(Number(jump)))) - 1) * size)
          setJump('')
        }
      }}>
        <label>Page<input aria-label="Go to page" type="number" min={1} max={last} value={jump} placeholder={String(current)}
          onChange={event => setJump(event.target.value)} /></label>
        <span>of {count(last)}</span><Button type="submit" disabled={!jump}>Go</Button>
      </form> : <span>Page 1 of 1</span>}
      <Button disabled={current === last} onClick={() => goTo(page.offset + size)}>Next</Button>
    </div>
  </div>
}

type InventoryProps = { workspace: Workspace; initialEndpoint: string | null; initialEntry: string | null; metadata: CatalogMetadata }

function CatalogInventory({ workspace, initialEndpoint, initialEntry, metadata }: InventoryProps) {
  const endpoints = workspace.endpoints
  const catalog = workspace.catalog
  const source = useMemo(() => createCatalogSource(catalog, metadata.store), [catalog, metadata.store])
  const entry = initialEntry ? source.entry(initialEntry) : undefined
  const [endpointId, setEndpointId] = useState(entry?.endpoint ?? initialEndpoint)
  const endpoint = endpoints.find(item => item.id === endpointId) ?? endpoints[0]
  const [path, setPath] = useState(entry ? (entry.folder ? entry.path : parentPath(entry.path)) : initialEndpoint || endpoint.id !== 'nas' ? '' : initialPath)
  const [width, setWidth] = useState(256)
  const [filter, setFilter] = useState('All')
  const [offset, setOffset] = useState(0)
  const [pageSize, setPageSize] = useState(100)
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [selection, setSelection] = useState<Set<string>>(new Set())
  const [inspectedId, setInspectedId] = useState(entry && !entry.folder ? entry.id : undefined)
  const [metadataTargets, setMetadataTargets] = useState<Entry[]>([])
  const [metadataNotice, setMetadataNotice] = useState('')
  const [action, setAction] = useState<{ operation: Operation; plan: ReturnType<typeof resolveTransferSelection> }>()
  const [fullscreen, setFullscreen] = useState(false)
  const explorer = useRef<HTMLDivElement>(null)
  const viewport = useRef<HTMLDivElement>(null)
  const drag = useRef<{ x: number; width: number } | null>(null)
  const inspected = inspectedId ? source.entry(inspectedId) : undefined
  const folder = source.entry(endpoint.id + ':' + path)

  useEffect(() => {
    const screenChanged = () => setFullscreen(document.fullscreenElement === explorer.current)
    document.addEventListener('fullscreenchange', screenChanged)
    return () => document.removeEventListener('fullscreenchange', screenChanged)
  }, [])

  function toggleFullscreen() {
    const node = explorer.current
    if (fullscreen) {
      if (document.fullscreenElement === node) void document.exitFullscreen().catch(() => {})
      else setFullscreen(false)
      return
    }
    setFullscreen(true)
    if (document.fullscreenEnabled) void node?.requestFullscreen?.().catch(() => {})
  }

  useEffect(() => {
    const timer = setTimeout(() => {
      const next = search.trim()
      if (next !== query) {
        setQuery(next)
        goToPage(0)
      }
    }, 220)
    return () => clearTimeout(timer)
  }, [search, query])

  const page = useMemo(() => source.list(endpoint.id, path, query, filter, offset, pageSize), [source, endpoint.id, path, query, filter, offset, pageSize])
  const items = page.items
  const list = useVirtualizer({ count: items.length, getScrollElement: () => viewport.current, estimateSize: () => 47,
    overscan: 8, getItemKey: index => items[index].id })
  useEffect(() => { viewport.current?.scrollTo(0, 0) }, [endpoint.id, path, query, filter, page.offset, pageSize])

  function goToPage(next: number) {
    setOffset(next)
    setSelection(new Set())
  }

  function go(next: Endpoint, nextPath: string) {
    setEndpointId(next.id)
    setPath(nextPath)
    goToPage(0)
    setMetadataNotice('')
    setSearch('')
    setQuery('')
    setFilter('All')
  }

  function toggle(id: string) {
    setSelection(previous => {
      const next = new Set(previous)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const allSelected = items.length > 0 && items.every(entry => selection.has(entry.id))
  const visibleSelected = items.some(entry => selection.has(entry.id))
  function selectAll() {
    setSelection(previous => {
      const next = new Set(previous)
      for (const item of items) {
        if (allSelected) next.delete(item.id)
        else next.add(item.id)
      }
      return next
    })
  }

  function openAction(operation: Operation, selected: Entry[]) {
    setInspectedId(undefined)
    setAction({ operation, plan: resolveTransferSelection(operation, selected, catalog, endpoints) })
  }

  const selectedEntries = items.filter(entry => selection.has(entry.id))
  const metadataFolders = metadataTargets.filter(entry => entry.folder).length
  const now = Date.now()
  const segments = path ? path.split('/') : []
  const resize = (size: number) => setWidth(Math.max(210, Math.min(360, size)))
  return <div ref={explorer} className={fullscreen ? 'explorer-fullscreen' : undefined} onKeyDown={event => {
    if (event.key === 'Escape' && fullscreen && !document.fullscreenElement && !explorer.current?.querySelector('dialog[open]')) {
      event.preventDefault()
      setFullscreen(false)
    }
  }}>
    <div className="page-heading"><div><h1>Catalog explorer</h1><p>Browse, inspect and move files across source servers and S3.</p></div>
      <Button icon={fullscreen ? 'minimize' : 'maximize'} aria-pressed={fullscreen} onClick={toggleFullscreen}>
        {fullscreen ? 'Exit full screen' : 'Full screen'}
      </Button>
    </div>
    <div className="panel explorer" style={{ gridTemplateColumns: `${width}px 7px minmax(0, 1fr)` }}>
      <CatalogTree source={source} endpoints={endpoints} active={endpoint} path={path} onNavigate={go} />
      <div className="tree-resizer" role="separator" aria-label="Resize storage catalog" aria-orientation="vertical" aria-valuemin={210} aria-valuemax={360} aria-valuenow={width} tabIndex={0}
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
            <button type="button" onClick={() => go(endpoint, '')}><Icon name={endpoint.origin === 'S3' ? 'cloud' : 'server'} size={16} />{endpoint.name}</button>
            {segments.map((segment, index) => <span key={segments.slice(0, index + 1).join('/')}><Icon name="chevron" size={13} />
              <button type="button" title={segment} aria-current={index === segments.length - 1 ? 'location' : undefined} onClick={() => go(endpoint, segments.slice(0, index + 1).join('/'))}><span className="breadcrumb-segment">{segment}</span></button>
            </span>)}
          </nav>
          <div className="catalog-title"><h2>{segments.at(-1) ?? endpoint.name}</h2><Badge>{endpoint.origin}</Badge>{folder?.generated && <Badge tone="blue">Sample catalog</Badge>}</div>
        </div>
        <div className="table-tools catalog-tools">
          <div className="filters" aria-label="Storage state filters">{filters.map(item => <button type="button" key={item} aria-pressed={filter === item} onClick={() => { setFilter(item); goToPage(0) }}>{item}</button>)}</div>
          <label className="search catalog-search"><Icon name="search" size={16} /><input aria-label="Search this location" placeholder="Search names, tags or properties" type="search" value={search} onChange={event => setSearch(event.target.value)} /></label>
        </div>
        <div ref={viewport} className="catalog-viewport">
          <div className="catalog-table" role="table" aria-label="Catalog metadata" aria-rowcount={page.total + 1}>
            <div className="catalog-table-header catalog-row" role="row" aria-rowindex={1}>
              <span role="columnheader"><CheckBox checked={allSelected} mixed={!allSelected && visibleSelected} label="Select all items on this page" disabled={!items.length} onChange={selectAll} /></span>
              <span role="columnheader">Name</span><span role="columnheader">Size</span><span role="columnheader">Storage state</span><span role="columnheader">Temperature</span><span role="columnheader">Last modified date</span><span role="columnheader">Checksum</span>
            </div>
            {items.length ? <div className="virtual-items" role="rowgroup" style={{ height: list.getTotalSize() }}>
              {list.getVirtualItems().map(row => {
                const entry = items[row.index]
                const checked = selection.has(entry.id)
                const temperature = entry.folder ? undefined : fileTemperature(entry.modified, now)?.temperature
                const indicator = entry.movements?.some(movement => movement.outcome === 'Completed') ? entry.movements.at(-1)?.checksum : undefined
                const label = entry.state === 'Purged Stubs' ? 'Purged · S3 Stub' : entry.storageClass && entry.state === 'Archived' ? 'Archived · ' + storageName[entry.storageClass] : entry.state
                return <div key={row.key} className={`catalog-row virtual-row ${checked ? 'checked' : ''}`} role="row" aria-rowindex={page.offset + row.index + 2}
                  style={{ transform: `translateY(${row.start}px)`, height: row.size }}
                  onClick={() => setInspectedId(entry.id)}>
                  <span role="cell" onClick={event => event.stopPropagation()}><CheckBox checked={checked} label={'Select ' + entryName(entry)} onChange={() => toggle(entry.id)} /></span>
                  <span role="cell" className="catalog-name"><button type="button" className="text-button" onClick={() => setInspectedId(entry.id)} title={entry.path}><Icon name={entryIcon(entry)} size={17} /><span>{entryName(entry)}</span></button>
                    {entry.folder && <Button className="icon-button folder-open" variant="quiet" icon="chevron" aria-label={'Open ' + entryName(entry)} onClick={event => { event.stopPropagation(); go(endpoint, entry.path) }} />}
                  </span>
                  <span role="cell">{entry.folder ? '—' : bytes(entry.bytes)}</span>
                  <span role="cell">{entry.folder ? <span className="muted">{itemCount(entry.count ?? 0)}</span> : <Badge tone={statusTone[entry.state]} dot>{label}</Badge>}</span>
                  <span role="cell">{temperature ? <Badge tone={temperatureTone[temperature]}>{temperature}</Badge> : '—'}</span>
                  <span role="cell">{date(entry.modified)}</span><span role="cell">{indicator ? <ChecksumIndicator result={indicator} /> : null}</span>
                </div>
              })}
            </div> : <div className="empty catalog-empty"><Icon name="folder" size={26} /><strong>No matching items</strong><span>Try a different search, filter or folder.</span></div>}
          </div>
        </div>
        <CatalogPagination page={page} size={pageSize}
          setSize={size => { setPageSize(size); goToPage(0) }} goTo={goToPage} />
      </section>
    </div>
    {selectedEntries.length > 0 && <div className="bulk-bar" aria-label="Selection actions">
      <strong>{count(selectedEntries.length)} selected on this page</strong><Button variant="quiet" icon="close" aria-label="Clear selection" className="icon-button" onClick={() => setSelection(new Set())} /><span className="bar-divider" />
      <Button variant="primary" icon="layers" onClick={() => { setMetadataNotice(''); setMetadataTargets(selectedEntries) }}>Edit metadata</Button>
      {selectedEntries.every(entry => !entry.generated) && <>
        <Button icon="upload" onClick={() => openAction('ARCHIVE', selectedEntries)}>{selectedEntries.some(entry => entry.folder) ? 'Archive folder' : 'Archive to S3'}</Button>
        <Button icon="download" onClick={() => openAction('RECALL', selectedEntries)}>{selectedEntries.some(entry => entry.folder) ? 'Recall folder' : 'Recall'}</Button>
        <Button icon="check" onClick={() => openAction('VERIFY', selectedEntries)}>Verify / re-scan</Button>
      </>}
    </div>}
    {metadataNotice && <p role="status" className="catalog-metadata-notice">{metadataNotice}</p>}
    {metadataTargets.length > 0 && <Overlay title="Edit selected metadata" onClose={() => setMetadataTargets([])}>
      <div className="dialog-header"><h2>Edit selected metadata</h2><Button icon="close" className="icon-button" aria-label="Close metadata editor" onClick={() => setMetadataTargets([])} /></div>
      <div className="dialog-body">
        <p>{count(metadataTargets.length)} selected {metadataTargets.length === 1 ? 'item' : 'items'}. Tags are added and properties with matching names are updated.</p>
        {metadataFolders > 0 && <p>Folder changes apply to every nested file across all pages.</p>}
        <MetadataEditor bulk onSave={values => {
          if (!metadata.save(metadataTargets, values, 'merge')) return false
          setMetadataTargets([])
          setSelection(new Set())
          setMetadataNotice(metadataFolders ? 'Metadata saved for the selection and its nested files.' : 'Metadata saved for the selected files.')
          return true
        }} />
      </div>
    </Overlay>}
    {inspected && <Inspector key={inspected.id} entry={inspected} endpoint={endpoint}
      customMetadata={metadataForEntry(metadata.store, inspected)} inherited={inheritedMetadata(metadata.store, inspected)}
      onSave={values => metadata.save([inspected], values, 'replace')} onClose={() => setInspectedId(undefined)} onOpen={() => { go(endpoint, inspected.path); setInspectedId(undefined) }}
      onAction={operation => openAction(operation, [inspected])} />}
    {action && <OperationDialog operation={action.operation} endpoints={endpoints} plan={action.plan} onClose={() => setAction(undefined)} onSubmit={options => {
      workspace.run(action.operation, action.plan.files, options)
      setAction(undefined)
      setSelection(new Set())
    }} />}
  </div>
}

export default function Inventory(props: InventoryProps) {
  if (!props.workspace.endpoints.length) return <section className="panel empty">No storage endpoints are enabled.</section>
  return <CatalogInventory {...props} />
}
