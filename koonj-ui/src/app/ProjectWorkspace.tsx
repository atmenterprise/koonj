import { useQuery } from '@tanstack/react-query'
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
  const session = useQuery({
    queryKey: ['project-session', subject],
    queryFn: ({ signal }) => projectService.session(signal),
    enabled: !!subject,
    meta: { inlineError: true },
    refetchInterval: 60_000,
  })
  const key = 'koonj-project-' + subject
  const stored = sessionStorage.getItem(key)
  const view = session.data?.find(project => project.id === stored) ?? session.data?.[0]

  useLayoutEffect(() => {
    if (view) sessionStorage.setItem(key, view.id)
  }, [key, view])

  function switchProject(id: string) {
    if (id === view?.id || !session.data?.some(project => project.id === id)) return
    sessionStorage.setItem(key, id)
    window.location.replace('/Home')
  }

  return <AuthGate>
    {view && session.data ? <ActiveProject key={view.id} project={view} projects={session.data} onSwitch={switchProject}
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
