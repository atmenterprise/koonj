import { apiClient } from '../client'

export interface TemperaturePolicy {
  hotDays: number
  coldDays: number
}

export const managementService = {
  async getTemperaturePolicy(signal?: AbortSignal) {
    return (await apiClient.get<TemperaturePolicy>('/management/temperature-policy', { signal })).data
  },
  async saveTemperaturePolicy(policy: TemperaturePolicy) {
    return (await apiClient.put<TemperaturePolicy>('/management/temperature-policy', policy)).data
  },
}
