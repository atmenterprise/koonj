import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  channelTypes, notificationEvents, notificationService,
  type ChannelSettings, type ChannelType, type NotificationChannel, type NotificationEvent,
  type NotificationRule, type RuleSettings, type Thresholds,
} from '../../api/services/notificationService'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { Badge, Button, time } from '../../shared/ui/primitives'

const keys = ['notification-admin'] as const
const eventTypes = Object.keys(notificationEvents) as NotificationEvent[]
const types = Object.keys(channelTypes) as ChannelType[]

function formText(form: FormData, name: string) {
  const value = form.get(name)
  return typeof value === 'string' && value.trim() ? value : undefined
}

function formNumber(form: FormData, name: string) {
  const value = formText(form, name)
  return value === undefined ? undefined : Number(value)
}

function readHeaders(value: string | undefined): Record<string, string> | undefined {
  if (!value) return undefined
  let headers: unknown
  try { headers = JSON.parse(value) } catch { throw new Error('Enter webhook headers as a JSON object.') }
  if (!headers || typeof headers !== 'object' || Array.isArray(headers) || Object.values(headers).some(item => typeof item !== 'string')) {
    throw new Error('Each webhook header must have a text value.')
  }
  return headers as Record<string, string>
}

export function ChannelEditor({ channel, emailOnly, onSaved, close }: {
  channel?: NotificationChannel; emailOnly?: boolean; onSaved?: (channel: NotificationChannel) => void; close: () => void
}) {
  const [type, setType] = useState<ChannelType>(channel?.type ?? (emailOnly ? 'EMAIL' : 'WEBHOOK'))
  const [error, setError] = useState('')
  const queryClient = useQueryClient()
  const mutation = useMutation({
    mutationFn: (settings: ChannelSettings) => notificationService.saveChannel(settings, channel?.id),
    onSuccess: response => { void queryClient.invalidateQueries({ queryKey: keys }); onSaved?.(response.data); close() },
    meta: { inlineError: true },
  })

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')
    const form = new FormData(event.currentTarget)
    const settings: ChannelSettings = { type, name: String(form.get('name')).trim(), enabled: emailOnly || form.has('enabled') }
    if (type === 'EMAIL') {
      settings.adminRecipients = (formText(form, 'adminRecipients') ?? '').split(/[\n,]/).map(value => value.trim()).filter(Boolean)
      settings.smtpHost = formText(form, 'smtpHost')
      settings.smtpPort = formNumber(form, 'smtpPort')
      settings.smtpTls = formText(form, 'smtpTls') as ChannelSettings['smtpTls']
      settings.smtpUsername = formText(form, 'smtpUsername')
      settings.smtpPassword = form.has('clearSmtpPassword') ? '' : formText(form, 'smtpPassword')
      settings.from = formText(form, 'from')
      settings.subjectTemplate = formText(form, 'subjectTemplate')
      settings.bodyTemplate = formText(form, 'bodyTemplate')
    } else {
      settings.endpoint = formText(form, 'endpoint')
      settings.signingSecret = form.has('clearSigningSecret') ? '' : formText(form, 'signingSecret')
      try { settings.headers = readHeaders(formText(form, 'headers')) }
      catch (failure) { setError((failure as Error).message); return }
    }
    mutation.mutate(settings)
  }

  return <form className="admin-editor notification-editor" onSubmit={save}>
    <h3>{channel ? 'Edit channel' : 'New channel'}</h3>
    <fieldset className="admin-form-fields" disabled={mutation.isPending}>
      <label>Channel name<input name="name" required maxLength={120} defaultValue={channel?.name} /></label>
      {!emailOnly && <><label>Channel type<select value={type} disabled={!!channel} onChange={event => setType(event.target.value as ChannelType)}>
        {types.map(value => <option key={value} value={value}>{channelTypes[value]}</option>)}
      </select></label>
      <label className="notification-check"><input name="enabled" type="checkbox" defaultChecked={channel?.enabled ?? true} /> Enabled</label></>}
    </fieldset>
    {type === 'EMAIL' ? <fieldset className="admin-form-fields" disabled={mutation.isPending}>
      <legend>SMTP and message settings</legend>
      <p className="notification-wide">Blank settings use deployment defaults. When changing the SMTP host, re-enter the password for that host.</p>
      <label>SMTP host<input name="smtpHost" defaultValue={channel?.smtpHost ?? ''} /></label>
      <label>SMTP port<input name="smtpPort" type="number" min={1} max={65535} defaultValue={channel?.smtpPort ?? ''} /></label>
      <label>TLS mode<select name="smtpTls" defaultValue={channel?.smtpTls ?? ''}>
        <option value="">Deployment default</option><option value="STARTTLS">STARTTLS</option>
        <option value="SSL">Implicit TLS</option><option value="NONE">No TLS</option>
      </select></label>
      <label>SMTP username<input name="smtpUsername" autoComplete="off" defaultValue={channel?.smtpUsername ?? ''} /></label>
      <label>SMTP password<input name="smtpPassword" type="password" autoComplete="new-password"
        placeholder={channel?.hasSmtpPassword ? 'Leave blank to keep current password' : undefined} /></label>
      {channel?.hasSmtpPassword && <label className="notification-check"><input name="clearSmtpPassword" type="checkbox" /> Clear saved password</label>}
      <label>Sender address<input name="from" type="email" defaultValue={channel?.from ?? ''} /></label>
      {!emailOnly && <><label className="notification-wide">Admin recipients
        <textarea name="adminRecipients" rows={2} defaultValue={channel?.adminRecipients?.join('\n')}
          placeholder="One email address per line" />
        <small>Used for quota and license alerts. Job emails go to the job owner.</small>
      </label>
      <label className="notification-wide">Subject template<input name="subjectTemplate" maxLength={250}
        defaultValue={channel?.subjectTemplate ?? ''} placeholder="{title}" /></label>
      <label className="notification-wide">Body template<textarea name="bodyTemplate" rows={3} maxLength={10000}
        defaultValue={channel?.bodyTemplate ?? ''} placeholder="{summary}" />
        <small>Template fields are {'{title}'}, {'{eventType}'}, and {'{summary}'}.</small>
      </label></>}
    </fieldset> : <fieldset key={type} className="admin-form-fields" disabled={mutation.isPending}>
      <legend>Webhook settings</legend>
      <label className="notification-wide">Endpoint URL<input name="endpoint" type="password" autoComplete="new-password"
        required={!channel?.hasEndpoint} maxLength={4096} placeholder={channel?.hasEndpoint ? 'Leave blank to keep current URL' : 'https://…'} /></label>
      {channel && <small className="notification-wide">Changing the webhook host, scheme, or port clears saved headers and signing secrets.</small>}
      <label className="notification-wide">HTTP headers (JSON object)
        <textarea name="headers" rows={2} autoComplete="off" placeholder={channel?.hasHeaders
          ? 'Leave blank to keep saved headers. Enter {} to clear.' : '{"Authorization":"Bearer …"}'} />
      </label>
      {type === 'WEBHOOK' && <>
        <label>HMAC signing secret<input name="signingSecret" type="password" autoComplete="new-password"
          placeholder={channel?.hasSigningSecret ? 'Leave blank to keep current secret' : undefined} /></label>
        {channel?.hasSigningSecret && <label className="notification-check"><input name="clearSigningSecret" type="checkbox" /> Clear signing secret</label>}
      </>}
    </fieldset>}
    {(error || mutation.error) && <p role="alert">{error || mutation.error?.message}</p>}
    <div className="row-actions"><Button type="button" variant="quiet" onClick={close}>Cancel</Button>
      <Button type="submit" disabled={mutation.isPending}>Save channel</Button></div>
  </form>
}

const thresholdFields: Record<NotificationEvent, { name: keyof Thresholds; label: string; min: number; max?: number; hint: string }[]> = {
  LARGE_BATCH_COMPLETED: [
    { name: 'fileCount', label: 'Minimum files', min: 1, hint: '1,000 by default' },
    { name: 'bytes', label: 'Minimum bytes', min: 1, hint: '10 GB by default' },
  ],
  TRANSFER_FAILED: [{ name: 'failureCount', label: 'File failures per job', min: 1, hint: '1 by default' }],
  QUOTA_THRESHOLD: [{ name: 'warningPercent', label: 'Quota warning (%)', min: 1, max: 100, hint: '80 by default' }],
  LICENSE_ALERT: [{ name: 'expiryWarningDays', label: 'Days before expiry', min: 0, max: 3650, hint: '30 by default' }],
}

function RuleEditor({ rule, channels, close }: { rule?: NotificationRule; channels: NotificationChannel[]; close: () => void }) {
  const [type, setType] = useState<NotificationEvent>(rule?.eventType ?? 'LARGE_BATCH_COMPLETED')
  const [channelIds, setChannelIds] = useState(rule?.channelIds ?? [])
  const queryClient = useQueryClient()
  const mutation = useMutation({
    mutationFn: (settings: RuleSettings) => notificationService.saveRule(settings, rule?.id),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: keys }); close() },
    meta: { inlineError: true },
  })

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const thresholdConfig: Thresholds = {}
    for (const field of thresholdFields[type]) thresholdConfig[field.name] = formNumber(form, field.name)
    thresholdConfig.cooldownMinutes = formNumber(form, 'cooldownMinutes')
    mutation.mutate({ eventType: type, channelIds, thresholdConfig, enabled: form.has('enabled') })
  }

  return <form className="admin-editor notification-editor" onSubmit={save}>
    <h3>{rule ? 'Edit rule' : 'New rule'}</h3>
    <fieldset className="admin-form-fields" disabled={mutation.isPending}>
      <label>Event<select value={type} onChange={event => setType(event.target.value as NotificationEvent)}>
        {eventTypes.map(value => <option key={value} value={value}>{notificationEvents[value]}</option>)}
      </select></label>
      {thresholdFields[type].map(field => <label key={field.name}>{field.label}
        <input type="number" name={field.name} min={field.min} max={field.max ?? Number.MAX_SAFE_INTEGER}
          defaultValue={rule?.thresholdConfig[field.name] ?? ''} placeholder={field.hint} />
      </label>)}
      {type !== 'LARGE_BATCH_COMPLETED' && <label>Cooldown (minutes)
        <input type="number" name="cooldownMinutes" min={1} max={43200} placeholder="60 by default"
          defaultValue={rule?.thresholdConfig.cooldownMinutes ?? ''} />
      </label>}
      <label className="notification-check"><input name="enabled" type="checkbox" defaultChecked={rule?.enabled ?? true} /> Enabled</label>
      {type === 'LARGE_BATCH_COMPLETED' && <small className="notification-wide">
        Either threshold can trigger the rule. Enter one value to use only that threshold. Leave both blank to use both defaults.
      </small>}
    </fieldset>
    <fieldset className="notification-channel-options" disabled={mutation.isPending}>
      <legend>Delivery channels</legend>
      {channels.map(channel => <label className="notification-check" key={channel.id}>
        <input type="checkbox" checked={channelIds.includes(channel.id)} onChange={event => {
          setChannelIds(current => event.target.checked ? [...current, channel.id] : current.filter(id => id !== channel.id))
        }} /> {channel.name}{channel.enabled ? '' : ' (disabled)'}
      </label>)}
      {!channels.length && <p>Create a channel to route this rule.</p>}
    </fieldset>
    {mutation.error && <p role="alert">{mutation.error.message}</p>}
    <div className="row-actions"><Button type="button" variant="quiet" onClick={close}>Cancel</Button>
      <Button type="submit" disabled={mutation.isPending || !channelIds.length}>Save rule</Button></div>
  </form>
}

function DeliveryAudit({ channels }: { channels: NotificationChannel[] }) {
  const [page, setPage] = useState(0)
  const deliveries = useQuery({
    queryKey: [...keys, 'deliveries', page], queryFn: ({ signal }) => notificationService.deliveries(page, signal),
    refetchInterval: 5_000, meta: { inlineError: true },
  })
  const statuses = { PENDING: 'Pending', SENT: 'Sent', FAILED: 'Failed', SUPPRESSED_OPT_OUT: 'Suppressed by preferences' } as const
  const tones = { PENDING: 'neutral', SENT: 'green', FAILED: 'red', SUPPRESSED_OPT_OUT: 'yellow' } as const
  return <section className="panel admin-group">
    <div className="panel-heading"><h2>Delivery history</h2></div>
    {!deliveries.data ? <ApiPageState loadingLabel="Loading deliveries" error={deliveries.error ?? undefined}
      retry={() => void deliveries.refetch()} retrying={deliveries.isFetching} />
      : deliveries.data.content.length ? <div className="notification-table"><table>
        <thead><tr><th scope="col">Time</th><th scope="col">Event</th><th scope="col">Channel</th><th scope="col">Recipient</th>
          <th scope="col">Status</th><th scope="col">Attempts</th><th scope="col">Error</th></tr></thead>
        <tbody>{deliveries.data.content.map(delivery => <tr key={delivery.id}>
          <td>{time(delivery.createdAt)}</td>
          <td>{delivery.testDelivery ? 'Channel test' : notificationEvents[delivery.eventType]}</td>
          <td>{channels.find(channel => channel.id === delivery.channelId)?.name ?? delivery.channelId}</td>
          <td>{delivery.recipient ?? '—'}</td>
          <td><Badge tone={tones[delivery.status]}>{statuses[delivery.status]}</Badge></td>
          <td>{delivery.attemptCount}</td><td>{delivery.errorMessage ?? '—'}</td>
        </tr>)}</tbody>
      </table></div> : <p>No deliveries recorded.</p>}
    {(page > 0 || (deliveries.data?.totalPages ?? 0) > 1) && <div className="row-actions">
      <Button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous page</Button>
      <span>Page {page + 1}</span>
      <Button disabled={!deliveries.data || page + 1 >= deliveries.data.totalPages} onClick={() => setPage(page + 1)}>Next page</Button>
    </div>}
  </section>
}

export function NotificationSettings() {
  const queryClient = useQueryClient()
  const channels = useQuery({ queryKey: [...keys, 'channels'], queryFn: ({ signal }) => notificationService.channels(signal), meta: { inlineError: true } })
  const rules = useQuery({ queryKey: [...keys, 'rules'], queryFn: ({ signal }) => notificationService.rules(signal), meta: { inlineError: true } })
  const [channelEditor, setChannelEditor] = useState<string | null>(null)
  const [ruleEditor, setRuleEditor] = useState<string | null>(null)
  const test = useMutation({
    mutationFn: notificationService.test,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: [...keys, 'deliveries'] }),
    meta: { inlineError: true },
  })
  const remove = useMutation({
    mutationFn: ({ kind, id }: { kind: 'channels' | 'rules'; id: string }) => notificationService.remove(kind, id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: keys }),
    meta: { inlineError: true },
  })
  if (!channels.data || !rules.data) return <ApiPageState loadingLabel="Loading notification settings" error={channels.error ?? rules.error ?? undefined}
    retry={() => { void channels.refetch(); void rules.refetch() }} retrying={channels.isFetching || rules.isFetching} />
  const savedChannels = channels.data
  return <div className="admin-stack">
    <section className="panel admin-group">
      <div className="panel-heading"><h2>Notification channels</h2><Button onClick={() => setChannelEditor('new')}>Add channel</Button></div>
      {test.error && <p role="alert">{test.error.message}</p>}
      {test.isSuccess && <p className="admin-notice" role="status">Test queued. Check delivery history for the result.</p>}
      {savedChannels.length ? <ul className="admin-endpoints">{savedChannels.map(channel => <li key={channel.id}>
        <div className="admin-endpoint-label"><strong>{channel.name}</strong><small>{channelTypes[channel.type]}</small></div>
        <Badge tone={channel.enabled ? 'green' : 'neutral'}>{channel.enabled ? 'Enabled' : 'Disabled'}</Badge>
        <div className="row-actions">
          <Button aria-label={'Test ' + channel.name} disabled={test.isPending} onClick={() => test.mutate(channel.id)}>Test</Button>
          <Button aria-label={'Edit ' + channel.name} onClick={() => setChannelEditor(channel.id)}>Edit</Button>
          <Button aria-label={'Delete ' + channel.name} disabled={remove.isPending} onClick={() => {
            if (window.confirm('Delete this channel and remove it from alert rules?')) remove.mutate({ kind: 'channels', id: channel.id })
          }}>Delete</Button>
        </div>
      </li>)}</ul> : <p>No channels configured.</p>}
      {channelEditor && <ChannelEditor key={channelEditor} channel={savedChannels.find(channel => channel.id === channelEditor)}
        close={() => setChannelEditor(null)} />}
    </section>
    <section className="panel admin-group">
      <div className="panel-heading"><h2>Alert rules</h2><Button onClick={() => setRuleEditor('new')}>Add rule</Button></div>
      {rules.data.length ? <ul className="admin-endpoints">{rules.data.map(rule => <li key={rule.id}>
        <div className="admin-endpoint-label"><strong>{notificationEvents[rule.eventType]}</strong>
          <small>{savedChannels.filter(channel => rule.channelIds.includes(channel.id)).map(channel => channel.name).join(', ') || 'No channels assigned'}</small>
        </div>
        <Badge tone={rule.enabled ? 'green' : 'neutral'}>{rule.enabled ? 'Enabled' : 'Disabled'}</Badge>
        <div className="row-actions">
          <Button aria-label={'Edit ' + notificationEvents[rule.eventType] + ' rule'} onClick={() => setRuleEditor(rule.id)}>Edit</Button>
          <Button aria-label={'Delete ' + notificationEvents[rule.eventType] + ' rule'} disabled={remove.isPending} onClick={() => {
            if (window.confirm('Delete this alert rule?')) remove.mutate({ kind: 'rules', id: rule.id })
          }}>Delete</Button>
        </div>
      </li>)}</ul> : <p>No alert rules configured.</p>}
      {ruleEditor && <RuleEditor key={ruleEditor} channels={savedChannels} rule={rules.data.find(rule => rule.id === ruleEditor)}
        close={() => setRuleEditor(null)} />}
    </section>
    {remove.error && <p role="alert">{remove.error.message}</p>}
    <DeliveryAudit channels={savedChannels} />
  </div>
}
