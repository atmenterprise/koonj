import { apiClient } from '../../api/client'
import { Icon } from '../../shared/ui/primitives'

function apiReference() {
  return new URL('/swagger-ui/start', new URL(apiClient.defaults.baseURL ?? '/', window.location.origin)).href
}

export function ApiSettings() {
  return <div className="admin-stack">
    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>Koonj API</h2><p>Inspect endpoint definitions and authentication requirements.</p></div>
        <a className="button ops-link" href={apiReference()} target="_blank" rel="noopener noreferrer">Open API reference <Icon name="external" size={14} /></a>
      </div>
    </section>
  </div>
}
