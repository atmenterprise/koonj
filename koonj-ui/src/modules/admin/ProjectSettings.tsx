import { lazy, Suspense, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { projectService, type Project } from '../../api/services/projectService'
import { Dialog } from '../../shared/ui/feedback'
import { Badge, Button, Icon } from '../../shared/ui/primitives'

const ChannelEditor = lazy(() => import('./NotificationSettings').then(module => ({ default: module.ChannelEditor })))

export function ProjectSettings({ project, refreshMemberships, canConfigureChannels, projectCount }: { project: Project; projectCount: number; canConfigureChannels?: boolean; refreshMemberships: () => void }) {
  const cache = useQueryClient()
  const [name, setName] = useState('')
  const [editing, setEditing] = useState(false)
  const [action, setAction] = useState<'create' | 'rename' | 'delete'>()
  const [email, setEmail] = useState('')
  const id = project.id
  const admin = project.role === 'ADMIN'
  const members = useQuery({ queryKey: ['members', id], queryFn: ({ signal }) => projectService.members(id, signal) })
  const invites = useQuery({ queryKey: ['invitations', id], enabled: admin, refetchInterval: 15000,
    queryFn: ({ signal }) => projectService.invitations(id, signal) })
  const settings = useQuery({ queryKey: ['invitation-channel', id], enabled: admin,
    queryFn: ({ signal }) => projectService.emailSettings(id, signal) })
  const selected = settings.data?.channels.find(channel => channel.id === settings.data.selected)
  const selectChannel = useMutation({
    mutationFn: (channelId: string) => projectService.selectChannel(id, channelId),
    onSettled: () => cache.invalidateQueries({ queryKey: ['invitation-channel', id] }),
  })
  const change = useMutation({
    mutationFn: (work: () => Promise<unknown>) => work(),
    onSuccess: async () => {
      refreshMemberships()
      await Promise.all(['members', 'invitations'].map(key => cache.invalidateQueries({ queryKey: [key, id] })))
    },
  })
  const updateProject = useMutation({
    mutationFn: async (operation: 'create' | 'rename' | 'delete') => {
      if (operation === 'delete') await projectService.delete(id)
      else if (operation === 'rename') await projectService.rename(id, name.trim())
      else return projectService.create(name.trim())
    },
    onSuccess: () => { setAction(undefined); refreshMemberships() },
  })
  function openDialog(operation: 'create' | 'rename' | 'delete') {
    updateProject.reset()
    setName(operation === 'rename' ? project.name : '')
    setAction(operation)
  }
  function closeDialog() {
    if (!updateProject.isPending) setAction(undefined)
  }
  const dialogTitle = action === 'delete' ? 'Delete project' : action === 'rename' ? 'Rename project' : 'Create project'
  const error = settings.error ?? selectChannel.error ?? members.error ?? invites.error ?? change.error
  const canInvite = !!selected && !selectChannel.isPending && !change.isPending
  return <>
    <header className="page-heading"><div><h1>Projects</h1><p>{project.name}</p></div>
      <div className="row-actions">
        {admin && <>
          <Button onClick={() => openDialog('rename')}>Rename project</Button>
          <Button variant="danger" disabled={projectCount <= 1}
            title={projectCount <= 1 ? 'You must have at least one project' : undefined}
            onClick={() => openDialog('delete')}>Delete project</Button>
        </>}
        <Button icon="projects" variant="primary" onClick={() => openDialog('create')}>Create project</Button>
      </div>
    </header>
    {action && <Dialog title={dialogTitle} onClose={closeDialog}>
      <form onSubmit={event => { event.preventDefault(); updateProject.mutate(action) }}>
        <div className="dialog-body">
          {action === 'delete' ? <p>Delete <strong>{project.name}</strong>? All members will lose access and pending invitations will be revoked.</p>
            : <label>Project name<input autoFocus required maxLength={100} value={name} disabled={updateProject.isPending}
              onChange={event => setName(event.target.value)} /></label>}
          {updateProject.error && <p className="ops-error" role="alert">{updateProject.error.message}</p>}
        </div>
        <div className="dialog-footer">
          <Button onClick={closeDialog} disabled={updateProject.isPending}>Cancel</Button>
          <Button type="submit" variant={action === 'delete' ? 'danger' : 'primary'}
            disabled={updateProject.isPending || (action !== 'delete' && !name.trim())}>{dialogTitle}</Button>
        </div>
      </form>
    </Dialog>}
    <div className="admin-stack">
      {updateProject.data && <p role="status">Created {updateProject.data.name}. Select it in the project switcher.</p>}
      <div className="projects-layout">
      <section className="panel admin-group project-members"><div className="panel-heading"><h2>Members</h2>
        {members.data && <Badge>{members.data.length}</Badge>}</div>
        {members.isPending && <p className="empty compact">Loading members…</p>}
        <ul className="admin-endpoints">{members.data?.map(member => <li key={member.subject}>
          <span className="icon-tile blue"><Icon name="user" /></span>
          <div className="admin-endpoint-label"><strong>{member.displayName}</strong><small>{member.email}</small></div>
          <Badge tone={member.role === 'ADMIN' ? 'blue' : 'neutral'}>{member.role === 'ADMIN' ? 'Administrator' : 'Member'}</Badge>
          {admin && !member.personalOwner && (member.role !== 'ADMIN' || members.data.filter(m => m.role === 'ADMIN').length > 1) &&
            <Button disabled={change.isPending} onClick={() => change.mutate(() => projectService.remove(id, member.subject))}>Remove</Button>}
        </li>)}</ul>
      </section>
      {admin && <><section className="panel admin-group">
        <div className="panel-heading"><div><h2>Email invitations</h2>
          <p>{selected ? 'Invitations are sent through ' + selected.name + '.' : 'Select an enabled email channel to invite members.'}</p></div>
          {canConfigureChannels && <Button icon="settings" aria-expanded={editing} onClick={() => setEditing(!editing)}>Configure email channel</Button>}
        </div>
        {editing && <Suspense fallback={<p role="status">Loading email settings…</p>}>
          <ChannelEditor emailOnly close={() => setEditing(false)} onSaved={channel => selectChannel.mutate(channel.id)} />
        </Suspense>}
        <div className="ops-form project-form"><label>Invitation channel<select value={selected?.id ?? ''}
          disabled={settings.isPending || selectChannel.isPending || !settings.data?.channels.length}
          onChange={e => selectChannel.mutate(e.target.value)}>
          <option value="" disabled>{settings.isPending ? 'Loading channels…' : settings.data?.channels.length ? 'Select a channel' : 'No enabled email channels'}</option>
          {settings.data?.channels.map(channel => <option key={channel.id} value={channel.id}>{channel.name}</option>)}
        </select></label></div>
        {!canConfigureChannels && settings.data?.channels.length === 0 && <p>Ask a Koonj administrator to configure an email channel.</p>}
        <form className="ops-form project-form" onSubmit={event => {
          event.preventDefault()
          if (canInvite) change.mutate(() => projectService.invite(id, email.trim()), { onSuccess: () => setEmail('') })
        }}>
          <label>Recipient email<input required type="email" disabled={!canInvite} maxLength={320} value={email} onChange={e => setEmail(e.target.value)} /></label>
          <Button type="submit" variant="primary" icon="arrow" disabled={!canInvite}>Invite by email</Button>
        </form>
      </section>
      <section className="panel admin-group"><div className="panel-heading"><h2>Pending invitations</h2></div>
        {invites.data?.length === 0 && <p className="empty compact">No pending invitations.</p>}
        <ul className="admin-endpoints">{invites.data?.map(invite => <li key={invite.id}>
          <strong>{invite.email}</strong><Badge>{Date.parse(invite.expiresAt) <= Date.now() ? 'EXPIRED' : invite.delivery}</Badge>
          <Button icon="retry" disabled={!canInvite} onClick={() => change.mutate(() => projectService.resend(id, invite.id))}>Resend</Button>
          <Button disabled={change.isPending} onClick={() => change.mutate(() => projectService.revoke(id, invite.id))}>Revoke</Button>
        </li>)}</ul>
      </section></>}
      </div>
      {error && <p role="alert" className="ops-error">{error.message}</p>}
    </div>
  </>
}
