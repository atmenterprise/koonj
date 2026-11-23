import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { useCatalogSnapshot, useCatalogLocations, useTransferJobs } from '../api/hooks/queries'
import { jobLabels, operationLabels } from '../api/mappers'
import type { Project } from '../api/services/projectService'
import type { TransferJobDTO } from '../api/types'
import { useActivityNotifications } from '../shared/notifications/ActivityNotifications'
import { notificationPresentation } from '../shared/notifications/NotificationPopup'
import { useAuth } from '../auth/AuthProvider'
import { usePermissions } from '../auth/WorkspacePermissions'
import type { KoonjRole } from '../auth/keycloak'
import type { Navigate } from '../shared/ui/feedback'
import { useQueries } from '@tanstack/react-query'
import { catalogMetadataOptions } from '../modules/catalog/catalogMetadata'
import { catalogSearchResults } from '../modules/catalog/catalogSearchResults'
import { Badge, Button, Icon, type IconName } from '../shared/ui/primitives'

type SearchPage = { path: string; title: string; icon: IconName }
type Result = { title: string; detail: string; icon: IconName; href: string }

const roleLabels: Record<KoonjRole, string> = {
  KOONJ_ADMIN: 'Admin',
  KOONJ_OPERATOR: 'Operator',
  KOONJ_VIEWER: 'Viewer',
}

export const themes = [
  { id: 'classic', label: 'Classic', background: '#fcfcfc', accent: '#ffde59' },
  { id: 'professional', label: 'Professional', background: '#fff', accent: '#172f59' },
  { id: 'light', label: 'Light', background: '#fff', accent: '#e9edf2' },
  { id: 'dark', label: 'Dark', background: '#18181b', accent: '#ffde59' },
  { id: 'sepia', label: 'Sepia', background: '#f3eee3', accent: '#dfc188' },
] as const
export type Theme = typeof themes[number]['id']

function GlobalSearch({ pages, navigate, jobs }: { pages: SearchPage[]; navigate: Navigate; jobs: TransferJobDTO[] }) {
  const id = useId()
  const input = useRef<HTMLInputElement>(null)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState(-1)
  const term = query.trim().replace(/^\/+/, '').toLowerCase()
  const { can } = usePermissions()
  const browseCatalog = can('CATALOG_VIEW')
  const searching = open && !!term
  const locations = useCatalogLocations(browseCatalog)
  const files = useCatalogSnapshot(undefined, searching && browseCatalog)
  const endpoints = browseCatalog ? locations.data ?? [] : []
  const metadata = useQueries({
    queries: searching ? endpoints.map(endpoint => catalogMetadataOptions(endpoint.id)) : [],
    combine: queries => ({
      entries: queries.map(result => result.data),
      loading: queries.some(result => result.isPending),
      failed: queries.some(result => result.isError),
    }),
  })
  const catalog = useMemo(() => {
    if (!searching || !browseCatalog) return { files: [], folders: [] }
    const entries = new Map(endpoints.flatMap((endpoint, index) => {
      const values = metadata.entries[index]
      return values ? [[endpoint.id, values] as const] : []
    }))
    return catalogSearchResults(files.data ?? [], endpoints, entries, value => value.toLowerCase().includes(term))
  }, [searching, browseCatalog, endpoints, files.data, metadata.entries, term])
  const match = (value: string) => value.toLowerCase().includes(term)
  const groups = open ? [
    { title: 'Workspace pages', results: pages.filter(page => match(page.title + ' Workspace Koonj')).map(page => ({
      title: page.title, detail: 'Workspace', icon: page.icon, href: page.path,
    })) },
    ...(searching ? [
      { title: 'Storage endpoints', results: endpoints.filter(location => match(
        [location.name, location.id, location.providerType, location.bucket, location.role].join(' ')))
        .map(location => ({ title: location.name, detail: location.providerType, icon: 'server' as const,
          href: '/explorer?' + new URLSearchParams({ endpoint: location.id }) })) },
      { title: 'Folders', results: catalog.folders },
      { title: 'Files', results: catalog.files },
      { title: 'Activities', results: jobs.filter(job => [job.id, operationLabels[job.operation], jobLabels[job.status],
        job.details ?? '', job.triggeredBy ?? '', ...job.requestedPaths].some(match))
        .map(job => ({ title: operationLabels[job.operation], detail: job.id, icon: 'activity' as const,
          href: '/activities?' + new URLSearchParams({ job: job.id }) })) },
    ] : []),
  ].filter(group => group.results.length) : []
  const resultLimit = 25
  const visibleGroups = groups.map(group => ({ ...group, results: group.results.slice(0, resultLimit) }))
  const results = visibleGroups.flatMap(group => group.results)
  const loading = searching && browseCatalog && (locations.isPending || files.isPending || metadata.loading)
  const incomplete = searching && browseCatalog && (locations.isError || files.isError || metadata.failed)
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
        placeholder="Search files, metadata, workspace pages…" value={query} onFocus={() => setOpen(true)} onClick={() => setOpen(true)}
        onBlur={() => { setOpen(false); setSelected(-1) }} onKeyDown={onKeyDown}
        onChange={event => { setQuery(event.target.value); setOpen(true); setSelected(-1) }} />
      {!query && <kbd aria-hidden="true">{/Mac/.test(navigator.platform) ? '⌘ K' : 'Ctrl K'}</kbd>}
    </div>
    {open && <div className="topbar-popover search-popover">
      <div id={id} role="listbox" aria-label="Workspace search results">
        {visibleGroups.map(group => <div key={group.title} role="group" aria-label={group.title}>
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
      {loading && <p className="search-no-results" role="status">Searching the catalog…</p>}
      {!loading && !incomplete && !results.length && <p className="search-no-results" role="status">No matches found.</p>}
      {groups.some(group => group.results.length > resultLimit) &&
        <p className="search-no-results">Showing the first {resultLimit} matches per category. Refine your search to narrow the results.</p>}
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

function ProfileMenu({ theme, onThemeChange }: { theme: Theme; onThemeChange: (theme: Theme) => void }) {
  const { user, logout } = useAuth()
  const { notify } = useActivityNotifications()
  const [pending, setPending] = useState(false)
  if (!user) return null
  return <ToolbarPopover label="User profile" triggerClass="profile-trigger" panelClass="profile-popover"
    triggerContent={<><span className="profile-avatar"><Icon name="user" size={18} /></span>
      <span className="profile-name">{user.name}</span><Icon name="down" size={13} /></>}>
    {() => <>
      <strong>{user.name}</strong>
      {user.email && <p>{user.email}</p>}
      <div className="profile-roles">
        <span className="popover-label">Workspace roles</span>
        {user.roles.length ? <div>{user.roles.map(role => <Badge key={role} tone="yellow">{roleLabels[role]}</Badge>)}</div>
          : <p>No roles assigned</p>}
      </div>
      <ThemePicker theme={theme} onChange={onThemeChange} />
      <Button variant="quiet" icon="logout" disabled={pending} onClick={() => {
        setPending(true)
        void logout().catch(() => notify({ tone: 'error', title: 'Logout failed', detail: 'Try again.' }))
          .finally(() => setPending(false))
      }}>Logout</Button>
    </>}
  </ToolbarPopover>
}

export function Topbar({ title, pages, navigate, theme, onThemeChange, canViewActivity, project, projects, onProjectSwitch }: {
  title: string; pages: SearchPage[]; navigate: Navigate; theme: Theme; onThemeChange: (theme: Theme) => void; canViewActivity: boolean
  project: Project; projects: Project[]; onProjectSwitch: (id: string) => void
}) {
  const jobHistory = useTransferJobs(canViewActivity)
  const { notices, markRead, syncJobs } = useActivityNotifications()
  useEffect(() => {
    if (canViewActivity && jobHistory.data) syncJobs(jobHistory.data)
  }, [canViewActivity, jobHistory.data, syncJobs])
  const jobs = canViewActivity ? jobHistory.data ?? [] : []
  const unread = notices.filter(notice => !notice.read).length
  const alerts = notices.slice().reverse()
  return <header className="topbar">
    <nav className="topbar-path" aria-label="Breadcrumb">
      <span title={project.name}>{project.name}</span><Icon name="chevron" size={14} />
      <strong aria-current="page">{title}</strong>
    </nav>
    <GlobalSearch pages={pages} navigate={navigate} jobs={jobs} />
    <select className="topbar-project" aria-label="Active project" title={project.name} value={project.id}
      onChange={event => onProjectSwitch(event.target.value)}>
      {projects.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
    </select>
    <div className="topbar-actions">
      <ToolbarPopover label={`Notifications (${unread} unread)`} triggerClass="alerts-trigger" panelClass="alerts-popover"
        triggerContent={<><Icon name="bell" size={19} />{unread > 0 && <span className="alerts-badge" aria-hidden="true">{unread}</span>}</>}>
        {close => <><h2>Notifications</h2><div className="notifications">
          {alerts.length ? alerts.map(notice => {
            const { icon, tile, label } = notificationPresentation[notice.tone]
            const jobId = canViewActivity ? notice.jobId : undefined
            const detailId = notice.detail ? `notification-detail-${notice.id}` : undefined
            return <button type="button" className={notice.read ? 'notification' : 'notification notification-unread'} key={notice.id}
              aria-label={`${notice.read ? '' : 'Unread. '}${label}: ${notice.title}. ${jobId ? 'View activity' : 'View notification'}`}
              aria-describedby={detailId}
              onClick={() => {
                markRead(notice.id, !jobId)
                close()
                if (jobId) navigate('/activities?' + new URLSearchParams({ job: jobId }))
              }}>
              <span className={`icon-tile ${tile}`}><Icon name={icon} size={16} /></span>
              <span className="notification-text"><strong>{notice.title}</strong>
                {notice.detail && <small id={detailId}>{notice.detail}</small>}</span>
              <Icon name={jobId ? 'chevron' : 'external'} size={14} />
            </button>
          }) : <p className="empty compact">No notifications yet.</p>}
        </div></>}
      </ToolbarPopover>
      <ProfileMenu theme={theme} onThemeChange={onThemeChange} />
    </div>
  </header>
}
