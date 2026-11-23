import { useQuery } from '@tanstack/react-query'
import { createContext, useContext, type ReactNode } from 'react'
import { permissionService, type Permission } from '../api/services/permissionService'
import { ApiPageState } from '../shared/ui/ApiPageState'

const PermissionContext = createContext<ReadonlySet<Permission> | null>(null)

export function WorkspacePermissions({ children }: { children: ReactNode }) {
  const grants = useQuery({
    queryKey: ['permissions', 'me'],
    queryFn: ({ signal }) => permissionService.effective(signal),
    refetchInterval: 30_000,
    meta: { inlineError: true },
  })

  if (!grants.data) return <main className="auth-gate">{grants.error
    ? <><p role="alert">{grants.error.message}</p>
      <ApiPageState loadingLabel="Loading your permissions" error={grants.error}
        retry={() => void grants.refetch()} retrying={grants.isFetching} /></>
    : <ApiPageState loadingLabel="Loading your permissions" />}</main>

  return <PermissionContext.Provider value={new Set(grants.data)}>{children}</PermissionContext.Provider>
}

export function usePermissions() {
  const grants = useContext(PermissionContext)
  if (!grants) throw new Error('WorkspacePermissions is required')
  return { can: (permission: Permission) => grants.has(permission) }
}
