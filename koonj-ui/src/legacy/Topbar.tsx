import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import AlertsList, { activityHref, alertsForJobs } from './AlertsList'
import { adminSections } from './Admin'
import { activityLabel } from './AdminActivity'
import { createCatalogSource } from './catalogSource'
import { metadataSearchText, type MetadataStore } from './catalogMetadata'
import type { Navigate } from './Home'
import { demoOperatorName, entryName } from './sampleData'
import { Badge, Button, Icon, type IconName } from '../shared/ui/primitives'
import type { Workspace } from './workspace'

export const adminPath = '/admin'
export const storagePage = { path: '/storage', title: 'Storage status', shortTitle: 'Storage', icon: 'drive' } as const
export const adminPage = { path: adminPath, title: 'Administration', shortTitle: 'Admin', icon: 'layers' } as const
export const pages = [
  { path: '/Home', title: 'Home', icon: 'home' },
  { path: '/explorer', title: 'Catalog explorer', shortTitle: 'Explorer', icon: 'folder' },
  { path: '/activities', title: 'Activity log', shortTitle: 'Activity', icon: 'activity' },
  storagePage,
  adminPage,
] as const

export const profiles = {
  operator: { name: demoOperatorName, roles: ['Storage operator', 'Activity viewer'] },
  admin: { name: 'Demo admin', roles: ['Administrator'] },
  guest: { name: 'Guest', roles: [] },
} as const
export type Profile = keyof typeof profiles

export const themes = [
  { id: 'classic', label: 'Classic', background: '#fcfcfc', accent: '#ffde59' },
  { id: 'professional', label: 'Professional', background: '#fff', accent: '#172f59' },
  { id: 'light', label: 'Light', background: '#fff', accent: '#e9edf2' },
  { id: 'dark', label: 'Dark', background: '#18181b', accent: '#ffde59' },
  { id: 'sepia', label: 'Sepia', background: '#f3eee3', accent: '#dfc188' },
] as const
export type Theme = typeof themes[number]['id']

type Result = { title: string; detail: string; icon: IconName; href: string; keywords?: string }
type ResultsGroup = { title: string; results: Result[] }

function searchWorkspace({ catalog, endpoints, jobs }: Workspace, query: string, operationsOnly: boolean, metadata: MetadataStore): ResultsGroup[] {
  const availablePages = operationsOnly ? [...adminSections.map(section => ({ ...section,
    path: section.id === 'overview' ? adminPath : adminPath + '?section=' + section.id,
  })), storagePage] : pages
  const groups: ResultsGroup[] = [{ title: 'Pages', results: availablePages.map(page => ({
    title: page.title, icon: page.icon, detail: 'Workspace', href: page.path,
  })) }]
  const term = query.trim().toLowerCase()
  if (!term) return groups
  if (!operationsOnly) groups.push(
    { title: 'Storage endpoints', results: endpoints.map(endpoint => ({
      title: endpoint.name, detail: endpoint.path, icon: endpoint.origin === 'S3' ? 'cloud' : 'server',
      href: '/explorer?' + new URLSearchParams({ endpoint: endpoint.id }), keywords: endpoint.origin,
    })) },
    { title: 'Files and folders', results: createCatalogSource(catalog, metadata).searchableEntries.map(entry => ({
      title: entryName(entry), detail: (endpoints.find(endpoint => endpoint.id === entry.endpoint)?.name ?? entry.endpoint) + ' / ' + entry.path,
      icon: entry.folder ? 'folder' : 'file', href: '/explorer?' + new URLSearchParams({ entry: entry.id }), keywords: metadataSearchText(metadata, entry),
    })) },
  )
  groups.push({ title: 'Activities', results: jobs.map(job => ({
    title: operationsOnly ? activityLabel(job) : job.title, detail: job.id + ' · ' + (operationsOnly ? job.status : job.target), icon: 'activity',
    href: activityHref(job.id, operationsOnly), keywords: [job.operation, job.status, job.actor].join(' '),
  })) })
  function score(result: Result) {
    const title = result.title.toLowerCase()
    if (title === term) return 0
    if (title.startsWith(term)) return 1
    if (title.includes(term)) return 2
    return term.split(/\s+/).every(word => (result.title + ' ' + result.detail + ' ' + (result.keywords ?? '')).toLowerCase().includes(word)) ? 3 : Infinity
  }
  return groups.map(group => ({
    ...group, results: group.results.map(result => ({ result, score: score(result) }))
      .filter(match => Number.isFinite(match.score)).sort((left, right) => left.score - right.score)
      .slice(0, 6).map(match => match.result),
  })).filter(group => group.results.length > 0)
}

function GlobalSearch({ workspace, navigate, operationsOnly, metadata }: {
  workspace: Workspace; navigate: Navigate; operationsOnly: boolean; metadata: MetadataStore
}) {
  const id = useId()
  const input = useRef<HTMLInputElement>(null)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState(-1)
  const groups = open ? searchWorkspace(workspace, query, operationsOnly, metadata) : []
  const results = groups.flatMap(group => group.results)
  const active = results[selected]

  useEffect(() => {
    const shortcut = (event: globalThis.KeyboardEvent) => {
      if (event.key.toLowerCase() === 'k' && (event.metaKey || event.ctrlKey) && !document.querySelector('dialog[open]')) {
        event.preventDefault()
        input.current?.focus()
        setOpen(true)
      }
    }
    window.addEventListener('keydown', shortcut)
    return () => window.removeEventListener('keydown', shortcut)
  }, [])

  useEffect(() => {
    if (active) document.getElementById(id + '-' + selected)?.scrollIntoView({ block: 'nearest' })
  }, [active?.href, id, selected])

  function choose(result: Result) {
    setOpen(false)
    setQuery('')
    setSelected(-1)
    input.current?.blur()
    document.getElementById('main-content')?.focus({ preventScroll: true })
    navigate(result.href)
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape' && open) {
      event.preventDefault()
      setOpen(false)
      setSelected(-1)
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setOpen(true)
      if (results.length) setSelected(event.key === 'ArrowDown'
        ? (selected + 1) % results.length : selected <= 0 ? results.length - 1 : selected - 1)
    }
    if (open && event.key === 'Enter' && (active ?? results[0])) {
      event.preventDefault()
      choose(active ?? results[0])
    }
  }

  return <div className="global-search">
    <div className="search global-search-field">
      <Icon name="search" size={16} />
      <input ref={input} type="search" role="combobox" aria-label="Search workspace" aria-expanded={open}
        aria-controls={open ? id : undefined} aria-autocomplete="list" aria-keyshortcuts="Control+K Meta+K"
        aria-activedescendant={open && active ? id + '-' + selected : undefined}
        placeholder="Search workspace…" value={query} onFocus={() => setOpen(true)} onClick={() => setOpen(true)}
        onBlur={() => { setOpen(false); setSelected(-1) }} onKeyDown={onKeyDown}
        onChange={event => { setQuery(event.target.value); setOpen(true); setSelected(-1) }} />
      {!query && <kbd aria-hidden="true">{/Mac/.test(navigator.platform) ? '⌘ K' : 'Ctrl K'}</kbd>}
    </div>
    {open && <div className="topbar-popover search-popover">
      <div id={id} role="listbox" aria-label="Workspace search results">
        {groups.map(group => <div key={group.title} role="group" aria-label={group.title}>
          <div className="popover-label" aria-hidden="true">{group.title}</div>
          {group.results.map(result => {
            const index = results.indexOf(result)
            return <div key={result.href} id={id + '-' + index} role="option" aria-selected={selected === index} className="search-result"
              onMouseDown={event => event.preventDefault()} onClick={() => choose(result)}>
              <Icon name={result.icon} size={17} /><span><strong>{result.title}</strong><small>{result.detail}</small></span>
              <Icon name="arrow" size={14} />
            </div>
          })}
        </div>)}
      </div>
      {!results.length && <p className="search-no-results" role="status">No workspace results found.</p>}
    </div>}
  </div>
}

function ToolbarPopover({ label, triggerContent, triggerClass, panelClass, children }: {
  label: string; triggerContent: ReactNode; triggerClass: string; panelClass: string; children: (close: () => void) => ReactNode
}) {
  const [open, setOpen] = useState(false)
  const menu = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const id = useId()

  useEffect(() => {
    if (!open) return
    function outside(event: PointerEvent) {
      if (event.target instanceof Node && !menu.current?.contains(event.target)) setOpen(false)
    }
    document.addEventListener('pointerdown', outside)
    return () => document.removeEventListener('pointerdown', outside)
  }, [open])

  function close() {
    setOpen(false)
    trigger.current?.focus()
  }

  return <div className="toolbar-disclosure" ref={menu} onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false)
  }} onKeyDown={event => {
    if (event.key === 'Escape' && open) { event.preventDefault(); close() }
  }}>
    <button ref={trigger} className={triggerClass} type="button" aria-label={label} title={label}
      aria-expanded={open} aria-controls={open ? id : undefined} onClick={() => setOpen(!open)}>
      {triggerContent}
    </button>
    {open && <section id={id} className={'topbar-popover ' + panelClass} aria-label={label}>{children(close)}</section>}
  </div>
}

function ThemePicker({ theme, onChange }: { theme: Theme; onChange: (theme: Theme) => void }) {
  const [open, setOpen] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)
  const id = useId()
  const current = themes.find(option => option.id === theme) ?? themes[0]

  function close() {
    trigger.current?.focus()
    setOpen(false)
  }

  return <div className="theme-picker" onKeyDown={event => {
    if (event.key !== 'Escape' || !open) return
    event.preventDefault()
    event.stopPropagation()
    close()
  }}>
    <button ref={trigger} className="button theme-trigger" type="button" aria-expanded={open} aria-controls={open ? id : undefined}
      onClick={() => setOpen(!open)}>
      <span>Theme</span><span className="current-theme">{current.label}</span><Icon name="down" size={13} />
    </button>
    {open && <div id={id} className="theme-options" role="group" aria-label="Choose a theme">
      {themes.map(option => <button type="button" className="theme-option" key={option.id} aria-pressed={theme === option.id}
        onClick={() => { onChange(option.id); close() }}>
        <span className="theme-swatch" aria-hidden="true" style={{ background: `linear-gradient(135deg, ${option.background} 50%, ${option.accent} 50%)` }} />
        <span>{option.label}</span>
        {theme === option.id && <Icon name="check" size={12} />}
      </button>)}
    </div>}
  </div>
}

function ProfileMenu({ profile, onProfileChange, theme, onThemeChange }: {
  profile: Profile; onProfileChange: (profile: Profile) => void; theme: Theme; onThemeChange: (theme: Theme) => void
}) {
  const user = profiles[profile]
  return <ToolbarPopover label="User profile" triggerClass="profile-trigger" panelClass="profile-popover"
    triggerContent={<><span className="profile-avatar"><Icon name="user" size={18} /></span>
      <span className="profile-name">{user.name}</span><Icon name="down" size={13} /></>}>
    {close => <>
      <strong>{user.name}</strong>
      <div className="profile-roles">
        <span className="popover-label">Workspace roles</span>
        {user.roles.length ? <div>{user.roles.map(role => <Badge key={role} tone="yellow">{role}</Badge>)}</div>
          : <p>No roles assigned</p>}
      </div>
      <ThemePicker theme={theme} onChange={onThemeChange} />
      {profile === 'guest' && <Button variant="quiet" icon="user" onClick={() => { onProfileChange('operator'); close() }}>Resume demo profile</Button>}
      {profile !== 'guest' && <Button variant="quiet" icon="logout" onClick={() => { onProfileChange('guest'); close() }}>Sign out</Button>}
    </>}
  </ToolbarPopover>
}

export default function Topbar({ title, workspace, navigate, profile, onProfileChange, theme, onThemeChange, operationsOnly, metadata }: {
  title: string; workspace: Workspace; navigate: Navigate; profile: Profile; onProfileChange: (profile: Profile) => void
  theme: Theme; onThemeChange: (theme: Theme) => void; operationsOnly: boolean; metadata: MetadataStore
}) {
  const alerts = alertsForJobs(operationsOnly ? workspace.jobs : workspace.personalJobs)
  return <header className="topbar">
    <div className="topbar-path"><span>Workspace</span><Icon name="chevron" size={14} /><strong>{title}</strong></div>
    <GlobalSearch key={profile + operationsOnly} workspace={workspace} navigate={navigate} operationsOnly={operationsOnly} metadata={metadata} />
    <div className="topbar-actions">
      <ToolbarPopover label={`Notifications (${alerts.length})`} triggerClass="alerts-trigger" panelClass="alerts-popover"
        triggerContent={<><Icon name="bell" size={19} />{alerts.length > 0 && <span className="alerts-badge" aria-hidden="true">{alerts.length}</span>}</>}>
        {close => <><h2>Notifications</h2><AlertsList items={alerts} operationsOnly={operationsOnly} navigate={path => { close(); navigate(path) }} /></>}
      </ToolbarPopover>
      <ProfileMenu profile={profile} onProfileChange={onProfileChange} theme={theme} onThemeChange={onThemeChange} />
    </div>
  </header>
}
