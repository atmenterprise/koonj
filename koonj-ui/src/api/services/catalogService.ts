import { apiClient } from '../client'
import type { CatalogLocationDTO, CatalogMetadataEntry, CatalogMetadataWriteRequest, CatalogParams, PaginatedFileResponse, TransferJobDTO } from '../types'

export const catalogService = {
  async getMetadata(connectionId: string, signal?: AbortSignal) {
    return (await apiClient.get<CatalogMetadataEntry[]>(`/catalog/locations/${connectionId}/metadata`, { signal })).data
  },
  async saveMetadata(connectionId: string, request: CatalogMetadataWriteRequest, signal?: AbortSignal) {
    await apiClient.put(`/catalog/locations/${connectionId}/metadata`, request, { signal })
  },
  async getLocations(signal?: AbortSignal) {
    return (await apiClient.get<CatalogLocationDTO[]>('/catalog/locations', { signal })).data
  },
  async getFiles(params: CatalogParams = {}, signal?: AbortSignal) {
    return (await apiClient.get<PaginatedFileResponse>('/catalog/files', { params, signal })).data
  },
  async triggerScan(connectionId: string, signal?: AbortSignal) {
    return (await apiClient.post<TransferJobDTO>(`/catalog/scan/${connectionId}`, undefined, { signal })).data
  },
}
