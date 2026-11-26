import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ProjectSettings } from './ProjectSettings'
import type { Project } from '../../api/services/projectService'

function render(role: Project['role'], selected: string | null = null) {
  const cache = new QueryClient()
  cache.setQueryData(['members', 'other'], [{ subject: 'other', displayName: 'Private outsider' }])
  cache.setQueryData(['members', 'shared'], [{ subject: 'alice', displayName: 'Alice', role: 'ADMIN', personalOwner: false }])
  cache.setQueryData(['invitation-channel', 'shared'], { selected, channels: [{ id: 'relay', name: 'Team SMTP' }] })
  return renderToStaticMarkup(<QueryClientProvider client={cache}><ProjectSettings
    project={{ id: 'shared', name: 'Shared project', defaultProjectId: 'personal', role }}
    refreshMemberships={() => {}} /></QueryClientProvider>)
}

describe('project settings', () => {
  it('shows only the selected roster and offers email invitations to administrators', () => {
    const html = render('ADMIN')
    expect(html).toContain('Alice')
    const input = (markup: string) => markup.match(/<input[^>]*type="email"[^>]*>/)?.[0]
    expect(input(html)).toContain('disabled')
    const configured = render('ADMIN', 'relay')
    expect(configured).toContain('Invitations are sent through Team SMTP.')
    expect(input(configured)).toBeDefined()
    expect(input(configured)).not.toContain('disabled')
    expect(html).not.toContain('Private outsider')
  })
  it('lets members create projects while keeping invitation management with admins', () => {
    const html = render('MEMBER')
    expect(html).toContain('Create project')
    expect(html).not.toContain('Invite by email')
    expect(html).not.toContain('Remove')
  })
})
