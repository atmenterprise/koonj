import { useMutation, useQuery } from '@tanstack/react-query'
import { useLayoutEffect, useState } from 'react'
import { apiClient, projectHeader } from '../api/client'
import { projectService, type Project } from '../api/services/projectService'
import { AuthGate } from '../auth/AuthGate'
import { useAuth } from '../auth/AuthProvider'
import { WorkspacePermissions } from '../auth/WorkspacePermissions'
import { ActivityNotifications } from '../shared/notifications/ActivityNotifications'
import { ApiPageState } from '../shared/ui/ApiPageState'
import { AppProviders } from './AppProviders'
import Console from './Console'

function ActiveProject({ project, projects, onSwitch, refreshMemberships }: {
  project: Project; projects: Project[]; onSwitch: (id: string) => void; refreshMemberships: () => void
}) {
  const [configured, setConfigured] = useState(false)
  useLayoutEffect(() => {
    apiClient.defaults.headers.common[projectHeader] = project.id
    setConfigured(true)
    return () => { delete apiClient.defaults.headers.common[projectHeader] }
  }, [project.id])
  if (!configured) return null
  return <ActivityNotifications><AppProviders>
    <WorkspacePermissions>
      <Console project={project} projects={projects} onProjectSwitch={onSwitch} refreshMemberships={refreshMemberships} />
    </WorkspacePermissions>
  </AppProviders></ActivityNotifications>
}

function SignedInProject({ subject }: { subject?: string }) {
  const { user, logout } = useAuth()
  const session = useQuery({
    queryKey: ['project-session', subject],
    queryFn: ({ signal }) => projectService.session(signal),
    enabled: !!subject,
    meta: { inlineError: true },
    refetchInterval: 60_000,
  })
  const key = 'koonj-project-' + subject
  const stored = sessionStorage.getItem(key)
  const view = session.data?.find(project => project.id === stored)
    ?? session.data?.find(project => project.id === project.defaultProjectId)
  const invitation = sessionStorage.getItem('koonj-invitation')
  const accept = useMutation({
    mutationFn: projectService.accept,
    onSuccess: id => {
      sessionStorage.removeItem('koonj-invitation')
      sessionStorage.setItem(key, id)
      window.location.replace('/Home')
    },
  })

  useLayoutEffect(() => {
    if (view) sessionStorage.setItem(key, view.id)
  }, [key, view])

  function switchProject(id: string) {
    if (id === view?.id || !session.data?.some(project => project.id === id)) return
    sessionStorage.setItem(key, id)
    window.location.replace('/Home')
  }

  return <AuthGate>
    {invitation ? <main className="auth-gate"><section className="panel auth-card">
      <h1>Project invitation</h1><p>Accept using the account that received the invitation email.</p>
      <p>Signed in as {user?.email ?? user?.name}.</p>
      <button disabled={accept.isPending} onClick={() => void logout()}>Switch account</button>
      <button disabled={accept.isPending} onClick={() => accept.mutate(invitation)}>Accept invitation</button>
      <button disabled={accept.isPending} onClick={() => {
        sessionStorage.removeItem('koonj-invitation')
        window.location.replace('/Home')
      }}>Dismiss</button>
      {accept.error && <p role="alert">{accept.error.message}</p>}
    </section></main> : view && session.data ? <ActiveProject key={view.id} project={view} projects={session.data} onSwitch={switchProject}
      refreshMemberships={() => void session.refetch()} />
      : <main className="auth-gate">{session.error ? <>
        <p role="alert">{session.error.message}</p>
        <ApiPageState loadingLabel="Loading your projects" error={session.error}
          retry={() => void session.refetch()} retrying={session.isFetching} />
      </> : <ApiPageState loadingLabel="Loading your projects" />}</main>}
  </AuthGate>
}

export function ProjectWorkspace() {
  const { user } = useAuth()
  return <SignedInProject key={user?.id} subject={user?.id} />
}
