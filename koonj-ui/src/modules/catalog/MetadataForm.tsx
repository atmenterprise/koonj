import type { CatalogMetadataTarget, CatalogMetadataValues } from '../../api/types'
import { Dialog } from '../../shared/ui/feedback'
import { useSaveCatalogMetadata } from './catalogMetadata'
import MetadataEditor, { MetadataValues } from './MetadataEditor'

export function MetadataForm({ connectionId, targets, direct, inherited, writable, bulk, onSaved }: {
  connectionId: string; targets: CatalogMetadataTarget[]; direct?: CatalogMetadataValues; inherited?: CatalogMetadataValues
  writable: boolean; bulk?: boolean; onSaved?: () => void
}) {
  const save = useSaveCatalogMetadata(connectionId)
  if (!writable) return <section className="metadata-inherited" aria-label="Custom metadata">
    <h3>Custom tags and properties</h3>
    {direct && <MetadataValues values={direct} />}
    {inherited && (inherited.tags.length > 0 || inherited.properties.length > 0) && <>
      <h3>Inherited from folders</h3><MetadataValues values={inherited} />
    </>}
    {!direct?.tags.length && !direct?.properties.length && !inherited?.tags.length && !inherited?.properties.length &&
      <p>No custom metadata.</p>}
  </section>

  return <MetadataEditor initial={direct} inherited={inherited} bulk={bulk} pending={save.isPending} requestError={save.error}
    onSave={async values => {
      await save.mutateAsync({ targets, values, mode: bulk ? 'MERGE' : 'REPLACE' })
      onSaved?.()
    }} />
}

export function BulkMetadataDialog({ connectionId, targets, onClose, onSaved }: {
  connectionId: string; targets: CatalogMetadataTarget[]; onClose: () => void; onSaved: () => void
}) {
  return <Dialog title="Edit selected metadata" onClose={onClose}>
    <div className="dialog-body">
      <p>Tags are added and properties with matching names are updated.</p>
      {targets.some(target => target.scope === 'FOLDER') && <p>Folder metadata is inherited by nested items.</p>}
      <MetadataForm connectionId={connectionId} targets={targets} writable bulk onSaved={onSaved} />
    </div>
  </Dialog>
}
