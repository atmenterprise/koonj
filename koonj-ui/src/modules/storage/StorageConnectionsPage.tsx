import { useState } from 'react'
import { useDeleteConnection, useScanSourcesMutation } from '../../api/hooks/mutations'
import { useConnections } from '../../api/hooks/queries'
import { providerTypes, type ProviderType, type StorageConnectionDTO } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { Dialog, type Navigate } from '../../shared/ui/feedback'
import { Button, Icon, count } from '../../shared/ui/primitives'
import { ConnectionRow } from './ConnectionRow'
import { ConnectionSettings } from './ConnectionSettings'
import { providers } from './StorageConnectivity'

const pageSize = 12
const roleFilters = [
  { value: 'all', label: 'All roles' },
  { value: 'SOURCE', label: 'Sources' },
  { value: 'TARGET', label: 'Targets' },
] as const
type RoleFilter = typeof roleFilters[number]['value']

function filterConnections(connections: StorageConnectionDTO[], search: string, role: RoleFilter, provider: ProviderType | '') {
  const term = search.trim().toLowerCase()
  return connections.filter(connection => {
    if (role !== 'all' && connection.role !== role && connection.role !== 'BOTH') return false
    if (provider && connection.providerType !== provider) return false
    return !term || [connection.name, connection.basePathOrBucket, connection.endpointUrl, connection.region,
      connection.providerType, providers[connection.providerType]].some(value => value?.toLowerCase().includes(term))
  })
}

export function StorageConnectionsPage({ navigate }: { navigate: Navigate }) {
  const { hasRole } = useAuth()
  const canManage = hasRole('KOONJ_ADMIN')
  const connections = useConnections()
  const [search, setSearch] = useState('')
  const [role, setRole] = useState<RoleFilter>('all')
  const [provider, setProvider] = useState<ProviderType | ''>('')
  const [pageIndex, setPageIndex] = useState(0)
  const [expanded, setExpanded] = useState<string>()
  const [settings, setSettings] = useState<StorageConnectionDTO | 'new'>()
  const [pendingDelete, setPendingDelete] = useState<StorageConnectionDTO>()
  const remove = useDeleteConnection()
  const scanSources = useScanSourcesMutation()

  function goToPage(next: number) {
    setPageIndex(next)
    setExpanded(undefined)
  }

  if (connections.error && !(canManage && (settings || pendingDelete))) return <ApiPageState loadingLabel="Loading storage connections" error={connections.error}
    retry={() => void connections.refetch()} retrying={connections.isFetching} />
  if (connections.isPending) return <ApiPageState loadingLabel="Loading storage connections" />

  const registered = connections.data ?? []
  const filtered = filterConnections(registered, search, role, provider)
  const pages = Math.max(1, Math.ceil(filtered.length / pageSize))
  const page = Math.min(pageIndex, pages - 1)
  const start = page * pageSize
  const visible = filtered.slice(start, start + pageSize)
  const active = registered.filter(connection => connection.active).length
  const hasActiveSources = registered.some(connection => connection.active && connection.role !== 'TARGET')

  return <>
    <section className="panel connection-directory" aria-labelledby="connection-directory-title">
      <div className="panel-heading connection-directory-heading">
        <div><h2 id="connection-directory-title">Storage connections</h2>
          <p>{count(registered.length)} registered · {count(active)} active</p>
        </div>
        {canManage && <div className="row-actions connection-directory-actions">
          <Button variant="primary" icon="server" onClick={() => setSettings('new')}>Add connection</Button>
          <Button icon="scan" aria-label="Scan all active sources" title="Queue scans for all active source connections"
            disabled={!hasActiveSources || scanSources.isPending} onClick={() => scanSources.mutate()}>
            {scanSources.isPending ? 'Queuing scans…' : 'Scan Now'}
          </Button>
        </div>}
      </div>
      {registered.length > 0 && <div className="table-tools connection-directory-tools">
        <div className="filters" role="group" aria-label="Filter by connection role">
          {roleFilters.map(filter => <button type="button" key={filter.value} aria-pressed={role === filter.value}
            onClick={() => { setRole(filter.value); goToPage(0) }}>{filter.label}</button>)}
        </div>
        <div className="connection-directory-filters">
          <label className="connection-search"><Icon name="search" size={16} /><span className="sr-only">Search connections</span>
            <input type="search" value={search} placeholder="Search connections" onChange={event => { setSearch(event.target.value); goToPage(0) }} />
          </label>
          <label><span className="sr-only">Filter by provider</span>
            <select value={provider} onChange={event => { setProvider(event.target.value as ProviderType | ''); goToPage(0) }}>
              <option value="">All providers</option>
              {providerTypes.map(item => <option key={item} value={item}>{providers[item]}</option>)}
            </select>
          </label>
        </div>
      </div>}
      {visible.length > 0 ? <>
        <div className="connection-table-scroll">
          <table className="connection-table" role="table" aria-label="Registered storage connections">
            <thead role="rowgroup"><tr role="row"><th scope="col">Connection</th><th scope="col">Path or bucket</th><th scope="col">Role</th><th scope="col">Status</th><th scope="col">Actions</th></tr></thead>
            <tbody role="rowgroup">{visible.map(connection => <ConnectionRow key={connection.id} connection={connection} navigate={navigate}
              expanded={expanded === connection.id}
              onToggle={() => setExpanded(expanded === connection.id ? undefined : connection.id)}
              onEdit={canManage ? () => setSettings(connection) : undefined}
              onDelete={canManage ? () => { remove.reset(); setPendingDelete(connection) } : undefined} />)}</tbody>
          </table>
        </div>
        <div className="catalog-pagination">
          <span aria-live="polite">{count(start + 1)}–{count(start + visible.length)} of {count(filtered.length)} connections</span>
          {pages > 1 && <div className="catalog-page-actions">
            <Button disabled={page === 0} onClick={() => goToPage(page - 1)}>Previous</Button>
            <span>Page {count(page + 1)} of {count(pages)}</span>
            <Button disabled={page + 1 >= pages} onClick={() => goToPage(page + 1)}>Next</Button>
          </div>}
        </div>
      </> : <p className="empty compact" role="status">{registered.length ? 'No connections match these filters.' : 'No storage connections configured.'}</p>}
    </section>
    {canManage && settings && <ConnectionSettings connection={settings === 'new' ? undefined : settings} onClose={() => setSettings(undefined)} />}
    {canManage && pendingDelete && <Dialog title="Delete connection" onClose={() => { if (!remove.isPending) setPendingDelete(undefined) }}>
      <div className="dialog-body">
        <p>Delete {pendingDelete.name}?</p>
      </div>
      <div className="dialog-footer">
        <Button disabled={remove.isPending} onClick={() => setPendingDelete(undefined)}>Cancel</Button>
        <Button disabled={remove.isPending} variant="danger" onClick={() => remove.mutate(pendingDelete.id, {
          onSuccess: () => setPendingDelete(undefined),
        })}>{remove.isPending ? 'Deleting…' : 'Delete connection'}</Button>
      </div>
    </Dialog>}
  </>
}
