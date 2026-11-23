import { useState, type FormEvent } from 'react'
import { useArchiveMutation, useRecallMutation } from '../../api/hooks/mutations'
import type { ArchiveMode, CatalogLocationDTO, CatalogMetadataTarget } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { Dialog } from '../../shared/ui/feedback'
import { Button, Icon, count } from '../../shared/ui/primitives'
import { transferTargets, type TransferPlan } from './transferSelection'

export type CatalogTransferAction = 'archive' | 'recall'

const selectionPageSize = 20

function SelectionSummary({ items, location, plan, action }: {
  items: CatalogMetadataTarget[]; location: string; plan: TransferPlan; action: CatalogTransferAction
}) {
  const [page, setPage] = useState(0)
  const start = page * selectionPageSize
  const end = Math.min(start + selectionPageSize, items.length)
  const folders = items.filter(item => item.scope === 'FOLDER').length
  const files = items.length - folders
  const summary = [folders && `${count(folders)} ${folders === 1 ? 'folder' : 'folders'}`,
    files && `${count(files)} ${files === 1 ? 'file' : 'files'}`].filter(Boolean).join(' and ')

  return <section className="transfer-selection" aria-label="Selected folders and files">
    <div className="transfer-selection-heading">
      <h3>{summary} selected</h3>
      <p>{location}{plan.destinationId && <span> · Archive source: {plan.source.name}</span>}</p>
    </div>
    <ul className="transfer-selection-list">
      {items.slice(start, end).map(item => {
        const eligible = plan.scopeCounts.get(item.path)
        return <li key={item.scope + ':' + item.path}>
          <Icon name={item.scope === 'FOLDER' ? 'folder' : 'file'} size={16} />
          <span><span className="sr-only">{item.scope === 'FOLDER' ? 'Folder ' : 'File '}</span>{item.path || 'Storage root'}</span>
          <small>{eligible === undefined ? 'Covered by parent folder' : eligible === 0 ? 'Skipped'
            : item.scope === 'FILE' ? 'Included' : `${count(eligible)} eligible`}</small>
        </li>
      })}
    </ul>
    {items.length > selectionPageSize && <div className="transfer-selection-pages">
      <span aria-live="polite">{count(start + 1)}–{count(end)} of {count(items.length)} selected items</span>
      <Button disabled={!page} onClick={() => setPage(page - 1)}>Previous</Button>
      <Button disabled={end === items.length} onClick={() => setPage(page + 1)}>Next</Button>
    </div>}
    <p className="transfer-selection-note">
      <strong>{count(plan.fileCount)} {plan.fileCount === 1 ? 'eligible file' : 'eligible files'} in the catalog.</strong>
      {plan.skippedCount > 0 && <> {count(plan.skippedCount)} {plan.skippedCount === 1 ? 'file or link' : 'files or links'} skipped for this transfer.
        {' '}{action === 'archive' ? 'Only regular files can be archived.'
          : plan.destinationId ? 'Recall includes links to this archive source.' : 'Recall includes regular archive objects.'}</>}
      {folders > 0 && <> Folder selections include all pages and subfolders. Overlaps are counted once. Eligibility is checked again when the job runs.</>}
    </p>
  </section>
}

export function TransferDialog({ action, plans, selectedItems, selectedLocation, locations, onClose, onSubmitted }: {
  action: CatalogTransferAction; plans: TransferPlan[]; selectedItems: CatalogMetadataTarget[]; selectedLocation: string; locations: CatalogLocationDTO[]
  onClose: () => void; onSubmitted: () => void
}) {
  const { hasRole } = useAuth()
  const canPurge = hasRole('KOONJ_ADMIN')
  const [mode, setMode] = useState<ArchiveMode>('COPY')
  const [sourceId, setSourceId] = useState(plans[0].source.id)
  const plan = plans.find(candidate => candidate.source.id === sourceId) ?? plans[0]
  const targets = transferTargets(action, plan.source, locations)
    .filter(target => !plan.destinationId || target.id === plan.destinationId)
  const archive = useArchiveMutation()
  const recall = useRecallMutation()
  const isArchive = action === 'archive'
  const mutation = isArchive ? archive : recall
  const title = isArchive ? 'Archive to S3' : 'Recall to Source'
  const { source, paths, fileIds, targetFolderPaths } = plan

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const endpoints = { sourceConnectionId: source.id, targetConnectionId: String(form.get('target')) }
    const selection = paths.length ? { paths, ...(fileIds.length ? { fileIds } : {}) } : { fileIds }
    if (isArchive) archive.mutate({ ...endpoints, ...selection, storageClass: String(form.get('storageClass')),
      archiveMode: mode }, { onSuccess: onSubmitted })
    else recall.mutate({ ...endpoints, ...(targetFolderPaths?.length
      ? { targetFolderPaths, ...(fileIds.length ? { fileIds } : {}) } : selection),
      deleteFromTarget: form.has('deleteFromTarget') }, { onSuccess: onSubmitted })
  }

  return <Dialog title={title} className="catalog-transfer-dialog centered-form-dialog" onClose={() => { if (!mutation.isPending) onClose() }}>
    <form onSubmit={submit}>
      <div className="dialog-body">
        {plans.length > 1 && <label>Archive source<select value={sourceId} disabled={mutation.isPending}
          onChange={event => setSourceId(event.target.value)}>
          {plans.map(candidate => <option key={candidate.source.id} value={candidate.source.id}>
            {candidate.source.name} · {count(candidate.fileCount)} eligible
          </option>)}
        </select><small>Recall runs from one archive source per job.</small></label>}
        <SelectionSummary items={selectedItems} location={selectedLocation} plan={plan} action={action} />
        <label>Destination<select key={plan.source.id} name="target" required defaultValue={targets.length === 1 ? targets[0].id : ''}><option value="" disabled>Choose a destination</option>
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
      </div>
      <div className="dialog-footer"><Button type="button" onClick={onClose} disabled={mutation.isPending}>Cancel</Button>
        <Button type="submit" variant="primary" disabled={mutation.isPending || !targets.length}>
          {mutation.isPending ? 'Submitting…' : isArchive ? 'Submit archive' : 'Submit recall'}</Button></div>
    </form>
  </Dialog>
}
