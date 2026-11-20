import { useEffect, useState, type MouseEvent } from 'react'
import Activities from './Activities'
import Admin from './Admin'
import Home from './Home'
import Inventory from './Inventory'
import StorageStatus from './StorageStatus'
import { useCatalogMetadata } from './catalogMetadata'
import { Button, Icon } from './ui'
import { useStorageConfiguration } from './storageConfiguration'
import Topbar, { adminPath, pages, profiles, storagePage, themes, type Profile, type Theme } from './Topbar'
import { useWorkspace } from './workspace'

function readPreference(storage: Storage, key: string) {
  try { return storage.getItem('koonj-' + key) } catch { return null }
}

function savePreference(storage: Storage, key: string, value: string) {
  try { storage.setItem('koonj-' + key, value) } catch { /* Storage is optional. */ }
}

function initialProfile(): Profile {
  const stored = readPreference(sessionStorage, 'demo-profile')
  return stored === 'guest' || stored === 'admin' ? stored : 'operator'
}

function initialTheme(): Theme {
  const stored = readPreference(localStorage, 'theme')
  if (stored === 'navy') return 'professional'
  return themes.find(theme => theme.id === stored)?.id ?? 'classic'
}

const browserLocation = () => window.location.pathname + window.location.search
export default function Console() {
  const [location, setLocation] = useState(browserLocation)
  const [profile, setProfile] = useState(initialProfile)
  const [collapsed, setCollapsed] = useState(() => readPreference(localStorage, 'sidebar') === 'collapsed')
  const [theme, setTheme] = useState(initialTheme)
  const configuration = useStorageConfiguration()
  const metadata = useCatalogMetadata()
  const [path, query] = location.split('?')
  const route = path.toLowerCase().replace(/\/+$/, '')
  const inAdmin = route === adminPath
  const workspace = useWorkspace(profiles[profile].name, configuration.endpoints, !inAdmin)

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    savePreference(localStorage, 'theme', theme)
  }, [theme])

  function changeProfile(next: Profile) {
    configuration.clearCredentials()
    setProfile(next)
    savePreference(sessionStorage, 'demo-profile', next)
  }

  function toggleSidebar() {
    const next = !collapsed
    setCollapsed(next)
    savePreference(localStorage, 'sidebar', next ? 'collapsed' : 'expanded')
  }

  const page = pages.find(item => item.path.toLowerCase() === route) ?? pages[0]
  const title = page.title
  const params = new URLSearchParams(query)

  useEffect(() => {
    const changed = () => setLocation(browserLocation())
    window.addEventListener('popstate', changed)
    return () => window.removeEventListener('popstate', changed)
  }, [])

  useEffect(() => { document.title = title + ' · Koonj' }, [title])
  function navigate(next: string) {
    if (next === location) return
    window.history.pushState(null, '', next)
    setLocation(next)
    window.scrollTo(0, 0)
  }

  function follow(event: MouseEvent<HTMLAnchorElement>, next: string) {
    if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey || event.button !== 0) return
    event.preventDefault()
    navigate(next)
  }

  return <div className={collapsed ? 'app-shell sidebar-collapsed' : 'app-shell'}>
    <a className="skip-link" href="#main-content">Skip to content</a>
    <aside className="app-sidebar">
      <a className="brand" href="/Home" aria-label="Koonj home" onClick={event => follow(event, '/Home')}>
        <span className="brand-mark"><Icon name="bird" size={24} /></span><span className="brand-text"><strong>Koonj</strong><small>APPS THAT MATTER</small></span>
      </a>
      <div className="workspace-nav">
        <p className="nav-label">Workspace</p>
        <nav id="main-navigation" aria-label="Main navigation">
          {pages.map(item => {
            const current = item.path === page.path
            return <a key={item.path} className="nav-link" href={item.path} aria-current={current ? 'page' : undefined} aria-label={item.title} title={collapsed ? item.title : undefined} onClick={event => follow(event, item.path)}>
              <Icon name={item.icon} size={19} /><span className="nav-text">
                {'shortTitle' in item ? <><span className="nav-full-title">{item.title}</span><span className="nav-short-title">{item.shortTitle}</span></> : item.title}
              </span>{current && <span className="nav-indicator" />}
            </a>
          })}
        </nav>
      </div>
      <Button className="icon-button sidebar-toggle" icon="sidebar" variant="quiet" aria-expanded={!collapsed} aria-controls="main-navigation"
        aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} onClick={toggleSidebar} />
    </aside>
    <div className="app-body">
      <Topbar title={title} workspace={workspace} navigate={navigate} profile={profile} onProfileChange={changeProfile} theme={theme} onThemeChange={setTheme} operationsOnly={inAdmin} metadata={metadata.store} />
      <main id="main-content" className="main-content" tabIndex={-1}>
        {inAdmin ? <Admin workspace={workspace} configuration={configuration} navigate={navigate} section={params.get('section')} job={params.get('job')} />
          : page.path === storagePage.path ? <StorageStatus workspace={workspace} endpoints={configuration.endpoints} navigate={navigate} />
          : page.path === '/explorer' ? <Inventory key={location} initialEndpoint={params.get('endpoint')} initialEntry={params.get('entry')} workspace={workspace} metadata={metadata} />
          : page.path === '/activities' ? <Activities key={profile + location} initialJob={params.get('job')} workspace={workspace} />
            : <Home key={profile} workspace={workspace} navigate={navigate} name={profiles[profile].name} isGuest={profile === 'guest'} />}
      </main>
      <footer className="app-footer"><span>© 2026 Apps that Matter</span><span>Version 1.0</span></footer>
    </div>
    {workspace.notice && <div className="toast" role="status"><span className="icon-tile green"><Icon name="check" size={17} /></span><strong>{workspace.notice}</strong>
      <Button variant="quiet" onClick={() => { workspace.dismissNotice(); navigate('/activities') }}>View activity <Icon name="arrow" size={14} /></Button>
      <Button icon="close" className="icon-button" variant="quiet" aria-label="Dismiss message" onClick={workspace.dismissNotice} />
    </div>}
  </div>
}
