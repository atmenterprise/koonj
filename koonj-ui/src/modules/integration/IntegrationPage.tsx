import { useAuth } from '../../auth/AuthProvider'
import { usePermissions } from '../../auth/WorkspacePermissions'
import type { Navigate } from '../../shared/ui/feedback'
import { Icon } from '../../shared/ui/primitives'
import { NotificationSettings } from '../admin/NotificationSettings'
import { ApiSettings } from './ApiSettings'
import { McpSettings } from './McpSettings'

export default function IntegrationPage({ section, navigate, projectId }: {
  section: string | null; navigate: Navigate; projectId: string
}) {
  const { can } = usePermissions()
  const { hasRole } = useAuth()
  const tabs = [
    { id: 'notifications', title: 'Notifications & Alerts', icon: 'bell', visible: hasRole('KOONJ_ADMIN') },
    { id: 'api', title: 'APIs', icon: 'link', visible: can('INTEGRATIONS_MANAGE') },
    { id: 'mcp', title: 'MCP & AI Assistant', icon: 'layers', visible: true },
  ] as const
  const visible = tabs.filter(tab => tab.visible)
  const active = visible.find(tab => tab.id === section) ?? visible[0]

  return <>
    <div className="page-heading"><h1>Integration</h1></div>
    <nav className="admin-tabs filters" aria-label="Integration sections">
      {visible.map(tab => <button key={tab.id} type="button" aria-pressed={active.id === tab.id}
        onClick={() => navigate('/integration?section=' + tab.id)}>
        <Icon name={tab.icon} size={15} />{tab.title}
      </button>)}
    </nav>
    {active.id === 'notifications' && <NotificationSettings />}
    {active.id === 'api' && <ApiSettings />}
    {active.id === 'mcp' && <McpSettings key={projectId} projectId={projectId} />}
  </>
}
