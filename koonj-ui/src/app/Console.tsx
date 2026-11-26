import { Fragment, lazy, Suspense, useEffect, useRef, useState } from 'react'
import DashboardPage from '../modules/home/DashboardPage'
import StorageBrowserPage from '../modules/catalog/StorageBrowserPage'
import { Topbar, themes, type Theme } from './Topbar'
import type { Project } from '../api/services/projectService'
import { NotificationPopup } from '../shared/notifications/NotificationPopup'
import { useActivityNotifications } from '../shared/notifications/ActivityNotifications'
import { usePermissions } from '../auth/WorkspacePermissions'
import { useAuth } from '../auth/AuthProvider'
import { Button, Icon } from '../shared/ui/primitives'
import { LoadingRows } from '../shared/ui/feedback'

const AuditLogPage = lazy(() => import('../modules/activity/AuditLogPage'))
const StorageStatusPage = lazy(() => import('../modules/storage/StorageStatusPage'))
const AdminPage = lazy(() => import('../modules/admin/AdminPage'))
const IntegrationPage = lazy(() => import('../modules/integration/IntegrationPage'))
const ManagementPage = lazy(() => import('../modules/management/ManagementPage'))

import { ProjectSettings } from '../modules/admin/ProjectSettings'

const pages = [
  { path: '/Home', title: 'Home', icon: 'home', access: [] },
  { path: '/explorer', title: 'Catalog', icon: 'folder', access: ['CATALOG_VIEW'] },
  { path: '/activities', title: 'Activity log', shortTitle: 'Activity', icon: 'activity', access: ['ACTIVITY_VIEW'] },
  { path: '/storage', title: 'Storage Status', shortTitle: 'Storage', icon: 'drive', access: ['STORAGE_VIEW'] },
  { path: '/management', title: 'Management', icon: 'server', access: ['STORAGE_MANAGE', 'POLICY_MANAGE'] },
  { path: '/projects', title: 'Projects', icon: 'projects', access: [] },
  { path: '/integration', title: 'Integration', icon: 'link', access: [] },
  { path: '/admin', title: 'Administration', shortTitle: 'Admin', icon: 'layers', access: ['OBSERVABILITY_VIEW', 'PERMISSIONS_MANAGE'] },
  { path: '/billing', title: 'Billing', icon: 'billing', access: [] },
  { path: '/support', title: 'Support', icon: 'support', access: [] },
] as const
const supportEmail = 'atmenterprise23@gmail.com'
const browserLocation = () => window.location.pathname + window.location.search
function currentLocation() {
  const location = browserLocation()
  if (window.location.pathname.toLowerCase().replace(/\/+$/, '') !== '/admin') return location
  const params = new URLSearchParams(window.location.search)
  if (params.get('section') === 'projects') return '/projects'
  if (params.get('section') === 'notifications') return '/integration?section=notifications'
  if (params.get('section') === 'mcp') return '/integration?section=mcp'
  if (params.get('section') === 'api') return '/integration?section=api'
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
  const { can } = usePermissions()
  const { hasRole, user } = useAuth()
  const { notify } = useActivityNotifications()
  const deniedLocation = useRef<string | undefined>(undefined)
  const canViewActivity = can('ACTIVITY_VIEW')
  const canView = (item: typeof pages[number]) => !item.access.length || item.access.some(can)
    || (item.path === '/admin' && hasRole('KOONJ_ADMIN'))
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
        <span className="brand-mark" aria-hidden="true" />
        <span className="brand-text"><strong>Koonj</strong><small>Storage Explorer</small></span>
      </a>
      <div className="workspace-nav"><p className="nav-label">Workspace</p><nav id="main-navigation" aria-label="Main navigation">
        {pages.filter(canView).map(item => <Fragment key={item.path}>
          <a className="nav-link" href={item.path} aria-label={item.title}
            aria-current={page === item ? 'page' : undefined} title={collapsed ? item.title : undefined} onClick={event => {
              if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
              event.preventDefault()
              navigate(item.path)
            }}>
            <Icon name={item.icon} size={19} /><span className="nav-text">
              {'shortTitle' in item ? <><span className="nav-full-title">{item.title}</span><span className="nav-short-title">{item.shortTitle}</span></> : item.title}
            </span>{page === item && <span className="nav-indicator" />}
          </a>
          {item.path === '/integration' && <hr className="sidebar-divider" />}
        </Fragment>)}
      </nav></div>
      <Button className="icon-button sidebar-toggle" icon="sidebar" variant="quiet" aria-expanded={!collapsed}
        aria-controls="main-navigation" aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} onClick={() => {
          localStorage.setItem('koonj-sidebar', collapsed ? 'expanded' : 'collapsed')
          setCollapsed(!collapsed)
        }} />
      <footer className="sidebar-footer">© 2026 Apps that Matter</footer>
    </aside>
    <div className="app-body">
      <Topbar title={page.title} pages={pages.filter(canView)} navigate={navigate} theme={theme}
        onThemeChange={setTheme} canViewActivity={canViewActivity} project={project} projects={projects} onProjectSwitch={onProjectSwitch} />
      <main id="main-content" className="main-content" tabIndex={-1}>
        {!pageAllowed ? null : page.path === '/explorer' ? <StorageBrowserPage key={location} initialConnection={params.get('endpoint')} initialEntry={params.get('entry')}
            initialPath={params.get('path')} navigate={navigate} />
          : page.path === '/activities' ? <Suspense fallback={<LoadingRows label="Loading activity" />}>
            <AuditLogPage key={location} initialJob={params.get('job')} />
          </Suspense>
          : page.path === '/integration' ? <Suspense fallback={<LoadingRows label="Loading integrations" />}>
            <IntegrationPage section={params.get('section')} navigate={navigate} projectId={project.id} />
          </Suspense>
          : page.path === '/projects' ? <ProjectSettings projectCount={projects.length} canConfigureChannels={hasRole('KOONJ_ADMIN')} project={project} refreshMemberships={refreshMemberships} />
          : page.path === '/admin' ? <Suspense fallback={<LoadingRows label="Loading administration" />}>
            <AdminPage section={params.get('section')} navigate={navigate} />
          </Suspense>
          : page.path === '/management' ? <Suspense fallback={<LoadingRows label="Loading management" />}>
            <ManagementPage section={params.get('section')} navigate={navigate} />
          </Suspense>
          : page.path === '/storage' ? <Suspense fallback={<LoadingRows label="Loading storage status" />}>
            <StorageStatusPage />
          </Suspense>
          : page.path === '/billing' ? <>
            <div className="page-heading"><h1>Billing</h1></div>
            <section className="panel admin-group">
              <div className="panel-heading"><h2>Free</h2></div>
            </section>
          </> : page.path === '/support' ? <>
            <div className="page-heading"><h1>Support</h1></div>
            <section className="panel admin-group">
              <a className="support-email" href={`mailto:${supportEmail}`}>{supportEmail}</a>
            </section>
          </> : user?.roles.length ? <DashboardPage navigate={navigate} />
          : <ProjectSettings projectCount={projects.length} canConfigureChannels={hasRole('KOONJ_ADMIN')} project={project} refreshMemberships={refreshMemberships} />}
      </main>
    </div>
    <NotificationPopup navigate={canViewActivity ? navigate : undefined} />
  </div>
}
