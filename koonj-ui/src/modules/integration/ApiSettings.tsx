import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminService, type IntegrationEndpoint } from '../../api/services/adminService'
import { apiClient } from '../../api/client'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { Dialog } from '../../shared/ui/feedback'
import { Badge, Button, Icon } from '../../shared/ui/primitives'

function apiReference() {
  return new URL('/swagger-ui/start', new URL(apiClient.defaults.baseURL ?? '/', window.location.origin)).href
}

export function ApiSettings() {
  const client = useQueryClient()
  const queryKey = ['integrations', 'API']
  const entries = useQuery({ queryKey, queryFn: ({ signal }) => adminService.getIntegrations('API', signal),
    meta: { errorTitle: 'Could not load integrations' } })
  const [editing, setEditing] = useState<IntegrationEndpoint | 'new'>()
  const [deleting, setDeleting] = useState<IntegrationEndpoint>()
  const save = useMutation({
    mutationFn: (form: FormData) => adminService.saveIntegration({
      kind: 'API', name: String(form.get('name')), url: String(form.get('url')), enabled: form.has('enabled'),
    }, editing === 'new' ? undefined : editing?.id),
    onSuccess: async () => { setEditing(undefined); await client.invalidateQueries({ queryKey }) },
  })
  const remove = useMutation({
    mutationFn: (id: string) => adminService.deleteIntegration(id),
    onSuccess: async () => { setDeleting(undefined); await client.invalidateQueries({ queryKey }) },
  })
  const selected = editing === 'new' ? undefined : editing

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    save.mutate(new FormData(event.currentTarget))
  }

  return <div className="admin-stack">
    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>Koonj API</h2><p>Inspect endpoint definitions and authentication requirements.</p></div>
        <a className="button ops-link" href={apiReference()} target="_blank" rel="noopener noreferrer">Open API reference <Icon name="external" size={14} /></a>
      </div>
    </section>
    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>API endpoints</h2>
        <p>Maintain a shared registry of endpoints for administrators. Enabled entries are marked available for use by external clients.</p>
      </div><Button variant="primary" icon="link" onClick={() => { save.reset(); setEditing('new') }}>Add API endpoint</Button></div>
      {entries.isError ? <ApiPageState error={entries.error} loadingLabel="Loading integrations" retry={() => void entries.refetch()} retrying={entries.isFetching} />
        : entries.isPending ? <p className="empty compact" role="status">Loading endpoints…</p>
          : entries.data.length ? <ul className="ops-integrations">{entries.data.map(entry => <li key={entry.id}>
            <div><strong>{entry.name}</strong><span>{entry.url}</span></div>
            <Badge tone={entry.enabled ? 'green' : 'neutral'} dot>{entry.enabled ? 'Enabled' : 'Disabled'}</Badge>
            <div className="row-actions">
              <Button onClick={() => { save.reset(); setEditing(entry) }} aria-label={`Edit ${entry.name}`}>Edit</Button>
              <Button onClick={() => { remove.reset(); setDeleting(entry) }} aria-label={`Delete ${entry.name}`}>Delete</Button>
            </div>
          </li>)}</ul> : <p className="empty compact">No API endpoints configured.</p>}
    </section>
    {editing && <Dialog title={(selected ? 'Edit' : 'Add') + ' API endpoint'} onClose={() => { if (!save.isPending) setEditing(undefined) }}>
      <form onSubmit={submit}>
        <div className="dialog-body">
          <label>Name<input name="name" defaultValue={selected?.name} maxLength={120} required /></label>
          <label>Endpoint URL<input name="url" type="url" defaultValue={selected?.url} maxLength={2048} placeholder="https://example.com/api" required /></label>
          <label className="check-label"><input name="enabled" type="checkbox" defaultChecked={selected?.enabled ?? true} />Enabled</label>
          {save.isError && <p className="ops-error" role="alert">{save.error.message}</p>}
        </div>
        <div className="dialog-footer">
          <Button onClick={() => setEditing(undefined)} disabled={save.isPending}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save endpoint'}</Button>
        </div>
      </form>
    </Dialog>}
    {deleting && <Dialog title="Delete API endpoint" onClose={() => { if (!remove.isPending) setDeleting(undefined) }}>
      <div className="dialog-body"><p>Remove {deleting.name} from the registry?</p>
        {remove.isError && <p className="ops-error" role="alert">{remove.error.message}</p>}
      </div>
      <div className="dialog-footer">
        <Button onClick={() => setDeleting(undefined)} disabled={remove.isPending}>Cancel</Button>
        <Button variant="danger" onClick={() => remove.mutate(deleting.id)} disabled={remove.isPending}>Delete</Button>
      </div>
    </Dialog>}
  </div>
}
