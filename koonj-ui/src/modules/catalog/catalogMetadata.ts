import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useActivityNotifications } from '../../shared/notifications/ActivityNotifications'
import { catalogService } from '../../api/services/catalogService'
import type { CatalogMetadataEntry, CatalogMetadataTarget, CatalogMetadataValues, CatalogMetadataWriteRequest } from '../../api/types'

export const emptyMetadata: CatalogMetadataValues = { tags: [], properties: [] }

const metadataKey = (connectionId: string | undefined) => ['catalog', 'metadata', connectionId] as const
const targetKey = ({ scope, path }: CatalogMetadataTarget) => scope + ':' + path.replace(/\/+$/, '')

export function catalogMetadataOptions(connectionId?: string) {
  return queryOptions({
    queryKey: metadataKey(connectionId),
    enabled: !!connectionId,
    queryFn: ({ signal }) => catalogService.getMetadata(connectionId!, signal),
  })
}

export function useCatalogMetadata(connectionId?: string) {
  return useQuery(catalogMetadataOptions(connectionId))
}

export function useSaveCatalogMetadata(connectionId: string) {
  const client = useQueryClient()
  const { run } = useActivityNotifications()
  return useMutation({
    mutationFn: (request: CatalogMetadataWriteRequest) => run({
      started: 'Saving metadata',
      failed: 'Could not save metadata',
      complete: () => ({ tone: 'success', title: 'Metadata saved' }),
    }, () => catalogService.saveMetadata(connectionId, request)),
    onSuccess: () => client.invalidateQueries({ queryKey: metadataKey(connectionId) }),
  })
}

export function metadataIndex(entries: CatalogMetadataEntry[]) {
  return new Map(entries.map(entry => [targetKey(entry), entry.values]))
}

export function directMetadataFor(index: ReturnType<typeof metadataIndex>, target: CatalogMetadataTarget) {
  return index.get(targetKey(target)) ?? emptyMetadata
}

export function mergeMetadata(previous: CatalogMetadataValues, incoming: CatalogMetadataValues): CatalogMetadataValues {
  const tags = new Map([...previous.tags, ...incoming.tags].map(tag => [tag.toLowerCase(), tag]))
  const properties = new Map([...previous.properties, ...incoming.properties].map(property => [property.name.toLowerCase(), property]))
  return { tags: [...tags.values()], properties: [...properties.values()] }
}

export function metadataFor(index: ReturnType<typeof metadataIndex>, target: CatalogMetadataTarget) {
  const parts = target.path.replace(/\/+$/, '').split('/')
  parts.pop()
  let inherited = emptyMetadata
  let path = ''
  for (const part of parts) {
    path += (path ? '/' : '') + part
    const values = index.get(targetKey({ scope: 'FOLDER', path }))
    if (values) inherited = mergeMetadata(inherited, values)
  }
  const direct = directMetadataFor(index, target)
  return { direct, inherited, effective: mergeMetadata(inherited, direct) }
}
