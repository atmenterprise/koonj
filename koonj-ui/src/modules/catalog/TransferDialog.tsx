import type { FormEvent } from 'react'
import { useArchiveMutation, useRecallMutation } from '../../api/hooks/mutations'
import type { CatalogLocationDTO } from '../../api/types'
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
  const archive = useArchiveMutation()
  const recall = useRecallMutation()
  const isArchive = action === 'archive'
  const mutation = isArchive ? archive : recall
  const title = isArchive ? 'Archive to S3' : 'Recall to Local/NAS'

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const request = { sourceConnectionId: source.id, targetConnectionId: String(form.get('target')),
      ...(paths.length ? { paths, ...(fileIds.length ? { fileIds } : {}) } : { fileIds }) }
    if (isArchive) archive.mutate({ ...request, storageClass: String(form.get('storageClass')),
      purgeSource: canPurge && form.has('purgeSource') }, { onSuccess: onSubmitted })
    else recall.mutate(request, { onSuccess: onSubmitted })
  }

  return <Dialog title={title} onClose={() => { if (!mutation.isPending) onClose() }}>
    <form onSubmit={submit}>
      <div className="dialog-body">
        <p>{count(selectionCount)} selected · {source.name}</p>
        <label>Destination<select name="target" required defaultValue=""><option value="" disabled>Choose a destination</option>
          {targets.map(target => <option value={target.id} key={target.id}>{target.name}</option>)}</select></label>
        {!targets.length && <p>No active destination connections support this transfer.</p>}
        {isArchive && <>
          <label>Storage class<select name="storageClass" defaultValue="STANDARD">
            {['STANDARD', 'STANDARD_IA', 'INTELLIGENT_TIERING', 'GLACIER_IR', 'GLACIER', 'DEEP_ARCHIVE'].map(value =>
              <option key={value}>{value}</option>)}
          </select></label>
          {canPurge && <label className="check-label"><input type="checkbox" name="purgeSource" />Purge source after verification</label>}
        </>}
        <RequestError error={mutation.error} />
      </div>
      <div className="dialog-footer"><Button type="button" onClick={onClose} disabled={mutation.isPending}>Cancel</Button>
        <Button type="submit" variant="primary" disabled={mutation.isPending || !targets.length}>
          {mutation.isPending ? 'Submitting…' : isArchive ? 'Submit archive' : 'Submit recall'}</Button></div>
    </form>
  </Dialog>
}
