import { useState, type FormEvent } from 'react'
import { emptyMetadata, mergeMetadata } from './catalogMetadata'
import type { CatalogMetadataValues as CustomMetadata } from '../../api/types'
import { useActivityNotifications } from '../../shared/notifications/ActivityNotifications'
import { Badge, Button } from '../../shared/ui/primitives'

const tagTones = ['blue', 'purple', 'green', 'yellow', 'red']
function tagTone(tag: string) {
  let hash = 0
  for (const character of tag.toLowerCase()) hash = (hash * 31 + character.codePointAt(0)!) % tagTones.length
  return tagTones[hash]
}

export function MetadataTags({ tags }: { tags: string[] }) {
  return tags.length > 0 && <ul className="metadata-tag-list" aria-label="Tags">
    {tags.map(tag => <li key={tag}><Badge tone={tagTone(tag)}>{tag}</Badge></li>)}
  </ul>
}

export function MetadataValues({ values }: { values: CustomMetadata }) {
  return <>
    <MetadataTags tags={values.tags} />
    {values.properties.length > 0 && <dl className="metadata-property-list">
      {values.properties.map(({ name, value }) => <div key={name}><dt>{name}</dt><dd>{value}</dd></div>)}
    </dl>}
  </>
}

type PropertyRow = CustomMetadata['properties'][number] & { id: number }

export default function MetadataEditor({ initial = emptyMetadata, inherited = emptyMetadata, bulk = false, formId, pending, onSave }: {
  initial?: CustomMetadata; inherited?: CustomMetadata; bulk?: boolean; formId?: string; pending?: boolean; onSave: (metadata: CustomMetadata) => Promise<void>
}) {
  const [tags, setTags] = useState(initial.tags)
  const [tagDraft, setTagDraft] = useState('')
  const [properties, setProperties] = useState<PropertyRow[]>(() => initial.properties.length
    ? initial.properties.map((property, id) => ({ ...property, id }))
    : [{ id: 0, name: '', value: '' }])
  const [nextId, setNextId] = useState(properties.length)
  const { notify } = useActivityNotifications()

  function changeProperty(id: number, field: 'name' | 'value', value: string) {
    setProperties(rows => rows.map(row => row.id === id ? { ...row, [field]: value } : row))
  }

  function updatedTags() {
    return mergeMetadata({ ...emptyMetadata, tags }, {
      ...emptyMetadata, tags: tagDraft.split(/[,\n]/).map(tag => tag.trim()).filter(Boolean),
    }).tags
  }

  function addTags() {
    setTags(updatedTags())
    setTagDraft('')
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const description = new FormData(event.currentTarget).get('description')
    const pairs = properties.map(({ name, value }) => ({ name: name.trim(), value: value.trim() }))
    if (pairs.some(({ name, value }) => (!name && value) || (name && !value))) {
      notify({ tone: 'error', title: 'Check custom properties',
        detail: 'Enter both a name and a value for each property, or remove the row.' })
      return
    }
    const values = mergeMetadata(emptyMetadata, {
      tags: updatedTags(),
      properties: pairs.filter(pair => pair.name),
    })
    if (bulk && !values.tags.length && !values.properties.length) {
      notify({ tone: 'error', title: 'Add at least one tag or property' })
      return
    }
    try {
      await onSave(typeof description === 'string' ? { ...values, description } : values)
    } catch { return }
    setTags(values.tags)
    setTagDraft('')
  }

  return <form id={formId} className="metadata-editor" onSubmit={event => void submit(event)}>
    {(inherited.tags.length > 0 || inherited.properties.length > 0) && <section className="metadata-inherited" aria-label="Inherited metadata">
      <h3>Inherited from folders</h3><MetadataValues values={inherited} />
      {inherited.properties.length > 0 && <small>You can override an inherited property using the same name.</small>}
    </section>}
    <fieldset className="metadata-tags-field" disabled={pending}>
      <legend>Custom tags</legend>
      {tags.length > 0 && <ul className="metadata-tag-list editable-tags" aria-label="Custom tags">
        {tags.map(tag => <li key={tag}><Badge tone={tagTone(tag)}>{tag}
          <button type="button" aria-label={'Remove tag ' + tag} onClick={() => {
            setTags(previous => previous.filter(item => item !== tag))
          }}>×</button>
        </Badge></li>)}
      </ul>}
      <div className="tag-entry">
        <input aria-label="Add custom tags" placeholder="Add a tag or separate with commas" value={tagDraft}
          onChange={event => setTagDraft(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Enter' && !event.nativeEvent.isComposing) { event.preventDefault(); addTags() }
          }} />
        <Button onClick={addTags} disabled={!tagDraft.trim()}>Add tags</Button>
      </div>
    </fieldset>
    <fieldset className="metadata-properties" disabled={pending}>
      <legend>Custom properties</legend>
      {properties.map((property, index) => <div className="metadata-property-row" key={property.id}>
        <input aria-label={'Property ' + (index + 1) + ' name'} placeholder="Name" value={property.name}
          onChange={event => changeProperty(property.id, 'name', event.target.value)} />
        <textarea aria-label={'Property ' + (index + 1) + ' value'} placeholder="Value" rows={1} value={property.value}
          onChange={event => changeProperty(property.id, 'value', event.target.value)} />
        <Button icon="close" className="icon-button" aria-label={'Remove property ' + (index + 1)} onClick={() => {
          setProperties(rows => rows.filter(row => row.id !== property.id))
        }} />
      </div>)}
      <Button onClick={() => {
        setProperties(rows => [...rows, { id: nextId, name: '', value: '' }])
        setNextId(nextId + 1)
      }}>Add property</Button>
    </fieldset>
    {!formId && <div className="row-actions"><Button variant="primary" type="submit" disabled={pending}>{pending ? 'Saving…' : bulk ? 'Apply metadata' : 'Save metadata'}</Button></div>}
  </form>
}
