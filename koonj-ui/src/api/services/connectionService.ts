import { apiClient } from '../client'
import type { ConnectionHealthDTO, CreateConnectionRequest, StorageConnectionDTO } from '../types'

export const connectionService = {
  async getConnections(signal?: AbortSignal) {
    return (await apiClient.get<StorageConnectionDTO[]>('/connections', { signal })).data
  },
  async createConnection(payload: CreateConnectionRequest, signal?: AbortSignal) {
    return (await apiClient.post<StorageConnectionDTO>('/connections', payload, { signal })).data
  },
  async updateConnection(id: string, payload: CreateConnectionRequest, signal?: AbortSignal) {
    return (await apiClient.put<StorageConnectionDTO>(`/connections/${id}`, payload, { signal })).data
  },
  async deleteConnection(id: string, signal?: AbortSignal) {
    await apiClient.delete(`/connections/${id}`, { signal })
  },
  async testConnection(id: string, signal?: AbortSignal) {
    return (await apiClient.post<ConnectionHealthDTO>(`/connections/${id}/test`, undefined, { signal })).data
  },
}
