import { useState, type FormEvent } from 'react'
import { useSaveConnection, useTestConnection } from '../../api/hooks/mutations'
import { isObjectStorage } from '../../api/mappers'
import { providerTypes, storageRoles, type CreateConnectionRequest, type ProviderType, type StorageConnectionDTO,
  type StorageRole } from '../../api/types'
import { Dialog } from '../../shared/ui/feedback'
import { Button } from '../../shared/ui/primitives'

export function ConnectionSettings({ connection, onClose }: { connection?: StorageConnectionDTO; onClose: () => void }) {
  const [provider, setProvider] = useState<ProviderType>(connection?.providerType ?? 'LOCAL')
  const [savedId, setSavedId] = useState(connection?.id)
  const save = useSaveConnection()
  const test = useTestConnection()
  const objectStorage = isObjectStorage(provider)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const value = (field: string) => String(form.get(field) ?? '')
    const payload: CreateConnectionRequest = {
      name: value('name').trim(), providerType: provider, role: value('role') as StorageRole,
      basePathOrBucket: value('location').trim(), endpointUrl: value('endpoint').trim() || null,
      region: value('region').trim() || null, active: form.has('active'),
      pathStyleAccessEnabled: objectStorage ? form.has('pathStyle') : undefined,
      accessKeyOrUsername: value('access').trim() || undefined, secretKeyOrPassword: value('secret') || undefined,
    }
    save.mutate({ id: savedId, payload }, { onSuccess: result => setSavedId(result.id) })
  }

  return <Dialog title={connection ? 'Edit storage connection' : 'Add storage connection'} onClose={onClose} drawer>
    <form onSubmit={submit}>
      <fieldset className="dialog-body" disabled={save.isPending}>
        <label>Name<input name="name" required maxLength={255} defaultValue={connection?.name} /></label>
        <label>Provider<select value={provider} onChange={event => setProvider(event.target.value as ProviderType)}>
          {providerTypes.map(item => <option key={item}>{item}</option>)}
        </select></label>
        <label>Role<select name="role" defaultValue={connection?.role ?? 'BOTH'}>
          {storageRoles.map(item => <option key={item}>{item}</option>)}
        </select></label>
        <label>{objectStorage ? 'Bucket' : provider === 'SMB' ? 'Absolute mount path or Windows UNC share' : 'Absolute root path'}
          <input name="location" required defaultValue={connection?.basePathOrBucket} /></label>
        {objectStorage && <>
          <label>Endpoint URL<input name="endpoint" type="url" required={provider !== 'AWS_S3'} defaultValue={connection?.endpointUrl ?? ''} /></label>
          <label>Region<input name="region" defaultValue={connection?.region ?? ''} placeholder="us-east-1" /></label>
          <label className="check-label"><input name="pathStyle" type="checkbox" defaultChecked={connection?.pathStyleAccessEnabled ?? true} />Path-style access</label>
        </>}
        {objectStorage && <>
          <label>Access key<input name="access" autoComplete="off" maxLength={255}
            required={objectStorage && provider !== 'AWS_S3' && !savedId} /></label>
          <label>Secret key<input name="secret" type="password" autoComplete="new-password"
            required={objectStorage && provider !== 'AWS_S3' && !savedId} /></label>
        </>}
        <label className="check-label"><input name="active" type="checkbox" defaultChecked={connection?.active ?? true} />Active</label>
      </fieldset>
      <div className="dialog-footer">
        <Button type="button" onClick={onClose}>Close</Button>
        <Button type="button" disabled={!savedId || test.isPending || save.isPending}
          onClick={() => { if (savedId) test.mutate(savedId) }}>Test Connection</Button>
        <Button type="submit" variant="primary" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save connection'}</Button>
      </div>
    </form>
  </Dialog>
}
