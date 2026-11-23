import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import DashboardPage from '../modules/home/DashboardPage'
import StorageBrowserPage from '../modules/catalog/StorageBrowserPage'
import { Topbar, themes, type Theme } from './Topbar'
import type { Project } from '../api/services/projectService'
import { NotificationPopup } from '../shared/notifications/NotificationPopup'
import { useActivityNotifications } from '../shared/notifications/ActivityNotifications'
import { useAuth } from '../auth/AuthProvider'
import { Button, Icon } from '../shared/ui/primitives'
import { LoadingRows } from '../shared/ui/feedback'

const AuditLogPage = lazy(() => import('../modules/activity/AuditLogPage'))
const StorageStatusPage = lazy(() => import('../modules/storage/StorageStatusPage'))
const AdminPage = lazy(() => import('../modules/admin/AdminPage'))
const ManagementPage = lazy(() => import('../modules/management/ManagementPage'))

const pages = [
  { path: '/Home', title: 'Home', icon: 'home', access: 'read' },
  { path: '/explorer', title: 'Catalog', icon: 'folder', access: 'read' },
  { path: '/activities', title: 'Activity log', shortTitle: 'Activity', icon: 'activity', access: 'operate' },
  { path: '/storage', title: 'Storage status', shortTitle: 'Storage', icon: 'drive', access: 'admin' },
  { path: '/admin', title: 'Administration', shortTitle: 'Admin', icon: 'layers', access: 'admin' },
  { path: '/management', title: 'Management', icon: 'server', access: 'admin' },
] as const
const browserLocation = () => window.location.pathname + window.location.search
function currentLocation() {
  const location = browserLocation()
  if (window.location.pathname.toLowerCase().replace(/\/+$/, '') !== '/admin') return location
  const params = new URLSearchParams(window.location.search)
  if (params.get('section') === 'storage') return '/management'
  if (params.get('section') !== 'activity') return location
  const job = params.get('job')
  return job ? '/activities?' + new URLSearchParams({ job }) : '/activities'
}

export default function Console({ project, projects, onProjectSwitch, refreshMemberships }: {
  project: Project; projects: Project[]; onProjectSwitch: (id: string) => void; refreshMemberships: () => void
}) {
  const [location, setLocation] = useState(currentLocation)
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem('koonj-sidebar') === 'collapsed')
  const [theme, setTheme] = useState<Theme>(() => {
    const preference = localStorage.getItem('koonj-theme')
    return themes.find(option => option.id === preference)?.id ?? 'classic'
  })
  const { hasRole } = useAuth()
  const { notify } = useActivityNotifications()
  const deniedLocation = useRef<string | undefined>(undefined)
  const canAdminister = hasRole('KOONJ_ADMIN')
  const canOperate = canAdminister || hasRole('KOONJ_OPERATOR')
  const canView = (item: typeof pages[number]) =>
    item.access === 'read' || (item.access === 'operate' ? canOperate : canAdminister)
  const [path, query] = location.split('?')
  const params = new URLSearchParams(query)
  const page = pages.find(item => item.path.toLowerCase() === path.toLowerCase().replace(/\/+$/, '')) ?? pages[0]
  const pageAllowed = canView(page)

  useEffect(() => {
    if (pageAllowed) {
      deniedLocation.current = undefined
      return
    }
    if (deniedLocation.current === location) return
    deniedLocation.current = location
    notify({ tone: 'error', title: 'Access denied', detail: 'Insufficient permissions for this action' })
    window.history.replaceState(null, '', '/Home')
    setLocation('/Home')
  }, [location, notify, pageAllowed])

  useEffect(() => {
    const changed = () => {
      const canonical = currentLocation()
      if (canonical !== browserLocation()) window.history.replaceState(null, '', canonical)
      setLocation(canonical)
    }
    changed()
    window.addEventListener('popstate', changed)
    return () => window.removeEventListener('popstate', changed)
  }, [])
  useEffect(() => { document.title = page.title + ' · Koonj' }, [page.title])
  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('koonj-theme', theme)
  }, [theme])

  function navigate(next: string) {
    if (next === location) return
    window.history.pushState(null, '', next)
    setLocation(next)
    window.scrollTo(0, 0)
  }

  return <div className={collapsed ? 'app-shell sidebar-collapsed' : 'app-shell'}>
    <a className="skip-link" href="#main-content">Skip to content</a>
    <aside className="app-sidebar">
      <a className="brand" href="/Home" aria-label="Koonj home" onClick={event => {
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) return
        event.preventDefault()
        navigate('/Home')
      }}>
        <span className="brand-mark"><Icon name="bird" size={24} /></span>
        <span className="brand-text"><strong>Koonj</strong><small>APPS THAT MATTER</small></span>
      </a>
      <div className="workspace-nav"><p className="nav-label">Workspace</p><nav id="main-navigation" aria-label="Main navigation">
        {pages.filter(canView).map(item => <a key={item.path} className="nav-link" href={item.path} aria-label={item.title}
          aria-current={page === item ? 'page' : undefined} title={collapsed ? item.title : undefined} onClick={event => {
            if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
            event.preventDefault()
            navigate(item.path)
          }}>
          <Icon name={item.icon} size={19} /><span className="nav-text">
            {'shortTitle' in item ? <><span className="nav-full-title">{item.title}</span><span className="nav-short-title">{item.shortTitle}</span></> : item.title}
          </span>{page === item && <span className="nav-indicator" />}
        </a>)}
      </nav></div>
      <Button className="icon-button sidebar-toggle" icon="sidebar" variant="quiet" aria-expanded={!collapsed}
        aria-controls="main-navigation" aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} onClick={() => {
          localStorage.setItem('koonj-sidebar', collapsed ? 'expanded' : 'collapsed')
          setCollapsed(!collapsed)
        }} />
    </aside>
    <div className="app-body">
      <Topbar title={page.title} pages={pages.filter(canView)} navigate={navigate} theme={theme}
        onThemeChange={setTheme} canOperate={canOperate} project={project} projects={projects} onProjectSwitch={onProjectSwitch} />
      <main id="main-content" className="main-content" tabIndex={-1}>
        {!pageAllowed ? null : page.path === '/explorer' ? <StorageBrowserPage key={location} initialConnection={params.get('endpoint')} initialEntry={params.get('entry')}
            initialPath={params.get('path')} navigate={navigate} />
          : page.path === '/activities' ? <Suspense fallback={<LoadingRows label="Loading activity" />}>
            <AuditLogPage key={location} initialJob={params.get('job')} />
          </Suspense>
          : page.path === '/admin' ? <Suspense fallback={<LoadingRows label="Loading administration" />}>
            <AdminPage section={params.get('section')} navigate={navigate} projectId={project.id} refreshMemberships={refreshMemberships} />
          </Suspense>
          : page.path === '/management' ? <Suspense fallback={<LoadingRows label="Loading management" />}>
            <ManagementPage section={params.get('section')} navigate={navigate} />
          </Suspense>
          : page.path === '/storage' ? <Suspense fallback={<LoadingRows label="Loading storage status" />}>
            <StorageStatusPage />
          </Suspense>
          : <DashboardPage navigate={navigate} />}
      </main>
      <footer className="app-footer"><span>© 2026 Apps that Matter</span><span>Version 1.0</span></footer>
    </div>
    <NotificationPopup navigate={canOperate ? navigate : undefined} />
  </div>
}
