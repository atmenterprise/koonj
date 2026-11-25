import { useEffect, useState } from 'react'
import { connectionService } from '../api/services/connectionService'
import type { ProviderType } from '../api/types'
import { endpoints, type Endpoint, type S3Provider } from './sampleData'

const key = 'koonj-storage-config-v1'
export type EndpointDraft = Pick<Endpoint, 'name' | 'origin' | 'path' | 'region' | 'connection'>
type AuditEvent = { time: number; message: string }
type Configuration = { endpoints: Endpoint[]; audit: AuditEvent[] }
type Verification = { verified: boolean; message: string }

export function sameConnection(existing: Endpoint | undefined, draft: EndpointDraft) {
  const { connection } = draft
  return Boolean(existing && existing.origin === draft.origin && existing.path === draft.path && existing.region === draft.region
    && JSON.stringify(existing.connection) === JSON.stringify(connection))
}

const providers: Record<S3Provider, ProviderType> = {
  aws: 'AWS_S3', minio: 'MINIO', scality: 'SCALITY', compatible: 'CUSTOM_S3',
}

export async function testStorageConnection(draft: EndpointDraft, signal: AbortSignal): Promise<Verification> {
  const objectStorage = draft.origin === 'S3'
  const settings = draft.connection
  const authMode = settings?.auth === 'reference' ? 'SECRET_REF'
    : objectStorage ? 'AWS_DEFAULT_CHAIN' : 'HOST_IDENTITY'
  const result = await connectionService.testDraft({
    name: draft.name, role: objectStorage ? 'BOTH' : 'SOURCE', authMode,
    providerType: objectStorage ? providers[settings?.provider ?? 'aws'] : draft.origin as ProviderType,
    basePathOrBucket: objectStorage ? draft.path.replace(/^s3:\/\//, '') : draft.path,
    region: draft.region, endpointUrl: settings?.endpointUrl,
    secretRef: settings?.credentialReference,
  }, signal)
  return { verified: result.healthy, message: result.message }
}

function load(): Configuration {
  try {
    const saved = JSON.parse(localStorage.getItem(key) ?? 'null')
    if (Array.isArray(saved?.endpoints) && Array.isArray(saved?.audit)) return saved
  } catch { /* Storage is optional. */ }
  return { endpoints, audit: [] }
}

export function useStorageConfiguration() {
  const [configuration, setConfiguration] = useState(load)

  useEffect(() => {
    try { localStorage.setItem(key, JSON.stringify(configuration)) } catch { /* Storage is optional. */ }
  }, [configuration])

  function update(endpoint: Endpoint, message: string) {
    const time = Date.now()
    setConfiguration(previous => ({
      endpoints: previous.endpoints.some(item => item.id === endpoint.id)
        ? previous.endpoints.map(item => item.id === endpoint.id ? endpoint : item)
        : [...previous.endpoints, endpoint],
      audit: [{ time, message }, ...previous.audit],
    }))
  }

  function save(draft: EndpointDraft, existing?: Endpoint, verified?: boolean) {
    const id = existing?.id ?? 'endpoint-' + crypto.getRandomValues(new Uint32Array(2)).join('-')
    const connectionUnchanged = sameConnection(existing, draft)
    update({
      id,
      items: existing?.items ?? 0,
      health: verified === true ? 'Online' : existing && connectionUnchanged && verified === undefined ? existing.health : 'Not checked',
      verifiedAt: verified === true ? Date.now() : connectionUnchanged && verified === undefined ? existing?.verifiedAt : undefined,
      enabled: existing?.enabled ?? true,
      detail: draft.region ?? existing?.detail ?? 'No scan history',
      ...draft,
    }, (existing ? 'Updated settings for ' : 'Registered ') + draft.name)
  }

  function toggle(endpoint: Endpoint) {
    const enabled = endpoint.enabled === false
    update({ ...endpoint, enabled }, (enabled ? 'Enabled ' : 'Disabled ') + endpoint.name)
  }

  return { ...configuration, save, toggle }
}

export type StorageConfiguration = ReturnType<typeof useStorageConfiguration>
