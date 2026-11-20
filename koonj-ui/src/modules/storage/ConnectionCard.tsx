import { useScanMutation, useTestConnection } from '../../api/hooks/mutations'
import { isObjectStorage } from '../../api/mappers'
import type { CatalogLocationDTO, StorageConnectionDTO } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { RequestError, type Navigate } from '../../shared/ui/feedback'
import { Badge, Button, Icon } from '../../shared/ui/primitives'

export function ConnectionCard({ connection, navigate, onEdit, onDelete }: {
  connection: StorageConnectionDTO | CatalogLocationDTO; navigate: Navigate; onEdit?: () => void; onDelete?: () => void
}) {
  const { hasRole } = useAuth()
  const canAdminister = hasRole('KOONJ_ADMIN')
  const canScan = canAdminister || hasRole('KOONJ_OPERATOR')
  const scan = useScanMutation()
  const test = useTestConnection()
  const configuration = canAdminister && 'basePathOrBucket' in connection ? connection : undefined
  const path = configuration?.basePathOrBucket ?? ('bucket' in connection ? connection.bucket : null)
  return <article className="endpoint live-endpoint">
    <div className="endpoint-heading"><span className="icon-tile neutral">
      <Icon name={isObjectStorage(connection.providerType) ? 'cloud' : 'drive'} size={16} />
    </span><Badge tone={connection.active ? 'green' : 'neutral'}>{connection.active ? connection.role : 'Inactive'}</Badge></div>
    <strong className="endpoint-name">{connection.name}</strong>
    <p className="endpoint-detail">{connection.providerType}{path && <> · {path}</>}</p>
    {configuration && <dl className="live-metadata storage-connection-details">
      <div><dt>Endpoint URL</dt><dd>{configuration.endpointUrl ?? '—'}</dd></div>
      <div><dt>Region</dt><dd>{configuration.region ?? '—'}</dd></div>
      <div><dt>Path-style access</dt><dd>{configuration.pathStyleAccessEnabled ? 'Enabled' : 'Disabled'}</dd></div>
      <div><dt>Credentials</dt><dd>{configuration.accessKeyOrUsername || configuration.secretKeyOrPassword ? 'Configured' : '—'}</dd></div>
    </dl>}
    <div className="row-actions">
      <Button onClick={() => navigate('/explorer?' + new URLSearchParams({ endpoint: connection.id }))}>Browse</Button>
      {canScan && <Button icon="scan" disabled={scan.isPending || !connection.active || connection.providerType === 'SMB'}
        onClick={() => scan.mutate(connection.id)}>
        {scan.isPending ? 'Submitting…' : 'Scan Now'}</Button>}
      {canAdminister && configuration && <Button disabled={test.isPending} onClick={() => test.mutate(connection.id)}>
        {test.isPending ? 'Testing…' : 'Test Connection'}</Button>}
      {canAdminister && onEdit && <Button onClick={onEdit}>Edit</Button>}
      {canAdminister && onDelete && <Button onClick={onDelete}>Delete</Button>}
    </div>
    <RequestError error={scan.error ?? test.error} />
    {test.data && <p className={test.data.healthy ? 'endpoint-detail' : 'error-text'} role="status">{test.data.message}</p>}
  </article>
}
