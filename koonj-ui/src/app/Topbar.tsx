import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { useCatalogFiles, useCatalogLocations, useTransferJobs } from '../api/hooks/queries'
import { jobLabels, operationLabels } from '../api/mappers'
import type { TransferJobDTO } from '../api/types'
import { useActivityNotifications } from '../shared/notifications/ActivityNotifications'
import { useAuth } from '../auth/AuthProvider'
import type { Navigate } from '../shared/ui/feedback'
import { Badge, Button, Icon, type IconName } from '../shared/ui/primitives'

type SearchPage = { path: string; title: string; icon: IconName }
type Result = { title: string; detail: string; icon: IconName; href: string }

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
  const term = query.trim()
  const locations = useCatalogLocations()
  const searchPrefix = term.replace(/^\/+/, '')
  const files = useCatalogFiles({ pathPrefix: searchPrefix, size: 6 }, open && searchPrefix.length > 1)
  const match = (value: string) => value.toLowerCase().includes(term.toLowerCase())
  const groups = open ? [
    { title: 'Pages', results: pages.filter(page => match(page.title)).map(page => ({
      title: page.title, detail: 'Workspace', icon: page.icon, href: page.path,
    })) },
    ...(term ? [
      { title: 'Storage endpoints', results: (locations.data ?? []).filter(location => match(location.name))
        .slice(0, 6).map(location => ({ title: location.name, detail: location.providerType, icon: 'server' as const,
          href: '/explorer?' + new URLSearchParams({ endpoint: location.id }) })) },
      { title: 'Files', results: (files.data?.content ?? []).map(({ file }) => ({
        title: file.fileName, detail: file.relativePath, icon: 'file' as const,
        href: '/explorer?' + new URLSearchParams({ endpoint: file.sourceConnectionId, entry: file.id, path: file.relativePath }),
      })) },
      { title: 'Activities', results: jobs.filter(job => match(job.id + ' ' + operationLabels[job.operation] + ' ' + jobLabels[job.status]))
        .slice(0, 6).map(job => ({ title: operationLabels[job.operation], detail: job.id, icon: 'activity' as const,
          href: '/activities?' + new URLSearchParams({ job: job.id }) })) },
    ] : []),
  ].filter(group => group.results.length) : []
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

function ProfileMenu({ theme, onThemeChange }: { theme: Theme; onThemeChange: (theme: Theme) => void }) {
  const { user, logout, hasRole } = useAuth()
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)
  if (!user) return null
  const role = hasRole('KOONJ_ADMIN') ? 'Admin' : hasRole('KOONJ_OPERATOR') ? 'Operator' : 'Viewer'
  return <ToolbarPopover label="User profile" triggerClass="profile-trigger" panelClass="profile-popover"
    triggerContent={<><span className="profile-avatar"><Icon name="user" size={18} /></span>
      <span className="profile-name">{user.name}</span><Icon name="down" size={13} /></>}>
    {() => <>
      <strong>{user.name}</strong>
      {user.email && <p>{user.email}</p>}
      <div className="profile-roles"><span className="popover-label">Workspace role</span><div><Badge tone="yellow">{role}</Badge></div></div>
      <ThemePicker theme={theme} onChange={onThemeChange} />
      <Button variant="quiet" icon="logout" disabled={pending} onClick={() => {
        setPending(true)
        setFailed(false)
        void logout().catch(() => { setPending(false); setFailed(true) })
      }}>Logout</Button>
      {failed && <p role="alert">Logout failed. Try again.</p>}
    </>}
  </ToolbarPopover>
}

export function Topbar({ title, pages, navigate, theme, onThemeChange, canOperate }: {
  title: string; pages: SearchPage[]; navigate: Navigate; theme: Theme; onThemeChange: (theme: Theme) => void; canOperate: boolean
}) {
  const jobHistory = useTransferJobs(canOperate)
  const { syncJobs } = useActivityNotifications()
  useEffect(() => {
    if (canOperate && jobHistory.data) syncJobs(jobHistory.data)
  }, [canOperate, jobHistory.data, syncJobs])
  const jobs = canOperate ? jobHistory.data ?? [] : []
  const alerts = jobs.filter(job => job.status === 'FAILED' || job.status === 'PARTIAL_FAILURE').slice(0, 6)
  return <header className="topbar">
    <div className="topbar-path"><span>Workspace</span><Icon name="chevron" size={14} /><strong>{title}</strong></div>
    <GlobalSearch pages={pages} navigate={navigate} jobs={jobs} />
    <div className="topbar-actions">
      {canOperate && <ToolbarPopover label={`Notifications (${alerts.length})`} triggerClass="alerts-trigger" panelClass="alerts-popover"
        triggerContent={<><Icon name="bell" size={19} />{alerts.length > 0 && <span className="alerts-badge" aria-hidden="true">{alerts.length}</span>}</>}>
        {close => <><h2>Notifications</h2><div className="notifications">
          {alerts.length ? alerts.map(job => <button type="button" className="notification" key={job.id}
            onClick={() => { close(); navigate('/activities?' + new URLSearchParams({ job: job.id })) }}>
            <span className="icon-tile yellow"><Icon name="warning" size={16} /></span>
            <span className="notification-text"><strong>{operationLabels[job.operation]} · {jobLabels[job.status]}</strong>
              {job.details && <small>{job.details.split('\n')[0]}</small>}</span><Icon name="chevron" size={14} />
          </button>) : <p className="empty compact">No notifications.</p>}
        </div></>}
      </ToolbarPopover>}
      <ProfileMenu theme={theme} onThemeChange={onThemeChange} />
    </div>
  </header>
}
