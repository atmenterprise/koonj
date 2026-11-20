import { useEffect, useRef, useState } from 'react'
import { endpoints, type Endpoint } from './sampleData'

const key = 'koonj-storage-config-v1'
export type EndpointDraft = Pick<Endpoint, 'name' | 'origin' | 'path' | 'region' | 'connection'>
export type StorageCredentials =
  | { auth: 'password'; username: string; password: string; domain?: string }
  | { auth: 'aws-keys'; keyId: string; secretKey: string; sessionToken?: string }
type AuditEvent = { time: number; message: string }
type Configuration = { endpoints: Endpoint[]; audit: AuditEvent[] }
type Verification = { verified: boolean; message: string }

export function sameConnection(existing: Endpoint | undefined, draft: EndpointDraft) {
  const { connection } = draft
  return Boolean(existing && existing.origin === draft.origin && existing.path === draft.path && existing.region === draft.region
    && JSON.stringify(existing.connection) === JSON.stringify(connection))
}

export async function testStorageConnection(draft: EndpointDraft, secrets: StorageCredentials | undefined, signal: AbortSignal): Promise<Verification> {
  const keys = secrets?.auth === 'aws-keys' ? { keyId: secrets.keyId, secretKey: secrets.secretKey, sessionToken: secrets.sessionToken } : {}
  const response = await fetch('/api/v1/storage/connections/test', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, signal,
    body: JSON.stringify({ origin: draft.origin, path: draft.path, region: draft.region,
      endpointUrl: draft.connection?.endpointUrl, auth: draft.connection?.auth, ...keys }),
  })
  if (!response.ok || !response.headers.get('Content-Type')?.includes('application/json')) throw new Error('Connection checker unavailable')
  const result = await response.json()
  if (typeof result.verified !== 'boolean' || typeof result.message !== 'string') throw new Error('Invalid connection check response')
  return result
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
  const credentials = useRef(new Map<string, StorageCredentials>())

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

  function save(draft: EndpointDraft, existing?: Endpoint, secrets?: StorageCredentials, verified?: boolean) {
    const id = existing?.id ?? 'endpoint-' + crypto.getRandomValues(new Uint32Array(2)).join('-')
    const connectionUnchanged = sameConnection(existing, draft)
    if (secrets) credentials.current.set(id, secrets)
    else if (!connectionUnchanged) credentials.current.delete(id)
    update({
      id,
      items: existing?.items ?? 0,
      health: verified === true ? 'Online' : existing && connectionUnchanged && !secrets && verified === undefined ? existing.health : 'Not checked',
      verifiedAt: verified === true ? Date.now() : connectionUnchanged && !secrets && verified === undefined ? existing?.verifiedAt : undefined,
      enabled: existing?.enabled ?? true,
      detail: draft.region ?? existing?.detail ?? 'No scan history',
      ...draft,
    }, (existing ? 'Updated settings for ' : 'Registered ') + draft.name)
  }

  function toggle(endpoint: Endpoint) {
    const enabled = endpoint.enabled === false
    update({ ...endpoint, enabled }, (enabled ? 'Enabled ' : 'Disabled ') + endpoint.name)
  }

  return {
    ...configuration, save, toggle,
    hasCredentials: (id: string, auth: string) => credentials.current.get(id)?.auth === auth,
    credentialsFor: (id: string) => credentials.current.get(id),
    clearCredentials: () => credentials.current.clear(),
  }
}

export type StorageConfiguration = ReturnType<typeof useStorageConfiguration>
