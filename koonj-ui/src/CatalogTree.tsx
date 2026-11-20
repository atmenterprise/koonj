import { useEffect, useState } from 'react'
import { folderPageSize, type CatalogSource } from './catalogSource'
import { entryName, type Endpoint, type Entry } from './sampleData'
import { Button, Icon, count } from './ui'

const openBranchLimit = 12
type Branch = { offset: number; query: string }
const defaultBranch: Branch = { offset: 0, query: '' }
const keyFor = (endpoint: Endpoint, path: string) => endpoint.id + ':' + path

function pathSegments(path: string) {
  const parts = path ? path.split('/') : []
  return ['', ...parts.map((_, index) => parts.slice(0, index + 1).join('/'))]
}

export default function CatalogTree({ source, endpoints, active, path, onNavigate }: {
  source: CatalogSource; endpoints: Endpoint[]; active: Endpoint; path: string; onNavigate: (endpoint: Endpoint, path: string) => void
}) {
  const sourceEndpoints = endpoints.filter(endpoint => endpoint.origin !== 'S3')
  const cloudEndpoints = endpoints.filter(endpoint => endpoint.origin === 'S3')
  const [sections, setSections] = useState({ sources: true, cloud: true })
  const [branches, setBranches] = useState<Map<string, Branch>>(new Map())

  function limitBranches(open: Map<string, Branch>, endpoint: Endpoint, selectedPath: string) {
    const protectedKeys = new Set(pathSegments(selectedPath).map(item => keyFor(endpoint, item)))
    let extra = open.size - [...open.keys()].filter(key => protectedKeys.has(key)).length
    for (const key of open.keys()) {
      if (extra <= openBranchLimit) break
      if (!protectedKeys.has(key)) { open.delete(key); extra-- }
    }
    return open
  }

  useEffect(() => {
    const ancestors = pathSegments(path)
    setBranches(previous => {
      const next = new Map(previous)
      ancestors.forEach((ancestor, index) => {
        const child = ancestors[index + 1]
        next.set(keyFor(active, ancestor), child ? { offset: source.folderPage(active.id, ancestor, child), query: '' } : defaultBranch)
      })
      return limitBranches(next, active, path)
    })
    setSections(previous => ({ ...previous, [active.origin === 'S3' ? 'cloud' : 'sources']: true }))
    // Refresh only when the location changes, so live job updates preserve manual tree navigation.
  }, [active.id, path])

  function setBranch(id: string, value: Branch | null) {
    setBranches(previous => {
      const next = new Map(previous)
      next.delete(id)
      if (value) next.set(id, value)
      return limitBranches(next, active, path)
    })
  }

  function branch(endpoint: Endpoint, entry?: Entry, depth = 0) {
    const branchPath = entry?.path ?? ''
    const id = keyFor(endpoint, branchPath)
    const expanded = branches.get(id)
    const expandable = source.hasFolders(endpoint.id, branchPath)
    const selected = endpoint.id === active.id && branchPath === path
    const children = expanded ? source.folders(endpoint.id, branchPath, expanded.query.trim().toLowerCase(), expanded.offset) : undefined
    const title = entry ? entryName(entry) : endpoint.name
    const indent = 6 + Math.min(depth, 5) * 11
    return <li key={id}>
      <div className={`tree-item ${selected ? 'selected' : ''}`} style={{ paddingInlineStart: indent }}>
        {expandable ? <button type="button" className="tree-toggle" aria-expanded={Boolean(expanded)}
          aria-label={(expanded ? 'Collapse ' : 'Expand ') + title}
          onClick={() => setBranch(id, expanded ? null : defaultBranch)}><Icon name={expanded ? 'down' : 'chevron'} size={13} /></button> : <span className="tree-toggle" aria-hidden="true" />}
        <button type="button" className="tree-name" aria-current={selected ? 'location' : undefined}
          onClick={() => { setBranch(id, expanded ?? defaultBranch); onNavigate(endpoint, branchPath) }}>
          <Icon name={entry ? 'folder' : endpoint.origin === 'S3' ? 'cloud' : 'server'} size={16} /><span title={title}>{title}</span>
          <small>{entry ? count(entry.count ?? 0) : endpoint.origin}</small>
        </button>
      </div>
      {expanded && children && (children.total > folderPageSize || expanded.query) && <div className="tree-page-tools" style={{ marginInlineStart: indent + 12 }}>
        <input aria-label={'Find subfolders in ' + title} type="search" placeholder="Find subfolders…" value={expanded.query}
          onChange={event => setBranch(id, { query: event.target.value, offset: 0 })} />
        <div className="tree-page-nav">
          <Button aria-label={'Previous folders in ' + title} disabled={children.offset === 0} onClick={() => setBranch(id, { ...expanded, offset: children.offset - folderPageSize })}>‹</Button>
          <span>{children.total ? `${count(children.offset + 1)}–${count(children.offset + children.items.length)} of ${count(children.total)}` : 'No matching folders'}</span>
          <Button aria-label={'Next folders in ' + title} disabled={children.offset + children.items.length >= children.total} onClick={() => setBranch(id, { ...expanded, offset: children.offset + folderPageSize })}>›</Button>
        </div>
      </div>}
      {children && children.items.length > 0 && <ul>{children.items.map(item => branch(endpoint, item, depth + 1))}</ul>}
    </li>
  }

  return <aside className="catalog-tree" aria-label="Storage catalog">
    <div className="tree-header"><Icon name="layers" size={17} /><h2>Storage catalog</h2></div>
    {([{ id: 'sources', label: 'Source servers', items: sourceEndpoints }, { id: 'cloud', label: 'Cloud targets', items: cloudEndpoints }] as const).map(section => <div className="tree-section" key={section.id}>
      <button type="button" className="tree-section-label" aria-expanded={sections[section.id]} onClick={() => setSections({ ...sections, [section.id]: !sections[section.id] })}>
        <Icon name={sections[section.id] ? 'down' : 'chevron'} size={13} /><span>{section.label}</span><small>{section.items.length}</small>
      </button>
      {sections[section.id] && <ul>{section.items.map(endpoint => branch(endpoint))}</ul>}
    </div>)}
  </aside>
}
