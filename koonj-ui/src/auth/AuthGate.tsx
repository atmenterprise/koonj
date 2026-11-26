import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useActivityNotifications } from '../shared/notifications/ActivityNotifications'
import { NotificationPopup } from '../shared/notifications/NotificationPopup'
import { ApiPageState } from '../shared/ui/ApiPageState'
import { Button } from '../shared/ui/primitives'
import { useAuth } from './AuthProvider'

export function AuthGate({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const { notify } = useActivityNotifications()
  const [redirecting, setRedirecting] = useState(false)
  const unavailable = auth.status === 'error'
  const issue = unavailable ? 'Sign-in service unavailable' : null
  const lastIssue = useRef<string | null>(null)
  useEffect(() => {
    if (issue && issue !== lastIssue.current) notify({
      tone: 'error', title: issue, detail: 'Koonj could not connect to the sign-in service.',
    })
    lastIssue.current = issue
  }, [issue, notify])

  if (auth.status === 'authenticated') return children

  if (auth.status === 'loading') {
    return <main className="auth-gate"><ApiPageState loadingLabel="Checking your session" /></main>
  }
  async function redirect(action: () => Promise<void>) {
    setRedirecting(true)
    try {
      await action()
    } catch {
      notify({ tone: 'error', title: 'Could not reach the sign-in service', detail: 'Try again.' })
    } finally {
      setRedirecting(false)
    }
  }

  return <><main className="auth-gate auth-gate--landing">
    <section className="panel auth-card" aria-labelledby="auth-title">
      <span className="brand-mark" aria-hidden="true" />
      <h1 id="auth-title">Welcome to Koonj</h1>
      {!issue && <p>Sign in to browse storage, archive files, and track transfers.</p>}
      <div className="row-actions auth-actions">
        {unavailable ? <Button variant="primary" icon="retry" onClick={() => window.location.reload()}>Try again</Button>
            : <>
              <Button variant="primary" icon="user" disabled={redirecting} onClick={() => void redirect(auth.login)}>Sign In</Button>
              <Button disabled={redirecting} onClick={() => void redirect(auth.register)}>Create Account</Button>
            </>}
      </div>
      {auth.status === 'anonymous' && <>
        <div className="auth-alternative">-or-</div>
        <div className="row-actions auth-actions auth-actions--social">
          <Button disabled={redirecting} onClick={() => void redirect(() => auth.loginWithProvider('google'))}>Continue with Google</Button>
          <Button disabled={redirecting} onClick={() => void redirect(() => auth.loginWithProvider('github'))}>Continue with GitHub</Button>
        </div>
      </>}
    </section>
    <footer className="auth-copyright">© {new Date().getFullYear()} Apps That Matter</footer>
  </main><NotificationPopup /></>
}
