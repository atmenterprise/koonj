import { useEffect, useState } from 'react'
import AdminPage from '../modules/admin/AdminPage'
import AuditLogPage from '../modules/activity/AuditLogPage'
import DashboardPage from '../modules/home/DashboardPage'
import StorageBrowserPage from '../modules/catalog/StorageBrowserPage'
import StorageStatusPage from '../modules/storage/StorageStatusPage'
import { Topbar, themes, type Theme } from './Topbar'
import { NotificationPopup } from '../shared/notifications/NotificationPopup'
import { useAuth } from '../auth/AuthProvider'
import { Button, Icon } from '../shared/ui/primitives'

const pages = [
  { path: '/Home', title: 'Home', icon: 'home', access: 'read' },
  { path: '/explorer', title: 'Catalog explorer', shortTitle: 'Explorer', icon: 'folder', access: 'read' },
  { path: '/activities', title: 'Activity log', shortTitle: 'Activity', icon: 'activity', access: 'operate' },
  { path: '/storage', title: 'Storage status', shortTitle: 'Storage', icon: 'drive', access: 'admin' },
  { path: '/admin', title: 'Administration', shortTitle: 'Admin', icon: 'layers', access: 'admin' },
] as const
const currentLocation = () => window.location.pathname + window.location.search

export default function Console() {
  const [location, setLocation] = useState(currentLocation)
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem('koonj-sidebar') === 'collapsed')
  const [theme, setTheme] = useState<Theme>(() => {
    const preference = localStorage.getItem('koonj-theme')
    return themes.find(option => option.id === preference)?.id ?? 'classic'
  })
  const { hasRole } = useAuth()
  const canAdminister = hasRole('KOONJ_ADMIN')
  const canOperate = canAdminister || hasRole('KOONJ_OPERATOR')
  const canView = (item: typeof pages[number]) =>
    item.access === 'read' || (item.access === 'operate' ? canOperate : canAdminister)
  const [path, query] = location.split('?')
  const params = new URLSearchParams(query)
  const page = pages.find(item => item.path.toLowerCase() === path.toLowerCase().replace(/\/+$/, '')) ?? pages[0]

  useEffect(() => {
    const changed = () => setLocation(currentLocation())
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
        onThemeChange={setTheme} canOperate={canOperate} />
      <main id="main-content" className="main-content" tabIndex={-1}>
        {!canView(page) ? <section className="api-page-state" aria-labelledby="access-denied-title">
          <div className="panel api-page-card">
            <span className="icon-tile yellow"><Icon name="warning" size={24} /></span>
            <h1 id="access-denied-title">Access denied</h1>
            <p>Your role does not have permission to open this page.</p>
            <div className="row-actions api-page-actions">
              <Button variant="primary" icon="home" onClick={() => navigate('/Home')}>Go to Home</Button>
            </div>
          </div>
        </section> : page.path === '/explorer' ? <StorageBrowserPage key={location} initialConnection={params.get('endpoint')} initialEntry={params.get('entry')}
            initialPath={params.get('path')} />
          : page.path === '/activities' ? <AuditLogPage key={location} initialJob={params.get('job')} />
          : page.path === '/admin' ? <AdminPage section={params.get('section')} job={params.get('job')} navigate={navigate} />
          : page.path === '/storage' ? <StorageStatusPage navigate={navigate} />
          : <DashboardPage navigate={navigate} />}
      </main>
      <footer className="app-footer"><span>© 2026 Apps that Matter</span><span>Version 1.0</span></footer>
    </div>
    <NotificationPopup navigate={canOperate ? navigate : undefined} />
  </div>
}
