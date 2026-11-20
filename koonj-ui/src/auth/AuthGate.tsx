import { useState, type ReactNode } from 'react'
import { ApiPageState } from '../shared/ui/ApiPageState'
import { Button, Icon } from '../shared/ui/primitives'
import { useAuth } from './AuthProvider'

export function AuthGate({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const [redirecting, setRedirecting] = useState(false)
  const [redirectError, setRedirectError] = useState(false)
  const allowed = auth.status === 'authenticated' && auth.user.roles.length > 0
  if (allowed) return children

  if (auth.status === 'loading') {
    return <main className="auth-gate"><ApiPageState loadingLabel="Checking your session" /></main>
  }
  const unavailable = auth.status === 'error'
  const awaitingRole = auth.status === 'authenticated'

  async function redirect(action: () => Promise<void>) {
    setRedirectError(false)
    setRedirecting(true)
    try {
      await action()
    } catch {
      setRedirectError(true)
      setRedirecting(false)
    }
  }

  return <main className="auth-gate">
    <section className="panel auth-card" aria-labelledby="auth-title">
      <span className="brand-mark"><Icon name="bird" size={28} /></span>
      <h1 id="auth-title">{unavailable ? 'Sign-in service unavailable' : awaitingRole ? 'Workspace access pending' : 'Welcome to Koonj'}</h1>
      <p>{unavailable ? 'Koonj could not connect to the sign-in service.'
        : awaitingRole ? 'Ask your Koonj administrator to assign a workspace role, then sign in again.'
          : 'Sign in to browse storage, archive files, and track transfers.'}</p>
      {redirectError && <p role="alert" className="error-text">Could not reach the sign-in service. Try again.</p>}
      <div className="row-actions auth-actions">
        {unavailable ? <Button variant="primary" icon="retry" onClick={() => window.location.reload()}>Try again</Button>
          : awaitingRole ? <Button icon="logout" disabled={redirecting} onClick={() => void redirect(auth.logout)}>Logout</Button>
            : <>
              <Button variant="primary" icon="user" disabled={redirecting} onClick={() => void redirect(auth.login)}>Sign In</Button>
              <Button disabled={redirecting} onClick={() => void redirect(auth.register)}>Create Account</Button>
            </>}
      </div>
    </section>
  </main>
}
