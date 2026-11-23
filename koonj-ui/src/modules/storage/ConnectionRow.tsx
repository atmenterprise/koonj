import { useId } from 'react'
import { useScanMutation, useTestConnection } from '../../api/hooks/mutations'
import { isObjectStorage } from '../../api/mappers'
import type { StorageConnectionDTO } from '../../api/types'
import type { Navigate } from '../../shared/ui/feedback'
import { Badge, Button, Icon } from '../../shared/ui/primitives'
import { providers, roles } from './StorageConnectivity'

export function ConnectionRow({ connection, navigate, expanded, onToggle, onEdit, onDelete }: {
  connection: StorageConnectionDTO; navigate: Navigate; expanded: boolean
  onToggle: () => void; onEdit?: () => void; onDelete?: () => void
}) {
  const detailsId = useId()
  const scan = useScanMutation()
  const test = useTestConnection()
  const cloud = isObjectStorage(connection.providerType)

  return <>
    <tr className="connection-row" role="row" data-expanded={expanded || undefined}>
      <th scope="row">
        <div className="connection-row-name">
          <span className={`icon-tile ${cloud ? 'blue' : 'neutral'}`}><Icon name={cloud ? 'cloud' : 'drive'} size={16} /></span>
          <div><strong>{connection.name}</strong><small>{providers[connection.providerType]}</small></div>
        </div>
      </th>
      <td className="connection-row-path">{connection.basePathOrBucket}</td>
      <td>{roles[connection.role]}</td>
      <td><Badge dot tone={connection.active ? 'green' : 'neutral'}>{connection.active ? 'Active' : 'Inactive'}</Badge></td>
      <td><div className="row-actions">
        {onEdit && <Button onClick={onEdit} aria-label={`Edit ${connection.name}`}>Edit</Button>}
        <Button icon={expanded ? 'down' : 'chevron'} aria-expanded={expanded} aria-controls={detailsId}
          aria-label={`Details for ${connection.name}`} onClick={onToggle}>Details</Button>
      </div></td>
    </tr>
    <tr id={detailsId} className="connection-row-details" role="row" hidden={!expanded}>
      <td colSpan={5}>
        {expanded && <div className="connection-details">
          {cloud && <dl className="live-metadata">
            <div><dt>Endpoint URL</dt><dd>{connection.endpointUrl ?? 'Provider default'}</dd></div>
            <div><dt>Region</dt><dd>{connection.region ?? 'Provider default'}</dd></div>
            <div><dt>Path-style access</dt><dd>{connection.pathStyleAccessEnabled ? 'Enabled' : 'Disabled'}</dd></div>
            <div><dt>Credentials</dt><dd>{connection.accessKeyOrUsername || connection.secretKeyOrPassword ? 'Configured' : '—'}</dd></div>
          </dl>}
          <div className="row-actions">
            <Button onClick={() => navigate('/explorer?' + new URLSearchParams({ endpoint: connection.id }))}>Browse Catalog</Button>
            {onEdit && <>
              <Button icon="scan" disabled={scan.isPending || !connection.active} onClick={() => scan.mutate(connection.id)}>
                {scan.isPending ? 'Submitting…' : 'Scan Now'}</Button>
              <Button disabled={test.isPending} onClick={() => test.mutate(connection.id)}>
                {test.isPending ? 'Testing…' : 'Test Connection'}</Button>
            </>}
            {onDelete && <Button variant="danger" onClick={onDelete}>Delete</Button>}
          </div>
          {test.data && <p className={test.data.healthy ? 'connection-feedback' : 'connection-feedback connection-feedback-error'} role="status">
            {test.data.message}
          </p>}
        </div>}
      </td>
    </tr>
  </>
}
