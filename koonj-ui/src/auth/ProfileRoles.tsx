import type { KoonjRole } from './keycloak'
import { Badge } from '../shared/ui/primitives'

const roleLabels: Record<KoonjRole, string> = {
  KOONJ_ADMIN: 'Admin',
  KOONJ_OPERATOR: 'Operator',
  KOONJ_VIEWER: 'Viewer',
}

export function ProfileRoles({ roles }: { roles: KoonjRole[] }) {
  return roles.length ? roles.map(role => <Badge key={role} tone="yellow">{roleLabels[role]}</Badge>)
    : <small>No roles assigned</small>
}
