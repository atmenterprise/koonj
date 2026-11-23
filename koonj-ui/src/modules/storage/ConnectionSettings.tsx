import { useState, type FormEvent } from 'react'
import { useSaveConnection, useTestConnection } from '../../api/hooks/mutations'
import { isObjectStorage } from '../../api/mappers'
import { providerTypes, storageRoles, type CreateConnectionRequest, type ProviderType, type StorageConnectionDTO,
  type StorageRole } from '../../api/types'
import { Dialog } from '../../shared/ui/feedback'
import { Button } from '../../shared/ui/primitives'
import { providers, roles } from './StorageConnectivity'

export function ConnectionSettings({ connection, onClose }: { connection?: StorageConnectionDTO; onClose: () => void }) {
  const [provider, setProvider] = useState<ProviderType>(connection?.providerType ?? 'LOCAL')
  const [savedId, setSavedId] = useState(connection?.id)
  const save = useSaveConnection()
  const test = useTestConnection()
  const objectStorage = isObjectStorage(provider)
  const draftTestPassed = test.data?.healthy === true
  const fieldsDisabled = save.isPending || (!savedId && test.isPending)

  function readSettings(element: HTMLFormElement): CreateConnectionRequest {
    const form = new FormData(element)
    const value = (field: string) => String(form.get(field) ?? '')
    return {
      name: value('name').trim(), providerType: provider, role: value('role') as StorageRole,
      basePathOrBucket: value('location').trim(), endpointUrl: value('endpoint').trim() || null,
      region: value('region').trim() || null, active: form.has('active'),
      pathStyleAccessEnabled: objectStorage ? form.has('pathStyle') : undefined,
      accessKeyOrUsername: value('access').trim() || undefined, secretKeyOrPassword: value('secret') || undefined,
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (save.isPending || test.isPending) return
    const payload = readSettings(event.currentTarget)
    if (!savedId && (!draftTestPassed || JSON.stringify(payload) !== JSON.stringify(test.variables))) {
      test.reset()
      return
    }
    if (savedId) test.reset()
    save.mutate({ id: savedId, payload }, {
      onSuccess: result => {
        test.reset()
        setSavedId(result.id)
      },
    })
  }

  return <Dialog title={savedId ? 'Edit storage connection' : 'Add storage connection'}
    className="centered-form-dialog connection-dialog" onClose={() => { if (!save.isPending) onClose() }}>
    <form onSubmit={submit} onChange={() => { if (!savedId) test.reset() }}>
      <div className="dialog-body">
        <fieldset className="connection-form-section" disabled={fieldsDisabled}>
          <legend>Connection details</legend>
          <div className="connection-form-grid">
            <label className="connection-form-wide">Name<input name="name" required maxLength={255} defaultValue={connection?.name} /></label>
            <label>Provider<select value={provider} onChange={event => setProvider(event.target.value as ProviderType)}>
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
              <label>Endpoint URL<input name="endpoint" type="url" required={provider !== 'AWS_S3'} defaultValue={connection?.endpointUrl ?? ''} /></label>
              <label>Region<input name="region" defaultValue={connection?.region ?? ''} placeholder="us-east-1" /></label>
              <label className="connection-form-wide check-label"><input name="pathStyle" type="checkbox" defaultChecked={connection?.pathStyleAccessEnabled ?? true} />Path-style access</label>
            </>}
          </div>
        </fieldset>
        {objectStorage && <fieldset className="connection-form-section" disabled={fieldsDisabled}>
          <legend>Credentials</legend>
          <div className="connection-form-grid">
            <label>Access key<input name="access" autoComplete="off" maxLength={255} required={provider !== 'AWS_S3' && !savedId} /></label>
            <label>Secret key<input name="secret" type="password" autoComplete="new-password" required={provider !== 'AWS_S3' && !savedId} /></label>
            {savedId && <p className="connection-form-wide connection-form-note">Leave these fields blank to keep the saved credentials.</p>}
          </div>
        </fieldset>}
        <div className="connection-form-result" aria-live="polite">
          {save.isError && <p className="connection-feedback connection-feedback-error">{save.error.message}</p>}
          {test.isError ? <p className="connection-feedback connection-feedback-error">{test.error.message}</p>
            : test.data ? <p className={test.data.healthy ? 'connection-feedback' : 'connection-feedback connection-feedback-error'}>{test.data.message}</p>
              : <p className="connection-form-note">{save.isSuccess ? 'Connection saved. ' : ''}
                {savedId ? 'Tests use the saved settings.' : test.isPending ? 'Testing the connection…'
                  : 'Test the connection successfully before saving.'}</p>}
        </div>
      </div>
      <div className="dialog-footer">
        <Button type="button" disabled={save.isPending} onClick={onClose}>Close</Button>
        <Button type="button" disabled={test.isPending || save.isPending} onClick={event => {
          const form = event.currentTarget.form
          if (savedId) test.mutate(savedId)
          else if (form?.reportValidity()) test.mutate(readSettings(form))
        }}>{test.isPending ? 'Testing…' : 'Test Connection'}</Button>
        <Button type="submit" variant="primary" disabled={save.isPending || test.isPending || (!savedId && !draftTestPassed)}>
          {save.isPending ? 'Saving…' : 'Save connection'}</Button>
      </div>
    </form>
  </Dialog>
}
