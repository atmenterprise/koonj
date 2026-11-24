import { usePermissions } from '../../auth/WorkspacePermissions'
import type { Navigate } from '../../shared/ui/feedback'
import { Icon } from '../../shared/ui/primitives'
import { ApiSettings } from './ApiSettings'
import { McpSettings } from './McpSettings'

export default function IntegrationPage({ section, navigate, projectId }: {
  section: string | null; navigate: Navigate; projectId: string
}) {
  const { can } = usePermissions()
  const canManageApi = can('INTEGRATIONS_MANAGE')
  const showApi = canManageApi && section === 'api'

  return <>
    <div className="page-heading"><h1>Integration</h1></div>
    <nav className="admin-tabs filters" aria-label="Integration sections">
      <button type="button" aria-pressed={!showApi} onClick={() => navigate('/integration')}>
        <Icon name="layers" size={15} />MCP &amp; AI Assistant
      </button>
      {canManageApi && <button type="button" aria-pressed={showApi} onClick={() => navigate('/integration?section=api')}>
        <Icon name="link" size={15} />APIs
      </button>}
    </nav>
    {showApi ? <ApiSettings /> : <McpSettings key={projectId} projectId={projectId} />}
  </>
}
