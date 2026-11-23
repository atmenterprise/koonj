import type { StorageLocationStatusDTO, ConnectionHealthDTO } from '../../api/types'
import type { UseQueryResult } from '@tanstack/react-query'
import { Badge } from '../../shared/ui/primitives'

export const providers = {
  LOCAL: 'Local storage', SMB: 'SMB share', NAS: 'NAS', AWS_S3: 'Amazon S3',
  MINIO: 'MinIO', RUSTFS: 'RustFS', SCALITY: 'Scality', CUSTOM_S3: 'S3-compatible storage',
} satisfies Record<StorageLocationStatusDTO['providerType'], string>

export const roles = { SOURCE: 'Source', TARGET: 'Target', BOTH: 'Source and target' }
export type ConnectionProbe = UseQueryResult<ConnectionHealthDTO>
const checkedTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

export function ConnectionStatus({ connection, probe, detailed = false }: {
  connection: StorageLocationStatusDTO; probe?: ConnectionProbe; detailed?: boolean
}) {
  const status = !connection.active ? { label: 'Inactive', tone: 'neutral', detail: 'Connectivity checks are disabled.' }
    : probe?.isFetching ? { label: 'Checking…', tone: 'blue', detail: 'Checking access from Koonj Server.' }
      : probe?.isError || probe?.isPaused || !probe?.data
        ? { label: 'Unknown', tone: 'neutral', detail: 'Koonj could not retrieve the connectivity result.' }
        : { label: probe.data.healthy ? 'Online' : 'Offline', tone: probe.data.healthy ? 'green' : 'red', detail: probe.data.message }
  const checked = connection.active && probe?.fetchStatus === 'idle' && probe.isSuccess && probe.dataUpdatedAt
    ? new Date(probe.dataUpdatedAt) : undefined

  return <div className="storage-connectivity-result" aria-live={detailed ? 'polite' : undefined}>
    <Badge dot tone={status.tone}>{status.label}</Badge>
    {(detailed || status.label === 'Offline' || status.label === 'Unknown') && <p>{status.detail}</p>}
    {checked && <p className="storage-connectivity-time">Checked <time dateTime={checked.toISOString()}>{checkedTime.format(checked)}</time></p>}
  </div>
}

export function currentCapacity(connection: StorageLocationStatusDTO, probe?: ConnectionProbe) {
  if (connection.active && probe?.fetchStatus === 'idle' && probe.isSuccess && probe.data.healthy) return probe.data.capacity
}
