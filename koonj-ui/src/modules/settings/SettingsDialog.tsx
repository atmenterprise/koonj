import { useId, useState, type KeyboardEvent } from 'react'
import { useAuth } from '../../auth/AuthProvider'
import { EmailNotificationPreferences } from '../../auth/EmailNotificationPreferences'
import { ProfileRoles } from '../../auth/ProfileRoles'
import { Dialog } from '../../shared/ui/feedback'
import { Icon } from '../../shared/ui/primitives'

const tabs = [
  { id: 'profile', label: 'Profile', icon: 'user' },
  { id: 'email', label: 'Email notifications', icon: 'bell' },
] as const

type Tab = typeof tabs[number]['id']

export function SettingsDialog({ onClose }: { onClose: () => void }) {
  const { user } = useAuth()
  const id = useId()
  const [tab, setTab] = useState<Tab>('profile')
  const [emailVisited, setEmailVisited] = useState(false)
  if (!user) return null

  function select(next: Tab) {
    setTab(next)
    if (next === 'email') setEmailVisited(true)
  }

  function moveTab(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const offset = event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0
    if (!offset && event.key !== 'Home' && event.key !== 'End') return
    event.preventDefault()
    const nextIndex = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
      : (index + offset + tabs.length) % tabs.length
    const next = tabs[nextIndex].id
    select(next)
    document.getElementById(id + '-tab-' + next)?.focus()
  }

  return <Dialog title="Settings" onClose={onClose} className="operation-dialog settings-dialog">
    <div className="filters settings-tabs" role="tablist" aria-label="Settings sections">
      {tabs.map((item, index) => <button key={item.id} id={id + '-tab-' + item.id} type="button" role="tab"
        aria-selected={tab === item.id} aria-controls={id + '-panel-' + item.id} tabIndex={tab === item.id ? 0 : -1}
        onClick={() => select(item.id)} onKeyDown={event => moveTab(event, index)}>
        <Icon name={item.icon} size={15} />{item.label}
      </button>)}
    </div>
    <section className="settings-panel" role="tabpanel" id={id + '-panel-profile'} aria-labelledby={id + '-tab-profile'}
      hidden={tab !== 'profile'} tabIndex={0}>
      <dl className="settings-profile">
        <div><dt>Name</dt><dd>{user.name}</dd></div>
        <div><dt>Email</dt><dd>{user.email || 'No email address on file'}</dd></div>
        <div><dt>Workspace roles</dt><dd><ProfileRoles roles={user.roles} /></dd></div>
      </dl>
    </section>
    <section className="settings-panel" role="tabpanel" id={id + '-panel-email'} aria-labelledby={id + '-tab-email'}
      hidden={tab !== 'email'} tabIndex={0}>
      {emailVisited && <EmailNotificationPreferences key={user.id} subject={user.id} />}
    </section>
  </Dialog>
}
