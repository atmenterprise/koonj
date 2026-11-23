import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { permissionService, type Permission, type PermissionMatrix } from '../../api/services/permissionService'
import type { KoonjRole } from '../../auth/keycloak'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { Button } from '../../shared/ui/primitives'

const matrixKey = ['permissions', 'matrix'] as const
const roleNames: Record<KoonjRole, string> = { KOONJ_ADMIN: 'Administrator', KOONJ_OPERATOR: 'Operator', KOONJ_VIEWER: 'Viewer' }

export function RolePermissionSettings() {
  const client = useQueryClient()
  const [drafts, setDrafts] = useState<Partial<Record<KoonjRole, Set<Permission>>>>({})
  const [savedRole, setSavedRole] = useState<KoonjRole>()
  const matrix = useQuery({ queryKey: matrixKey, queryFn: ({ signal }) => permissionService.matrix(signal) })
  const save = useMutation({
    mutationFn: ({ role, grants }: { role: KoonjRole; grants: Set<Permission> }) => permissionService.save(role, [...grants]),
    onSuccess: saved => {
      client.setQueryData<PermissionMatrix>(matrixKey, previous => previous && {
        ...previous, roles: previous.roles.map(role => role.role === saved.role ? saved : role),
      })
      setDrafts(previous => ({ ...previous, [saved.role]: undefined }))
      setSavedRole(saved.role)
      void client.invalidateQueries({ queryKey: ['permissions', 'me'] })
      void client.invalidateQueries({ queryKey: matrixKey })
    },
  })

  if (!matrix.data) return matrix.error ? <ApiPageState loadingLabel="Loading role permissions" error={matrix.error}
    retry={() => void matrix.refetch()} retrying={matrix.isFetching} /> : <ApiPageState loadingLabel="Loading role permissions" />

  const { permissions, roles } = matrix.data

  function toggle(role: KoonjRole, current: Permission[], permission: Permission, granted: boolean) {
    setSavedRole(undefined)
    save.reset()
    setDrafts(previous => {
      const next = new Set(previous[role] ?? current)
      if (granted) next.add(permission)
      else next.delete(permission)
      const changed = next.size !== current.length || current.some(item => !next.has(item))
      return { ...previous, [role]: changed ? next : undefined }
    })
  }

  return <section className="panel admin-group" aria-labelledby="role-permissions-title">
    <div className="panel-heading"><div>
      <h2 id="role-permissions-title">Role permissions</h2>
      <p>Changes apply across projects. Users with multiple roles receive the permissions granted by any of those roles.</p>
    </div></div>
    <div className="permission-table-scroll" role="region" aria-label="Role permission matrix" tabIndex={0}>
      <table className="permission-table">
        <thead><tr><th scope="col">Permission</th>{roles.map(role =>
          <th key={role.role} scope="col">{roleNames[role.role]}{!role.editable && <small>Fixed access</small>}</th>)}</tr></thead>
        <tbody>{permissions.map(permission => <tr key={permission.id}>
          <th scope="row">{permission.label}<small>{permission.description}</small></th>
          {roles.map(role => {
            const checked = drafts[role.role]?.has(permission.id) ?? role.permissions.includes(permission.id)
            return <td key={role.role}>
              <input type="checkbox" checked={checked}
                aria-label={`${roleNames[role.role]} can ${permission.label.toLowerCase()}`}
                disabled={!role.editable || permission.id === 'PERMISSIONS_MANAGE' || save.isPending}
                onChange={event => toggle(role.role, role.permissions, permission.id, event.target.checked)} />
            </td>
          })}
        </tr>)}</tbody>
        <tfoot><tr><td />
          {roles.map(role => <td key={role.role}>
            {role.editable && <Button disabled={!drafts[role.role] || save.isPending}
              onClick={() => {
                const grants = drafts[role.role]
                if (grants) save.mutate({ role: role.role, grants })
              }}>Save {roleNames[role.role]}</Button>}
          </td>)}
        </tr></tfoot>
      </table>
    </div>
    {save.error && <p className="ops-error permission-feedback" role="alert">{save.error.message}</p>}
    {savedRole && <p className="permission-feedback" role="status">{roleNames[savedRole]} permissions saved.</p>}
  </section>
}
