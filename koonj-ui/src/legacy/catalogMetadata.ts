import { useEffect, useState } from 'react'
import { parentPath, type Entry } from './sampleData'

export type CustomMetadata = { tags: string[]; properties: { name: string; value: string }[] }
export const emptyMetadata: CustomMetadata = { tags: [], properties: [] }
export type MetadataStore = Record<string, CustomMetadata>
const storageKey = 'koonj-catalog-metadata-v1'

function readMetadata(): MetadataStore | null {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) ?? '{}')
    return saved && typeof saved === 'object' && !Array.isArray(saved) ? saved : null
  } catch {
    return null
  }
}

export function mergeMetadata(previous: CustomMetadata, incoming: CustomMetadata): CustomMetadata {
  const tags = new Map([...previous.tags, ...incoming.tags].map(tag => [tag.toLowerCase(), tag]))
  const properties = new Map([...previous.properties, ...incoming.properties].map(property => [property.name.toLowerCase(), property]))
  return { tags: [...tags.values()], properties: [...properties.values()] }
}

function metadataKey(endpoint: string, path: string, scope: 'folder' | 'file') {
  return JSON.stringify([scope, endpoint, path])
}

export function inheritedMetadata(store: MetadataStore, entry: Entry) {
  const ancestors = []
  for (let path = parentPath(entry.path); path; path = parentPath(path)) {
    const rule = store[metadataKey(entry.endpoint, path, 'folder')]
    if (rule) ancestors.push(rule)
  }
  return ancestors.reverse().reduce(mergeMetadata, emptyMetadata)
}

export function metadataForEntry(store: MetadataStore, entry: Entry) {
  return store[metadataKey(entry.endpoint, entry.path, entry.folder ? 'folder' : 'file')] ?? emptyMetadata
}

export function metadataSearchText(store: MetadataStore, entry: Entry) {
  const { tags, properties } = mergeMetadata(inheritedMetadata(store, entry), metadataForEntry(store, entry))
  return [...tags, ...properties.map(({ name, value }) => name + ' ' + value)].join(' ').toLowerCase()
}

export function annotatedEntries(store: MetadataStore, find: (id: string) => Entry | undefined) {
  return Object.keys(store).flatMap(key => {
    let target: unknown
    try { target = JSON.parse(key) } catch { return [] }
    if (!Array.isArray(target)) return []
    const [scope, endpoint, path] = target
    if ((scope !== 'folder' && scope !== 'file') || typeof endpoint !== 'string' || typeof path !== 'string') return []
    const entry = find(endpoint + ':' + path)
    return entry && entry.folder === (scope === 'folder') ? [entry] : []
  })
}

export function writeMetadata(store: MetadataStore, targets: Entry[], input: CustomMetadata, mode: 'replace' | 'merge') {
  const next = { ...store }
  for (const target of targets) {
    const key = metadataKey(target.endpoint, target.path, target.folder ? 'folder' : 'file')
    const value = mode === 'merge' ? mergeMetadata(next[key] ?? emptyMetadata, input) : input
    if (value.tags.length || value.properties.length) next[key] = value
    else delete next[key]
  }
  return next
}

export function useCatalogMetadata() {
  const [store, setStore] = useState(() => readMetadata() ?? {})

  useEffect(() => {
    function onStorage(event: StorageEvent) {
      if (event.key === storageKey || event.key === null) setStore(readMetadata() ?? {})
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])

  function save(targets: Entry[], input: CustomMetadata, mode: 'replace' | 'merge') {
    const next = writeMetadata(readMetadata() ?? store, targets, input, mode)
    try {
      localStorage.setItem(storageKey, JSON.stringify(next))
      setStore(next)
      return true
    } catch {
      return false
    }
  }

  return { store, save }
}
export type CatalogMetadata = ReturnType<typeof useCatalogMetadata>
