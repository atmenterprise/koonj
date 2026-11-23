import { apiClient } from '../client'
import type { ArchiveActivityDTO, CatalogFavorite, CatalogFavoriteTarget, CatalogLocationDTO, CatalogMetadataEntry, CatalogMetadataWriteRequest,
  CatalogParams, FileAttributesDTO, FileMovementDTO, PageResponse, PaginatedFileResponse, TransferJobDTO } from '../types'

export const catalogService = {
  async getFavorites(signal?: AbortSignal) {
    return (await apiClient.get<CatalogFavorite[]>('/catalog/favorites', { signal })).data
  },
  async setFavorite(request: CatalogFavoriteTarget & { favorite: boolean }) {
    await apiClient.put('/catalog/favorites', request)
  },
  async getMetadata(connectionId: string, signal?: AbortSignal) {
    return (await apiClient.get<CatalogMetadataEntry[]>(`/catalog/locations/${connectionId}/metadata`, { signal })).data
  },
  async saveMetadata(connectionId: string, request: CatalogMetadataWriteRequest, signal?: AbortSignal) {
    await apiClient.put(`/catalog/locations/${connectionId}/metadata`, request, { signal })
  },
  async getLocations(signal?: AbortSignal) {
    return (await apiClient.get<CatalogLocationDTO[]>('/catalog/locations', { signal })).data
  },
  async getActiveArchives(connectionId: string, signal?: AbortSignal) {
    return (await apiClient.get<ArchiveActivityDTO[]>(`/catalog/locations/${connectionId}/active-archives`, { signal })).data
  },
  async getFiles(params: CatalogParams = {}, signal?: AbortSignal) {
    return (await apiClient.get<PaginatedFileResponse>('/catalog/files', { params, signal })).data
  },
  async getFileAttributes(fileId: string, signal?: AbortSignal) {
    return (await apiClient.get<FileAttributesDTO>(`/catalog/files/${fileId}/attributes`, { signal })).data
  },
  async getLineage(connectionId: string, path: string, page: number, signal?: AbortSignal) {
    return (await apiClient.get<PageResponse<FileMovementDTO>>(`/catalog/locations/${connectionId}/lineage`,
      { params: { path, page }, signal })).data
  },
  async getLineageReport(connectionId: string, path: string) {
    return (await apiClient.get<Blob>(`/catalog/locations/${connectionId}/lineage/report`,
      { params: { path }, responseType: 'blob', timeout: 0, headers: { Accept: 'text/csv' } })).data
  },
  async triggerScan(connectionId: string, signal?: AbortSignal) {
    return (await apiClient.post<TransferJobDTO>(`/catalog/scan/${connectionId}`, undefined, { signal })).data
  },
  async scanSources() {
    return (await apiClient.post<{ jobs: TransferJobDTO[]; existingScanCount: number }>('/catalog/scan/sources')).data
  },
}
