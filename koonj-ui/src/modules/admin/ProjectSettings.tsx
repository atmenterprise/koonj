import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { projectService, type Project } from '../../api/services/projectService'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { Button } from '../../shared/ui/primitives'

const projectsKey = ['projects']

export function ProjectSettings({ projectId, refreshMemberships }: { projectId: string; refreshMemberships: () => void }) {
  const cache = useQueryClient()
  const [selected, setSelected] = useState(projectId)
  const [name, setName] = useState('')
  const [account, setAccount] = useState('')
  const projects = useQuery({ queryKey: projectsKey, queryFn: ({ signal }) => projectService.list(signal) })
  const users = useQuery({ queryKey: ['project-users'], queryFn: ({ signal }) => projectService.users(signal) })
  const members = useQuery({ queryKey: ['project-members', selected], queryFn: ({ signal }) => projectService.members(selected, signal) })
  const create = useMutation({
    mutationFn: projectService.create,
    onSuccess: project => {
      cache.setQueryData<Project[]>(projectsKey, current => [...(current ?? []), project])
      setName('')
      setSelected(project.id)
      setAccount('')
      void cache.invalidateQueries({ queryKey: projectsKey })
      refreshMemberships()
    },
  })
  const membership = useMutation({
    mutationFn: ({ id, subject, remove }: { id: string; subject: string; remove?: boolean }) =>
      remove ? projectService.remove(id, subject) : projectService.assign(id, subject),
    onSuccess: async (_, { id, remove }) => {
      if (!remove) setAccount('')
      await cache.invalidateQueries({ queryKey: ['project-members', id] })
      refreshMemberships()
    },
  })

  function addProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (name.trim()) create.mutate(name.trim())
  }

  const failure = [projects, users, members].find(query => query.isError)?.error
  const pending = projects.isPending || users.isPending || members.isPending
  const existing = new Set((members.data ?? []).map(member => member.subject))
  const available = (users.data ?? []).filter(user => !existing.has(user.subject))

  return <div className="admin-stack">
    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>Projects</h2><p>Each project has its own catalog, connections, activity, and policies.</p></div></div>
      <form className="ops-form project-form" onSubmit={addProject}>
        <label>Project name<input maxLength={100} value={name} required
          onChange={event => setName(event.target.value)} /></label>
        <Button type="submit" variant="primary" disabled={!name.trim() || create.isPending}>
          {create.isPending ? 'Creating…' : 'Create project'}
        </Button>
        {create.error && <p className="ops-error" role="alert">{create.error.message}</p>}
      </form>
    </section>
    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>Project members</h2>
        <p>Assign people after they first sign in to Koonj.</p>
      </div></div>
      {failure ? <ApiPageState loadingLabel="Loading project members" error={failure}
        retry={() => { void projects.refetch(); void users.refetch(); void members.refetch() }}
        retrying={projects.isFetching || users.isFetching || members.isFetching} />
        : <>
          <div className="ops-form project-form">
            <label>Project<select value={selected} disabled={membership.isPending}
              onChange={event => { setSelected(event.target.value); setAccount(''); membership.reset() }}>
              {projects.data?.map(project => <option value={project.id} key={project.id}>{project.name}</option>)}
            </select></label>
            <form className="project-form" onSubmit={event => {
              event.preventDefault()
              if (account) membership.mutate({ id: selected, subject: account })
            }}>
              <label>User<select value={account} disabled={pending || membership.isPending || !available.length}
                onChange={event => setAccount(event.target.value)}>
                <option value="">{available.length || pending ? 'Choose a user' : 'All signed-in users are members'}</option>
                {available.map(user => <option key={user.subject} value={user.subject}>
                  {user.displayName}{user.email ? ` · ${user.email}` : ''}
                </option>)}
              </select></label>
              <Button type="submit" disabled={!account || pending || membership.isPending}>Add member</Button>
            </form>
            {membership.error && <p className="ops-error" role="alert">{membership.error.message}</p>}
          </div>
          {pending ? <p className="empty compact" role="status">Loading project members…</p>
            : members.data?.length ? <ul className="admin-endpoints">
              {members.data.map(member => <li key={member.subject}>
                <div className="admin-endpoint-label"><strong>{member.displayName}</strong>
                  {member.email && <small>{member.email}</small>}</div>
                <Button disabled={membership.isPending} aria-label={`Remove ${member.displayName} from this project`}
                  onClick={() => membership.mutate({ id: selected, subject: member.subject, remove: true })}>Remove</Button>
              </li>)}
            </ul> : <p className="empty compact">No members in this project.</p>}
        </>}
    </section>
  </div>
}
