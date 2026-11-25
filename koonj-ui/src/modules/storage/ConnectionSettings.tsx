import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { invalidateConnections } from '../../api/hooks/mutations'
import { isObjectStorage } from '../../api/mappers'
import { connectionService } from '../../api/services/connectionService'
import { providerTypes, storageRoles, type CreateConnectionRequest, type ProviderType, type StorageConnectionDTO,
  type StorageRole, type StorageAuthMode, type ConnectionHealthDTO } from '../../api/types'
import { Dialog } from '../../shared/ui/feedback'
import { Button } from '../../shared/ui/primitives'
import { providers, roles } from './StorageConnectivity'
import { authModes, availableAuthModes, sameStorageSettings } from './storageAuthentication'

export function ConnectionSettings({ connection, onClose }: { connection?: StorageConnectionDTO; onClose: () => void }) {
  const legacyAwsEndpoint = connection?.authMode === 'MIGRATION_REQUIRED'
    && connection.providerType === 'AWS_S3' && !!connection.endpointUrl
  const [provider, setProvider] = useState<ProviderType>(legacyAwsEndpoint ? 'CUSTOM_S3' : connection?.providerType ?? 'LOCAL')
  const [selectedMode, setSelectedMode] = useState<StorageAuthMode | undefined>(connection?.authMode)
  const [roleArn, setRoleArn] = useState(connection?.awsRoleArn ?? '')
  const options = useQuery({
    queryKey: ['storage-auth-options'],
    queryFn: ({ signal }) => connectionService.getAuthOptions(signal),
  })
  const externalId = useMutation({ mutationFn: connectionService.getAwsExternalId })
  const modes: readonly Exclude<StorageAuthMode, 'MIGRATION_REQUIRED'>[] =
    availableAuthModes(provider, options.data?.awsDefaultChainAllowed ?? true,
      options.data?.encryptedCredentialsAvailable ?? false)
  const authMode = modes.find(mode => mode === selectedMode) ?? modes[0]
  const displayedExternalId = externalId.variables === roleArn.trim()
    ? externalId.data?.externalId
    : connection?.awsRoleArn === roleArn.trim() ? connection?.awsExternalId : undefined
  const [saved, setSaved] = useState(connection)
  const [pending, setPending] = useState<'save' | 'test'>()
  const [health, setHealth] = useState<ConnectionHealthDTO>()
  const [feedback, setFeedback] = useState<{ error: boolean; message: string }>()
  const client = useQueryClient()
  const objectStorage = isObjectStorage(provider)
  const encrypted = authMode === 'ENCRYPTED_CREDENTIALS'
  const encryptionDisabled = encrypted && options.data?.encryptedCredentialsAvailable !== true
  const needsCredentials = encrypted && saved?.authMode !== 'ENCRYPTED_CREDENTIALS'
  const fieldsDisabled = pending !== undefined

  function readSettings(element: HTMLFormElement): CreateConnectionRequest {
    const form = new FormData(element)
    const value = (field: string) => String(form.get(field) ?? '')
    const accessKeyId = value('accessKeyId')
    const secretAccessKey = value('secretAccessKey')
    if (encrypted && Boolean(accessKeyId) !== Boolean(secretAccessKey)) {
      throw new Error('Enter both the access key ID and secret access key.')
    }
    return {
      credentials: encrypted && accessKeyId ? { accessKeyId, secretAccessKey } : undefined,
      name: value('name').trim(), providerType: provider, role: value('role') as StorageRole,
      basePathOrBucket: value('location').trim(), endpointUrl: value('endpoint').trim() || null,
      region: value('region').trim() || null, active: form.has('active'),
      pathStyleAccessEnabled: objectStorage ? form.has('pathStyle') : undefined,
      authMode,
      secretRef: authMode === 'SECRET_REF' ? value('secretRef').trim() || undefined : undefined,
      awsRoleArn: authMode === 'AWS_ASSUME_ROLE' ? roleArn.trim() || undefined : undefined,
      objectPrefix: objectStorage ? value('objectPrefix').trim() || undefined : undefined,
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending || encryptionDisabled || (!saved && !health?.healthy)) return
    const form = event.currentTarget
    setFeedback(undefined)
    setPending('save')
    try {
      const payload = readSettings(form)
      const rotating = saved && payload.credentials && sameStorageSettings(payload, saved)
      const result = rotating && payload.credentials
        ? await connectionService.rotateCredential(saved.id, payload.credentials)
        : saved ? await connectionService.updateConnection(saved.id, payload)
          : await connectionService.createConnection(payload)
      for (const field of ['accessKeyId', 'secretAccessKey']) {
        const input = form.elements.namedItem(field)
        if (input instanceof HTMLInputElement) input.value = ''
      }
      setSaved(result)
      setHealth(undefined)
      setFeedback({ error: false, message: rotating ? 'Storage credential rotated.' : 'Connection saved.' })
      await invalidateConnections(client)
    } catch (error) {
      setFeedback({ error: true, message: error instanceof Error ? error.message : 'Could not save the connection.' })
    } finally {
      setPending(undefined)
    }
  }

  async function test(form: HTMLFormElement) {
    if (pending || encryptionDisabled || !form.reportValidity()) return
    setFeedback(undefined)
    setHealth(undefined)
    setPending('test')
    try {
      const payload = readSettings(form)
      if (saved && !payload.credentials && sameStorageSettings(payload, saved)) {
        setHealth(await connectionService.testConnection(saved.id))
      } else {
        setHealth(await connectionService.testDraft(payload))
      }
    } catch (error) {
      setFeedback({ error: true, message: error instanceof Error ? error.message : 'Could not test the connection.' })
    } finally {
      setPending(undefined)
    }
  }

  return <Dialog title={saved ? 'Edit storage connection' : 'Add storage connection'}
    className="centered-form-dialog connection-dialog" onClose={() => { if (!pending) onClose() }}>
    <form onSubmit={submit} onChange={() => { setHealth(undefined); setFeedback(undefined) }}>
      <div className="dialog-body">
        <fieldset className="connection-form-section" disabled={fieldsDisabled}>
          <legend>Connection details</legend>
          <div className="connection-form-grid">
            <label className="connection-form-wide">Name<input name="name" required maxLength={255} defaultValue={connection?.name} /></label>
            <label>Provider<select value={provider} onChange={event => {
              setProvider(event.target.value as ProviderType)
              setSelectedMode(undefined)
            }}>
              {providerTypes.map(item => <option key={item} value={item}>{providers[item]}</option>)}
            </select></label>
            <label>Role<select name="role" defaultValue={connection?.role ?? 'BOTH'}>
              {storageRoles.map(item => <option key={item} value={item}>{roles[item]}</option>)}
            </select></label>
            <label className="connection-form-wide check-label"><input name="active" type="checkbox" defaultChecked={connection?.active ?? true} />Active</label>
          </div>
        </fieldset>
        <fieldset className="connection-form-section" disabled={fieldsDisabled}>
          <legend>{objectStorage ? 'Bucket settings' : 'Mount settings'}</legend>
          <div className="connection-form-grid">
            <label className="connection-form-wide">{objectStorage ? 'Bucket' : provider === 'SMB' ? 'Absolute mount path or Windows UNC share' : 'Absolute root path'}
              <input name="location" required defaultValue={connection?.basePathOrBucket} /></label>
            {objectStorage && <>
              {provider !== 'AWS_S3' && <label>Endpoint URL<input name="endpoint" type="url" required
                defaultValue={connection?.endpointUrl ?? ''} /></label>}
              <label>Object prefix<input name="objectPrefix" maxLength={255} defaultValue={connection?.objectPrefix ?? ''} /></label>
              <label>Region<input name="region" defaultValue={connection?.region ?? ''} placeholder="us-east-1" /></label>
              <label className="connection-form-wide check-label"><input name="pathStyle" type="checkbox" defaultChecked={connection?.pathStyleAccessEnabled ?? true} />Path-style access</label>
            </>}
          </div>
        </fieldset>
        <fieldset className="connection-form-section" disabled={fieldsDisabled}>
          <legend>Authentication</legend>
          <div className="connection-form-grid">
            {modes.length > 1
              ? <label>Method<select value={authMode} onChange={event => setSelectedMode(event.target.value as StorageAuthMode)}>
                {modes.map(mode => <option key={mode} value={mode}>{authModes[mode]}</option>)}
              </select></label>
              : <p className="connection-form-note">{authModes[authMode]}</p>}
            {authMode === 'SECRET_REF' && <label>Secret reference<input name="secretRef" required maxLength={87}
              autoComplete="off" placeholder="aws-sm:archive-storage" defaultValue={connection?.secretRef ?? ''} />
              <small>Use the alias provisioned for this project by your secrets administrator.</small>
            </label>}
            {encrypted && <>
              <label>Access key ID<input name="accessKeyId" required={needsCredentials} maxLength={256}
                autoComplete="off" /></label>
              <label>Secret access key<input name="secretAccessKey" type="password" required={needsCredentials}
                maxLength={4096} autoComplete="new-password" /></label>
              {encryptionDisabled && <p className="connection-form-wide connection-form-note">
                A deployment administrator must configure a wrapping key to enable encrypted access keys.
              </p>}
              {saved?.authMode === 'ENCRYPTED_CREDENTIALS' && <p className="connection-form-wide connection-form-note">
                Leave both fields blank to keep the saved keys. Enter replacements to rotate them.
              </p>}
            </>}
            {authMode === 'AWS_ASSUME_ROLE' && <>
              <label className="connection-form-wide">IAM role ARN<input name="awsRoleArn" required maxLength={255}
                value={roleArn} onChange={event => { setRoleArn(event.target.value); externalId.reset() }}
                placeholder="arn:aws:iam::123456789012:role/koonj-storage" /></label>
              <Button type="button" disabled={!roleArn.trim() || externalId.isPending}
                onClick={() => externalId.mutate(roleArn.trim())}>Get external ID</Button>
              {displayedExternalId && <label className="connection-form-wide">External ID for the AWS trust policy
                <input value={displayedExternalId} readOnly />
              </label>}
              {externalId.isError && <p className="connection-feedback connection-feedback-error">{externalId.error.message}</p>}
            </>}
            {connection?.authMode === 'MIGRATION_REQUIRED' && <p className="connection-form-wide connection-form-note">
              Choose a new authentication method to restore access to this connection.
            </p>}
          </div>
        </fieldset>
        <div className="connection-form-result" aria-live="polite">
          {feedback && <p className={feedback.error ? 'connection-feedback connection-feedback-error' : 'connection-feedback'}>
            {feedback.message}</p>}
          {health ? <p className={health.healthy ? 'connection-feedback' : 'connection-feedback connection-feedback-error'}>
            {health.message}</p> : !feedback && <p className="connection-form-note">
            {pending === 'test' ? 'Testing the connection…' : saved ? 'Test any changes before saving.'
              : 'Test the connection successfully before saving.'}</p>}
        </div>
      </div>
      <div className="dialog-footer">
        <Button type="button" disabled={fieldsDisabled} onClick={onClose}>Close</Button>
        <Button type="button" disabled={fieldsDisabled || encryptionDisabled} onClick={event => {
          if (event.currentTarget.form) void test(event.currentTarget.form)
        }}>{pending === 'test' ? 'Testing…' : 'Test Connection'}</Button>
        <Button type="submit" variant="primary"
          disabled={fieldsDisabled || encryptionDisabled || (!saved && !health?.healthy)}>
          {pending === 'save' ? 'Saving…' : 'Save connection'}</Button>
      </div>
    </form>
  </Dialog>
}
