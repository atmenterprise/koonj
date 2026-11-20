import { useState } from 'react'
import { useDeleteConnection } from '../../api/hooks/mutations'
import { useConnections } from '../../api/hooks/queries'
import { isObjectStorage } from '../../api/mappers'
import type { StorageConnectionDTO } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { Dialog, RequestError, type Navigate } from '../../shared/ui/feedback'
import { Button } from '../../shared/ui/primitives'
import { ConnectionCard } from './ConnectionCard'
import { ConnectionSettings } from './ConnectionSettings'

export function StorageConnectionsPage({ navigate, embedded = false, grouped = false }: { navigate: Navigate; embedded?: boolean; grouped?: boolean }) {
  const { hasRole } = useAuth()
  const canManage = hasRole('KOONJ_ADMIN')
  const connections = useConnections()
  const [settings, setSettings] = useState<StorageConnectionDTO | 'new' | null>(null)
  const [pendingDelete, setPendingDelete] = useState<StorageConnectionDTO | null>(null)
  const remove = useDeleteConnection()
  if (connections.error && !(canManage && (settings || pendingDelete))) return <ApiPageState loadingLabel="Loading storage connections" error={connections.error}
    retry={() => void connections.refetch()} retrying={connections.isFetching} />
  if (connections.isPending) return <ApiPageState loadingLabel="Loading storage connections" />

  function renderConnection(connection: StorageConnectionDTO) {
    return <ConnectionCard key={connection.id} connection={connection} navigate={navigate}
      onEdit={() => setSettings(connection)} onDelete={() => { remove.reset(); setPendingDelete(connection) }} />
  }

  return <>
    <section className={grouped ? 'admin-stack' : embedded ? 'panel endpoints live-section' : undefined} aria-label="Registered storage">
      <div className={embedded ? 'panel-heading' : 'page-heading'}><div>
        {grouped ? <><h2>Storage configuration</h2><p>Add, verify, and manage storage connections.</p></>
          : embedded ? <><h2>Registered storage</h2><p>Source servers and cloud targets</p></> : <h1>Storage connections</h1>}
      </div>{canManage && <Button variant="primary" icon="server" onClick={() => setSettings('new')}>Add connection</Button>}</div>
      {grouped ? [false, true].map(cloud => {
        const items = connections.data?.filter(connection => isObjectStorage(connection.providerType) === cloud) ?? []
        return <section className="panel admin-group" key={String(cloud)}>
          <div className="panel-heading"><h2>{cloud ? 'S3-compatible targets' : 'Source directories'}</h2></div>
          {items.length ? <div className="live-endpoints">{items.map(renderConnection)}</div>
            : <p className="empty compact">No {cloud ? 'S3-compatible targets' : 'source directories'} configured.</p>}
        </section>
      }) : connections.data?.length
        ? <div className="live-endpoints">{connections.data.map(renderConnection)}</div>
        : !connections.isError && <p className="empty compact">No storage connections configured.</p>}
    </section>
    {canManage && settings && <ConnectionSettings connection={settings === 'new' ? undefined : settings} onClose={() => setSettings(null)} />}
    {canManage && pendingDelete && <Dialog title="Delete connection" onClose={() => { if (!remove.isPending) setPendingDelete(null) }}>
      <div className="dialog-body">
        <p>Delete {pendingDelete.name}?</p>
        <RequestError error={remove.error} />
      </div>
      <div className="dialog-footer">
        <Button disabled={remove.isPending} onClick={() => setPendingDelete(null)}>Cancel</Button>
        <Button disabled={remove.isPending} variant="primary" onClick={() => remove.mutate(pendingDelete.id, {
          onSuccess: () => setPendingDelete(null),
        })}>{remove.isPending ? 'Deleting…' : 'Delete connection'}</Button>
      </div>
    </Dialog>}
  </>
}
