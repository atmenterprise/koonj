import { useState, type FormEvent } from 'react'
import { useArchiveMutation, useRecallMutation } from '../../api/hooks/mutations'
import type { ArchiveMode, CatalogLocationDTO } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { Dialog, RequestError } from '../../shared/ui/feedback'
import { Button, count } from '../../shared/ui/primitives'

export type CatalogTransferAction = 'archive' | 'recall'

export function TransferDialog({ action, fileIds, paths, selectionCount, source, targets, onClose, onSubmitted }: {
  action: CatalogTransferAction; fileIds: string[]; paths: string[]; selectionCount: number; source: CatalogLocationDTO; targets: CatalogLocationDTO[]
  onClose: () => void; onSubmitted: () => void
}) {
  const { hasRole } = useAuth()
  const canPurge = hasRole('KOONJ_ADMIN')
  const [mode, setMode] = useState<ArchiveMode>('COPY')
  const archive = useArchiveMutation()
  const recall = useRecallMutation()
  const isArchive = action === 'archive'
  const mutation = isArchive ? archive : recall
  const title = isArchive ? 'Archive to S3' : 'Recall to Source'

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const request = { sourceConnectionId: source.id, targetConnectionId: String(form.get('target')),
      ...(paths.length ? { paths, ...(fileIds.length ? { fileIds } : {}) } : { fileIds }) }
    if (isArchive) archive.mutate({ ...request, storageClass: String(form.get('storageClass')),
      archiveMode: mode }, { onSuccess: onSubmitted })
    else recall.mutate({ ...request, deleteFromTarget: form.has('deleteFromTarget') }, { onSuccess: onSubmitted })
  }

  return <Dialog title={title} onClose={() => { if (!mutation.isPending) onClose() }}>
    <form onSubmit={submit}>
      <div className="dialog-body">
        <p>{count(selectionCount)} selected · {source.name}</p>
        <label>Destination<select name="target" required defaultValue={targets.length === 1 ? targets[0].id : ''}><option value="" disabled>Choose a destination</option>
          {targets.map(target => <option value={target.id} key={target.id}>{target.name}</option>)}</select></label>
        {!targets.length && <p>No active destination connections support this transfer.</p>}
        {isArchive ? <>
          <fieldset className="archive-modes"><legend>Archive mode</legend>
            <label className="archive-mode"><input type="radio" name="archiveMode" value="COPY"
              checked={mode === 'COPY'} onChange={() => setMode('COPY')} />
              <span><strong>Copy</strong><small>Keep original files on the source server and store a copy in S3.</small></span>
            </label>
            <label className="archive-mode"><input type="radio" name="archiveMode" value="PURGE" disabled={!canPurge}
              checked={mode === 'PURGE'} onChange={() => setMode('PURGE')} />
              <span><strong>Purge</strong><small>Move files to S3, free up source disk space, and leave lightweight symlinks behind.</small>
                {!canPurge && <small>Requires an administrator.</small>}</span>
            </label>
          </fieldset>
          <label>Storage class<select name="storageClass" defaultValue="STANDARD">
            {['STANDARD', 'STANDARD_IA', 'INTELLIGENT_TIERING', 'GLACIER_IR'].map(value =>
              <option key={value}>{value}</option>)}
          </select></label>
        </> : <label className="check-label"><input type="checkbox" name="deleteFromTarget" />
          Remove the S3 copy after restoring</label>}
        <RequestError error={mutation.error} />
      </div>
      <div className="dialog-footer"><Button type="button" onClick={onClose} disabled={mutation.isPending}>Cancel</Button>
        <Button type="submit" variant="primary" disabled={mutation.isPending || !targets.length}>
          {mutation.isPending ? 'Submitting…' : isArchive ? 'Submit archive' : 'Submit recall'}</Button></div>
    </form>
  </Dialog>
}
