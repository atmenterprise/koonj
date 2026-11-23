import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useActivityNotifications } from '../shared/notifications/ActivityNotifications'
import { NotificationPopup } from '../shared/notifications/NotificationPopup'
import { ApiPageState } from '../shared/ui/ApiPageState'
import { Button, Icon } from '../shared/ui/primitives'
import { useAuth } from './AuthProvider'
import { EmailNotificationPreferencesDisclosure } from './EmailNotificationPreferences'

export function AuthGate({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const { notify } = useActivityNotifications()
  const [redirecting, setRedirecting] = useState(false)
  const unavailable = auth.status === 'error'
  const awaitingRole = auth.status === 'authenticated' && auth.user.roles.length === 0
  const issue = unavailable ? 'Sign-in service unavailable' : awaitingRole ? 'Workspace access pending' : null
  const lastIssue = useRef<string | null>(null)
  useEffect(() => {
    if (issue && issue !== lastIssue.current) notify({
      tone: 'error', title: issue, detail: awaitingRole
        ? 'Ask your Koonj administrator to assign a workspace role, then sign in again.'
        : 'Koonj could not connect to the sign-in service.',
    })
    lastIssue.current = issue
  }, [awaitingRole, issue, notify])

  if (auth.status === 'authenticated' && !awaitingRole) return children

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
      <span className="brand-mark"><Icon name="bird" size={28} /></span>
      <h1 id="auth-title">Welcome to Koonj</h1>
      {!issue && <p>Sign in to browse storage, archive files, and track transfers.</p>}
      {awaitingRole && <EmailNotificationPreferencesDisclosure subject={auth.user.id} />}
      <div className="row-actions auth-actions">
        {unavailable ? <Button variant="primary" icon="retry" onClick={() => window.location.reload()}>Try again</Button>
          : awaitingRole ? <Button icon="logout" disabled={redirecting} onClick={() => void redirect(auth.logout)}>Logout</Button>
            : <>
              <Button variant="primary" icon="user" disabled={redirecting} onClick={() => void redirect(auth.login)}>Sign In</Button>
              <Button disabled={redirecting} onClick={() => void redirect(auth.register)}>Create Account</Button>
            </>}
      </div>
    </section>
    <footer className="auth-copyright">© {new Date().getFullYear()} Apps That Matter</footer>
  </main><NotificationPopup /></>
}
