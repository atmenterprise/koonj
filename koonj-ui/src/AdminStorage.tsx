import { Fragment, useEffect, useRef, useState, type FormEvent } from 'react'
import type { Endpoint, Origin, S3Provider, StorageAuth } from './sampleData'
import { sameConnection, testStorageConnection, type EndpointDraft, type StorageConfiguration, type StorageCredentials } from './storageConfiguration'
import { Badge, Button, Icon, date, time } from './ui'

const protocols: { value: Origin | S3Provider; origin: Origin; label: string }[] = [
  { value: 'LOCAL', origin: 'LOCAL', label: 'Local file directory' },
  { value: 'NAS', origin: 'NAS', label: 'NAS mount' },
  { value: 'SMB', origin: 'SMB', label: 'SMB share' },
  { value: 'aws', origin: 'S3', label: 'Amazon S3' },
  { value: 'minio', origin: 'S3', label: 'MinIO' },
  { value: 'scality', origin: 'S3', label: 'Scality S3' },
  { value: 'compatible', origin: 'S3', label: 'Other S3-compatible storage' },
]

const authLabels: Record<StorageAuth, string> = {
  'host-mount': 'Use host mount', password: 'Username and password', 'aws-default': 'Use AWS default credentials',
  'aws-keys': 'AWS access keys', reference: 'Credential reference',
}
const defaultAuth = (origin: Origin) => origin === 'S3' ? 'aws-default' : 'host-mount'
const authMethods = (origin: Origin): StorageAuth[] => origin === 'S3' ? ['aws-default', 'aws-keys', 'reference']
  : origin === 'LOCAL' ? ['host-mount'] : ['host-mount', 'password', 'reference']

function EndpointForm({ editor, configuration, onClose }: {
  editor: { endpoint?: Endpoint }; configuration: StorageConfiguration; onClose: () => void
}) {
  const endpoint = editor.endpoint
  const [origin, setOrigin] = useState<Origin>(endpoint?.origin ?? 'LOCAL')
  const [provider, setProvider] = useState<S3Provider>(endpoint?.connection?.provider ?? (endpoint?.connection?.endpointUrl ? 'compatible' : 'aws'))
  const [name, setName] = useState(endpoint?.name ?? '')
  const [location, setLocation] = useState(endpoint?.path.replace(/^s3:\/\//, '') ?? '')
  const [region, setRegion] = useState(endpoint?.region ?? '')
  const [auth, setAuth] = useState<StorageAuth>(endpoint?.connection?.auth ?? defaultAuth(origin))
  const [server, setServer] = useState(endpoint?.connection?.server ?? '')
  const [remotePath, setRemotePath] = useState(endpoint?.connection?.remotePath ?? '')
  const [endpointUrl, setEndpointUrl] = useState(endpoint?.connection?.endpointUrl ?? '')
  const [reference, setReference] = useState(endpoint?.connection?.credentialReference ?? '')
  const [error, setError] = useState('')
  const [verification, setVerification] = useState<{ verified: boolean; message: string }>()
  const [testing, setTesting] = useState(false)
  const activeTest = useRef<AbortController>(null)
  const isRemoteSource = origin === 'NAS' || origin === 'SMB'
  const hasCredentials = Boolean(endpoint && configuration.hasCredentials(endpoint.id, auth))

  useEffect(() => () => activeTest.current?.abort(), [])

  function resetVerification() {
    activeTest.current?.abort()
    setTesting(false)
    setVerification(undefined)
    setError('')
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const testingConnection = (event.nativeEvent as SubmitEvent).submitter?.getAttribute('value') === 'test'
    setError('')
    if (testingConnection) setVerification(undefined)
    const resource = location.trim()
    const draft: EndpointDraft = { name: name.trim(), origin, path: origin === 'S3' ? 's3://' + resource : resource,
      region: origin === 'S3' ? region.trim() : undefined,
      connection: {
        auth,
        provider: origin === 'S3' ? provider : undefined,
        server: isRemoteSource ? server.trim() || undefined : undefined,
        remotePath: isRemoteSource ? remotePath.trim() || undefined : undefined,
        endpointUrl: origin === 'S3' ? endpointUrl.trim() || undefined : undefined,
        credentialReference: auth === 'reference' ? reference.trim() : undefined,
      },
    }
    if (!draft.name || !resource || (origin === 'S3' && !draft.region)) {
      setError('Complete all fields.')
      return
    }
    if (origin === 'S3' && (!/^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(resource) || resource.includes('..') || /^\d+(\.\d+){3}$/.test(resource))) {
      setError('Enter a valid bucket name without the s3:// prefix.')
      return
    }
    if (draft.region && !/^[a-z][a-z0-9]*(-[a-z0-9]+)+-[0-9]+$/.test(draft.region)) {
      setError('Enter an AWS region such as eu-west-2.')
      return
    }
    if (origin === 'S3' && provider !== 'aws' && !draft.connection?.endpointUrl) {
      setError('Enter the endpoint URL for this S3-compatible storage.')
      return
    }
    if (origin !== 'S3' && !resource.startsWith('/')) {
      setError('Enter an absolute mount path or an SMB share beginning with //.')
      return
    }
    if (configuration.endpoints.some(item => item.id !== endpoint?.id && item.path === draft.path)) {
      setError('This storage location is already registered.')
      return
    }
    if (draft.connection?.endpointUrl) {
      const url = URL.canParse(draft.connection.endpointUrl) ? new URL(draft.connection.endpointUrl) : undefined
      if (!url || !['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
        setError('Enter an HTTP or HTTPS endpoint URL without credentials or query parameters.')
        return
      }
    }
    if (auth === 'reference' && !draft.connection?.credentialReference) {
      setError('Enter a credential reference name.')
      return
    }
    const fields = new FormData(event.currentTarget)
    const value = (field: string) => String(fields.get(field) ?? '')
    const newAuth = endpoint?.connection?.auth !== auth
    let secrets: StorageCredentials | undefined
    if (auth === 'password') {
      const username = value('username').trim()
      const password = value('password')
      const domain = value('domain').trim()
      if (newAuth || username || password || domain) {
        if (!username || !password) { setError('Enter both the username and password.'); return }
        secrets = { auth, username, password, domain: domain || undefined }
      }
    } else if (auth === 'aws-keys') {
      const keyId = value('keyId').trim()
      const secretKey = value('secretKey')
      const sessionToken = value('sessionToken').trim()
      if (newAuth || keyId || secretKey || sessionToken) {
        if (!keyId || !secretKey) { setError('Enter both the access key ID and secret access key.'); return }
        secrets = { auth, keyId, secretKey, sessionToken: sessionToken || undefined }
      }
    }
    if (testingConnection) {
      activeTest.current?.abort()
      const controller = new AbortController()
      activeTest.current = controller
      const timeout = window.setTimeout(() => controller.abort('timeout'), 10000)
      setTesting(true)
      try {
        const cached = endpoint && sameConnection(endpoint, draft) ? configuration.credentialsFor(endpoint.id) : undefined
        const result = await testStorageConnection(draft, secrets ?? cached, controller.signal)
        if (!controller.signal.aborted) setVerification(result)
      } catch {
        if (!controller.signal.aborted) setError('The connection checker could not be reached. Check that the Koonj server is running.')
        else if (controller.signal.reason === 'timeout') setError('The connection test timed out.')
      } finally {
        window.clearTimeout(timeout)
        if (activeTest.current === controller) {
          activeTest.current = null
          setTesting(false)
        }
      }
      return
    }
    configuration.save(draft, endpoint, secrets, verification?.verified)
    onClose()
  }

  return <form className="panel admin-editor" onSubmit={submit} onChange={resetVerification} aria-label={endpoint ? 'Edit storage connection' : 'Add storage connection'}>
    <div className="panel-heading"><h2>{endpoint ? 'Edit connection' : 'Add connection'}</h2>
      <Button className="icon-button" icon="close" aria-label="Close editor" onClick={onClose} /></div>
    <div className="admin-form-fields">
      <label>Display name<input required value={name} onChange={event => setName(event.target.value)} /></label>
      <label>Storage type<select value={origin === 'S3' ? provider : origin} onChange={event => {
        const next = protocols.find(item => item.value === event.target.value)
        if (!next) return
        setOrigin(next.origin)
        if (next.origin === 'S3') {
          setProvider(next.value as S3Provider)
          setAuth(next.value === 'aws' ? 'aws-default' : 'aws-keys')
        } else setAuth(defaultAuth(next.origin))
      }} disabled={Boolean(endpoint && endpoint.origin !== 'S3')}>
        {protocols.filter(protocol => !endpoint || protocol.origin === endpoint.origin).map(protocol => <option value={protocol.value} key={protocol.value}>{protocol.label}</option>)}
      </select></label>
      <label>{origin === 'S3' ? 'Bucket name' : 'Mount path'}<input required value={location} onChange={event => setLocation(event.target.value)} placeholder={origin === 'S3' ? 'example-archive' : '/mnt/storage'} /></label>
      {origin === 'S3' && <label>Region<input required value={region} onChange={event => setRegion(event.target.value)} placeholder={provider === 'aws' ? 'eu-west-2' : 'us-east-1'} /></label>}
      {origin === 'S3' && <label>{provider === 'aws' ? 'S3 endpoint URL (optional)' : 'S3 endpoint URL'}<input required={provider !== 'aws'} type="url" value={endpointUrl} onChange={event => setEndpointUrl(event.target.value)} placeholder="https://s3.example.com" /></label>}
      {isRemoteSource && <>
        <label>Server address (optional)<input value={server} onChange={event => setServer(event.target.value)} placeholder="nas-01.example.com" /></label>
        <label>{origin === 'SMB' ? 'Share (optional)' : 'Export path (optional)'}<input value={remotePath} onChange={event => setRemotePath(event.target.value)} placeholder={origin === 'SMB' ? 'media' : '/exports/media'} /></label>
      </>}
    </div>
    <fieldset className="admin-credentials">
      <legend>Credentials</legend>
      <div className="admin-form-fields">
        <label>Authentication method<select value={auth} onChange={event => setAuth(event.target.value as StorageAuth)}>
          {authMethods(origin).map(method => <option key={method} value={method}>{authLabels[method]}</option>)}
        </select></label>
        <Fragment key={auth}>
        {auth === 'reference' && <label>Credential reference name<input required value={reference} onChange={event => setReference(event.target.value)} placeholder="storage/archive" /></label>}
        {auth === 'password' && <>
          <label>Username<input name="username" autoComplete="off" placeholder={hasCredentials ? 'Enter to replace' : undefined} /></label>
          <label>Password<input name="password" type="password" autoComplete="new-password" /></label>
          <label>Domain (optional)<input name="domain" autoComplete="off" /></label>
        </>}
        {auth === 'aws-keys' && <>
          <label>AWS access key ID<input name="keyId" autoComplete="off" placeholder={hasCredentials ? 'Enter to replace' : undefined} /></label>
          <label>AWS secret access key<input name="secretKey" type="password" autoComplete="new-password" /></label>
          <label>AWS session token (optional)<input name="sessionToken" type="password" autoComplete="new-password" /></label>
        </>}
        </Fragment>
      </div>
      {(auth === 'password' || auth === 'aws-keys') && <p>{hasCredentials ? 'Leave these fields blank to keep the current session credentials.' : 'Entered credentials are kept in memory for this page session. Use a credential reference for settings that need to survive a reload.'}</p>}
      {auth === 'reference' && <p>Enter the name of the stored credential. The reference is saved with this endpoint.</p>}
      {isRemoteSource && <p>To verify a NAS or SMB share, mount it on the Koonj server and use its mount path with Use host mount.</p>}
    </fieldset>
    {error && <p className="error-text" role="alert">{error}</p>}
    {verification && <p className={verification.verified ? 'admin-notice' : 'error-text'} role="status">{verification.message}</p>}
    <div className="row-actions">
      <Button onClick={onClose}>Cancel</Button>
      <Button icon="activity" type="submit" value="test" disabled={testing}>{testing ? 'Testing…' : 'Test connection'}</Button>
      <Button variant="primary" type="submit" disabled={testing}>Save connection</Button>
    </div>
  </form>
}

export default function AdminStorage({ configuration }: { configuration: StorageConfiguration }) {
  const [editor, setEditor] = useState<{ endpoint?: Endpoint }>()
  const [notice, setNotice] = useState('')
  return <div className="admin-stack">
    <div className="panel-heading"><div><h2>Storage configuration</h2><p>Add, verify, and manage storage connections.</p></div>
      <Button variant="primary" icon="server" onClick={() => setEditor({})}>Add connection</Button></div>
    {editor && <EndpointForm key={editor.endpoint?.id ?? 'new'} editor={editor} configuration={configuration} onClose={() => setEditor(undefined)} />}
    {notice && <p className="admin-notice" role="status">{notice}</p>}
    {([false, true] as const).map(target => <section className="panel admin-group" key={String(target)}>
      <div className="panel-heading"><h2>{target ? 'S3-compatible targets' : 'Source directories'}</h2></div>
      <ul className="admin-endpoints">{configuration.endpoints.filter(endpoint => (endpoint.origin === 'S3') === target).map(endpoint => <li key={endpoint.id}>
        <span className="icon-tile neutral"><Icon name={target ? 'cloud' : 'server'} size={16} /></span>
        <div className="admin-endpoint-label"><strong>{endpoint.name}</strong><small>{endpoint.path}{endpoint.region && ' · ' + endpoint.region}</small>
          {endpoint.origin === 'S3' && <small>{protocols.find(item => item.value === (endpoint.connection?.provider ?? 'aws'))?.label}</small>}
          {endpoint.connection && <small>{authLabels[endpoint.connection.auth]}
            {(endpoint.connection.auth === 'password' || endpoint.connection.auth === 'aws-keys') &&
              (configuration.hasCredentials(endpoint.id, endpoint.connection.auth) ? ' · Entered for this session' : ' · Credentials required')}</small>}
        </div>
        {endpoint.verifiedAt && <Badge tone="green" dot>Verified {date(endpoint.verifiedAt)} · {time(endpoint.verifiedAt)}</Badge>}
        <Badge tone={endpoint.enabled === false || endpoint.health === 'Not checked' ? 'neutral' : endpoint.health === 'Online' ? 'green' : 'blue'} dot>{endpoint.enabled === false ? 'Disabled' : endpoint.health}</Badge>
        <div className="row-actions">
          <Button aria-label={'Edit ' + endpoint.name} onClick={() => setEditor({ endpoint })}>Edit</Button>
          <Button aria-label={(endpoint.enabled === false ? 'Enable ' : 'Disable ') + endpoint.name} onClick={() => {
            configuration.toggle(endpoint)
            setNotice(endpoint.enabled === false ? 'Endpoint enabled for operators.' : 'Endpoint disabled for operators.')
          }}>{endpoint.enabled === false ? 'Enable' : 'Disable'}</Button>
        </div>
      </li>)}</ul>
    </section>)}
  </div>
}
