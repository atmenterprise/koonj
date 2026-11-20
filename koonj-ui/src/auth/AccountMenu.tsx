import { useState } from 'react'
import { Badge, Button } from '../shared/ui/primitives'
import { useAuth } from './AuthProvider'

export function AccountMenu() {
  const auth = useAuth()
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)
  const user = auth.user
  if (!user) return null
  const role = auth.hasRole('KOONJ_ADMIN') ? 'Admin' : auth.hasRole('KOONJ_OPERATOR') ? 'Operator' : 'Viewer'

  async function logout() {
    setPending(true)
    setFailed(false)
    try {
      await auth.logout()
    } catch {
      setFailed(true)
      setPending(false)
    }
  }

  return <div className="auth-account">
    <span className="icon-tile blue auth-avatar" aria-hidden="true">{user.name.slice(0, 1).toUpperCase()}</span>
    <div className="auth-identity"><strong>{user.name}</strong>{user.email && <small>{user.email}</small>}</div>
    <Badge tone="blue">{role}</Badge>
    <Button icon="logout" disabled={pending} onClick={() => void logout()}>Logout</Button>
    {failed && <small role="alert">Logout failed. Try again.</small>}
  </div>
}
