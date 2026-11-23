import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { notificationEvents, notificationService, type NotificationEvent, type NotificationPreferences } from '../api/services/notificationService'
import { ApiPageState } from '../shared/ui/ApiPageState'
import { Button } from '../shared/ui/primitives'

const events = Object.keys(notificationEvents) as NotificationEvent[]

function PreferencesForm({ settings, subject }: { settings: NotificationPreferences; subject: string }) {
  const [enabled, setEnabled] = useState(settings.emailNotificationsEnabled)
  const [muted, setMuted] = useState(settings.mutedEventTypes)
  const [snooze, setSnooze] = useState('keep')
  const queryClient = useQueryClient()
  const mutation = useMutation({
    mutationFn: notificationService.savePreferences,
    onSuccess: updated => {
      queryClient.setQueryData(['email-preferences', subject], updated)
      setSnooze('keep')
    },
    meta: { inlineError: true },
  })

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const silencedUntil = snooze === 'keep' ? settings.silencedUntil
      : snooze === 'clear' ? null
      : new Date(Date.now() + Number(snooze) * 3_600_000).toISOString()
    mutation.mutate({ emailNotificationsEnabled: enabled, mutedEventTypes: muted, silencedUntil })
  }

  const silenceActive = settings.silencedUntil && Date.parse(settings.silencedUntil) > Date.now()
  return <form onSubmit={save} className="notification-preferences">
    <label className="notification-check"><input type="checkbox" checked={enabled} disabled={mutation.isPending}
      onChange={event => { setEnabled(event.target.checked); mutation.reset() }} /> Receive emails</label>
    <fieldset disabled={mutation.isPending}>
      <legend>Mute emails by event</legend>
      {events.map(type => <label className="notification-check" key={type}>
        <input type="checkbox" checked={muted.includes(type)} onChange={event => {
          setMuted(current => event.target.checked ? [...current, type] : current.filter(item => item !== type))
          mutation.reset()
        }} /> {notificationEvents[type]}
      </label>)}
    </fieldset>
    <label>Snooze emails
      <select value={snooze} disabled={mutation.isPending} onChange={event => { setSnooze(event.target.value); mutation.reset() }}>
        <option value="keep">{silenceActive ? 'Keep current snooze' : 'No snooze'}</option>
        {silenceActive && <option value="clear">End snooze</option>}
        <option value="1">For 1 hour</option>
        <option value="8">For 8 hours</option>
        <option value="24">For 24 hours</option>
        <option value="168">For 7 days</option>
      </select>
    </label>
    {silenceActive && <small>Silenced until {new Date(settings.silencedUntil!).toLocaleString()}</small>}
    <Button type="submit" disabled={mutation.isPending}>Save email preferences</Button>
    {mutation.error && <p role="alert">{mutation.error.message}</p>}
    {mutation.isSuccess && <p className="admin-notice" role="status">Email preferences saved.</p>}
  </form>
}

export function EmailNotificationPreferences({ subject }: { subject: string }) {
  const preferences = useQuery({
    queryKey: ['email-preferences', subject],
    queryFn: ({ signal }) => notificationService.preferences(signal),
    meta: { inlineError: true },
  })
  if (!preferences.data) return <ApiPageState loadingLabel="Loading email preferences" error={preferences.error ?? undefined}
    retry={() => void preferences.refetch()} retrying={preferences.isFetching} />
  return <PreferencesForm settings={preferences.data} subject={subject} />
}

export function EmailNotificationPreferencesDisclosure({ subject }: { subject: string }) {
  const [open, setOpen] = useState(false)
  return <details className="email-preferences-disclosure" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>Email Notification Preferences</summary>
    {open && <EmailNotificationPreferences subject={subject} />}
  </details>
}
