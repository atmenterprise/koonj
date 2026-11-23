import type { KoonjRole } from '../../auth/keycloak'
import { apiClient } from '../client'

export type Permission =
  | 'CATALOG_VIEW' | 'CATALOG_OPERATE' | 'TRANSFER_RUN' | 'TRANSFER_PURGE'
  | 'ACTIVITY_VIEW' | 'ACTIVITY_CONTROL' | 'STORAGE_VIEW' | 'STORAGE_MANAGE'
  | 'POLICY_MANAGE' | 'OBSERVABILITY_VIEW' | 'INTEGRATIONS_MANAGE' | 'PROJECTS_MANAGE' | 'PERMISSIONS_MANAGE'

export interface PermissionDefinition { id: Permission; label: string; description: string }
export interface RoleMapping { role: KoonjRole; editable: boolean; permissions: Permission[] }
export interface PermissionMatrix { permissions: PermissionDefinition[]; roles: RoleMapping[] }

export const permissionService = {
  effective: async (signal?: AbortSignal) => (await apiClient.get<Permission[]>('/permissions/me', { signal })).data,
  matrix: async (signal?: AbortSignal) => (await apiClient.get<PermissionMatrix>('/admin/permissions', { signal })).data,
  save: async (role: KoonjRole, permissions: Permission[]) =>
    (await apiClient.put<RoleMapping>(`/admin/permissions/${role}`, { permissions })).data,
}
