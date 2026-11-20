import { annotatedEntries, metadataSearchText, type MetadataStore } from './catalogMetadata'
import { entryName, parentPath, type Entry } from './sampleData'

export type CatalogPage = { items: Entry[]; total: number; offset: number }
export const folderPageSize = 20
const demoEndpoint = 'nas'
const casePath = 'investigations'
const collectionPath = casePath + '/Incoming evidence collections awaiting classification and case officer review'
const manifestPath = casePath + '/Full evidence manifest for the consolidated cross-jurisdiction investigation'
const collectionCount = 60_000
const filesPerCollection = 200
const manifestCount = 10_000_100
const modified = '2026-11-18T10:00:00Z'
const folderLabel = 'Evidence submissions for the regional inquiry and chain of custody review'
const identifierWidth = 8
const states = ['Local Only', 'Archived', 'Purged Stubs', 'In Progress'] as const

function demoFolder(path: string, count: number): Entry {
  return { id: demoEndpoint + ':' + path, endpoint: demoEndpoint, path, count, folder: true, bytes: 0,
    state: 'Local Only', modified, generated: true }
}

export const scaleDemoFolders = [
  demoFolder(casePath, collectionCount * filesPerCollection + manifestCount),
  demoFolder(collectionPath, collectionCount),
  demoFolder(manifestPath, manifestCount),
]

function folderName(index: number) {
  return `Case ${String(index).padStart(identifierWidth, '0')} — ${folderLabel}`
}

function evidenceFile(path: string, index: number): Entry {
  const name = path + '/evidence-' + String(index).padStart(identifierWidth, '0') + '.dat'
  const state = states[(index - 1) % states.length]
  return { id: demoEndpoint + ':' + name, endpoint: demoEndpoint, path: name, folder: false,
    bytes: 524288 + (index % 64) * 65536, modified, state, generated: true,
    ...(state === 'Archived' && { storageClass: 'STANDARD' as const }),
    ...(state === 'Purged Stubs' && { storageClass: 'GLACIER_IR' as const }),
  }
}

function collectionIndex(path: string) {
  if (parentPath(path) !== collectionPath) return 0
  const match = /^Case (\d{8})/.exec(path.slice(collectionPath.length + 1))
  const index = Number(match?.[1])
  return index >= 1 && index <= collectionCount && path === collectionPath + '/' + folderName(index) ? index : 0
}

function virtualChildren(endpoint: string, path: string) {
  if (endpoint !== demoEndpoint) return undefined
  if (path === collectionPath) return { count: collectionCount, folder: true }
  if (path === manifestPath) return { count: manifestCount, folder: false }
  if (collectionIndex(path)) return { count: filesPerCollection, folder: false }
  return undefined
}

type Sequence = { count: number; at(index: number): Entry; indexOf(entry: Entry): number }
const empty: Sequence = { count: 0, at: () => { throw new RangeError('Empty catalog page') }, indexOf: () => -1 }

function evidenceNumber(path: string) {
  return Number(/^evidence-(\d{8})\.dat$/.exec(path.slice(path.lastIndexOf('/') + 1))?.[1])
}

// Numbered sample names are addressable by prefix or exact ID without scanning the sequence.
function nameBounds(term: string, folder: boolean, limit: number): [number, number] {
  if (!term) return [1, limit]
  const name = folder ? term.replace(' — ' + folderLabel.toLowerCase(), '') : term
  const id = (folder ? /^(?:case\s*)?(\d{1,8})$/ : /^(?:evidence-?)?(\d{1,8})(\.dat)?$/).exec(name)
  if (id) {
    const start = Number(id[1])
    const factor = id[2] || !id[1].startsWith('0') ? 1 : 10 ** (identifierWidth - id[1].length)
    return [Math.max(1, start * factor), Math.min(limit, (start + 1) * factor - 1)]
  }
  const label = folder ? 'case — ' + folderLabel.toLowerCase() : 'evidence-.dat'
  return term.split(/\s+/).every(word => label.includes(word)) ? [1, limit] : [1, 0]
}

function generatedSequence(endpoint: string, path: string, query: string, filter: string, foldersOnly: boolean): Sequence {
  const descriptor = virtualChildren(endpoint, path)
  if (!descriptor || (foldersOnly && !descriptor.folder) || (descriptor.folder && filter !== 'All')) return empty

  const [first, last] = nameBounds(query, descriptor.folder, descriptor.count)
  const state = states.findIndex(item => item === filter)
  const step = !descriptor.folder && state >= 0 ? states.length : 1
  const start = step === 1 ? first : first + (state - (first - 1) % step + step) % step
  const count = start <= last ? Math.floor((last - start) / step) + 1 : 0
  return {
    count,
    at(index) {
      const number = start + index * step
      return descriptor.folder ? demoFolder(path + '/' + folderName(number), filesPerCollection) : evidenceFile(path, number)
    },
    indexOf(entry) {
      if (entry.endpoint !== endpoint || !entry.generated || entry.folder !== descriptor.folder || parentPath(entry.path) !== path) return -1
      const number = descriptor.folder ? collectionIndex(entry.path) : evidenceNumber(entry.path)
      const index = (number - start) / step
      return Number.isInteger(index) && index >= 0 && index < count ? index : -1
    },
  }
}

function withoutEntries(sequence: Sequence, excluded: number[]): Sequence {
  if (!excluded.length) return sequence
  const sorted = [...new Set(excluded)].sort((left, right) => left - right)
  const removed = new Set(sorted)
  return {
    count: sequence.count - sorted.length,
    at(index) {
      for (const removedIndex of sorted) {
        if (removedIndex > index) break
        index++
      }
      return sequence.at(index)
    },
    indexOf(entry) {
      const index = sequence.indexOf(entry)
      if (index < 0 || removed.has(index)) return -1
      return index - sorted.filter(removedIndex => removedIndex < index).length
    },
  }
}

const keyFor = (endpoint: string, path: string) => endpoint + ':' + path

export function createCatalogSource(entries: Entry[], metadata: MetadataStore = {}) {
  const children = new Map<string, Entry[]>()
  const folders = new Map<string, Entry[]>()
  const ids = new Map<string, Entry>()
  for (const entry of entries) {
    const key = keyFor(entry.endpoint, parentPath(entry.path))
    const siblings = children.get(key) ?? []
    siblings.push(entry)
    children.set(key, siblings)
    if (entry.folder) {
      const branch = folders.get(key) ?? []
      branch.push(entry)
      folders.set(key, branch)
    }
    ids.set(entry.id, entry)
  }
  const compare = (left: Entry, right: Entry) => Number(right.folder) - Number(left.folder) || left.path.localeCompare(right.path)
  for (const branch of children.values()) branch.sort(compare)
  for (const branch of folders.values()) branch.sort(compare)
  const scaleEnabled = ids.has(keyFor(demoEndpoint, casePath))

  function entry(id: string) {
    const saved = ids.get(id)
    if (saved || !scaleEnabled || !id.startsWith(demoEndpoint + ':')) return saved
    const path = id.slice(demoEndpoint.length + 1)
    if (collectionIndex(path)) return demoFolder(path, filesPerCollection)
    const parent = parentPath(path)
    const descriptor = virtualChildren(demoEndpoint, parent)
    const number = evidenceNumber(path)
    return descriptor && !descriptor.folder && number >= 1 && number <= descriptor.count ? evidenceFile(parent, number) : undefined
  }

  const generatedAnnotations = annotatedEntries(metadata, entry).filter(item => !ids.has(item.id))
  const searchableEntries = [...entries, ...generatedAnnotations]

  function page(rows: Entry[], sequence: Sequence, requestedOffset: number, limit: number): CatalogPage {
    const total = rows.length + sequence.count
    const offset = Math.min(Math.max(0, requestedOffset), Math.max(0, Math.floor((total - 1) / limit) * limit))
    const items = rows.slice(offset, offset + limit)
    const generatedStart = Math.max(0, offset - rows.length)
    const generatedCount = Math.min(limit - items.length, sequence.count - generatedStart)
    for (let index = 0; index < generatedCount; index++) items.push(sequence.at(generatedStart + index))
    return { items, total, offset }
  }

  return {
    list(endpoint: string, path: string, input: string, filter: string, offset: number, limit: number) {
      const query = input.toLowerCase()
      const words = query.split(/\s+/)
      function matches(item: Entry) {
        const path = item.path.toLowerCase()
        if (path.includes(query)) return true
        const searchable = path + ' ' + metadataSearchText(metadata, item)
        return words.every(word => searchable.includes(word))
      }
      const parent = query && scaleEnabled ? entry(keyFor(endpoint, path)) : undefined
      const inheritedText = parent ? metadataSearchText(metadata, parent) : ''
      const inheritedSearch = inheritedText && words.every(word => inheritedText.includes(word))
      let sequence = scaleEnabled ? generatedSequence(endpoint, path, inheritedSearch ? '' : query, filter, false) : empty
      if (inheritedSearch) {
        const excluded = generatedAnnotations.filter(item => !matches(item))
          .map(item => sequence.indexOf(item)).filter(index => index >= 0)
        sequence = withoutEntries(sequence, excluded)
      }
      const scoped = query ? searchableEntries.filter(item => item.endpoint === endpoint && (!path || item.path.startsWith(path + '/'))
        && sequence.indexOf(item) < 0 && matches(item)).sort(compare) : children.get(keyFor(endpoint, path)) ?? []
      const rows = filter === 'All' ? scoped : scoped.filter(entry => !entry.folder && entry.state === filter)
      return page(rows, sequence, offset, limit)
    },
    folders(endpoint: string, path: string, query: string, offset: number) {
      const branch = folders.get(keyFor(endpoint, path)) ?? []
      const rows = query ? branch.filter(entry => entryName(entry).toLowerCase().includes(query)) : branch
      return page(rows, scaleEnabled ? generatedSequence(endpoint, path, query, 'All', true) : empty, offset, folderPageSize)
    },
    hasFolders(endpoint: string, path: string) {
      return Boolean(folders.get(keyFor(endpoint, path))?.length || (scaleEnabled && virtualChildren(endpoint, path)?.folder))
    },
    folderPage(endpoint: string, path: string, child: string) {
      const branch = folders.get(keyFor(endpoint, path)) ?? []
      const fixtureIndex = branch.findIndex(entry => entry.path === child)
      const generated = scaleEnabled && endpoint === demoEndpoint && path === collectionPath ? collectionIndex(child) : 0
      const index = fixtureIndex >= 0 ? fixtureIndex : generated > 0 ? branch.length + generated - 1 : 0
      return Math.floor(index / folderPageSize) * folderPageSize
    },
    entry,
    searchableEntries,
  }
}

export type CatalogSource = ReturnType<typeof createCatalogSource>
