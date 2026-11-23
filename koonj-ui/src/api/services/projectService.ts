import { apiClient } from '../client'

export interface Project {
  id: string
  name: string
}

export interface ProjectAccount {
  subject: string
  displayName: string
  email: string | null
}

export const projectService = {
  session: async (signal?: AbortSignal) => (await apiClient.post<Project[]>('/projects/session', undefined, { signal })).data,
  list: async (signal?: AbortSignal) => (await apiClient.get<Project[]>('/projects', { signal })).data,
  create: async (name: string) => (await apiClient.post<Project>('/projects', { name })).data,
  users: async (signal?: AbortSignal) => (await apiClient.get<ProjectAccount[]>('/projects/users', { signal })).data,
  members: async (id: string, signal?: AbortSignal) =>
    (await apiClient.get<ProjectAccount[]>(`/projects/${id}/members`, { signal })).data,
  assign: async (id: string, subject: string) => { await apiClient.put(`/projects/${id}/members`, { subject }) },
  remove: async (id: string, subject: string) => { await apiClient.delete(`/projects/${id}/members`, { params: { subject } }) },
}
