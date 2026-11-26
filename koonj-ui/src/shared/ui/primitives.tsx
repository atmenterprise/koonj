import type { ButtonHTMLAttributes, ReactNode } from 'react'

const paths = {
  home: 'm3 10 9-7 9 7v10H3z M9 20v-7h6v7',
  projects: 'M3 7h18v13H3z M8 7V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2 M3 11q9 5 18 0 M10 12v3h4v-3',
  folder: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z',
  activity: 'M3 12h4l3-8 4 16 3-8h4',
  server: 'M4 4h16v7H4z M4 13h16v7H4z M7 7.5h.01 M7 16.5h.01 M11 7.5h5 M11 16.5h5',
  drive: 'M5 6h14l3 10v3H2v-3z M2 16h20 M6 19v.01 M18 19v.01',
  cloud: 'M6 18h12a4 4 0 0 0 .4-8 6.5 6.5 0 0 0-12.6-1.4A4.7 4.7 0 0 0 6 18z',
  files: 'M8 3h7l4 4v12H8z M14 3v5h5 M5 6v16h11',
  bolt: 'm13 2-9 12h7l-1 8 10-12h-7z',
  chevron: 'm9 18 6-6-6-6',
  down: 'm6 9 6 6 6-6',
  arrow: 'M4 12h15m-6-6 6 6-6 6',
  external: 'M7 17 17 7H8m9 0v9',
  upload: 'M4 16v4h16v-4 M12 16V4m-5 5 5-5 5 5',
  download: 'M4 16v4h16v-4 M12 4v12m-5-5 5 5 5-5',
  check: 'm5 12 4 4L19 6',
  warning: 'M12 3 2 20h20z M12 9v4m0 3h.01',
  bell: 'M4 17h16l-2-3v-4a6 6 0 1 0-12 0v4z M10 20h4',
  pause: 'M9 5v14m6-14v14',
  play: 'm8 5 12 7-12 7z',
  stop: 'M6 6h12v12H6z',
  link: 'M10 13a4 4 0 0 0 6 .4l4-4a4 4 0 0 0-5.7-5.7l-2.3 2.3m2 5a4 4 0 0 0-6-.4l-4 4a4 4 0 0 0 5.7 5.7l2.3-2.3',
  scan: 'M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5M4 12h16',
  search: 'M15.5 15.5 21 21 M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z',
  user: 'M15.5 8a3.5 3.5 0 1 1-7 0 3.5 3.5 0 0 1 7 0ZM4.5 21v-2a7.5 7.5 0 0 1 15 0v2',
  logout: 'M9 4H4v16h5m5-13 5 5-5 5m5-5H8',
  file: 'M6 3h8l4 4v14H6z M14 3v5h4 M9 13h6m-6 4h6',
  info: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Z M12 11v6m0-10h.01',
  star: 'm12 3 2.8 5.8 6.4.9-4.6 4.5 1.1 6.4-5.7-3-5.7 3 1.1-6.4-4.6-4.5 6.4-.9L12 3z',
  film: 'M4 4h16v16H4z M8 4v16m8-16v16M4 9h4m8 0h4M4 15h4m8 0h4',
  close: 'm6 6 12 12M18 6 6 18',
  clock: 'M12 7v5l3 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  retry: 'M20 11a8 8 0 1 0-2 6m2-11v5h-5',
  menu: 'M4 6h16M4 12h16M4 18h16',
  settings: 'M4 6h5m4 0h7M4 12h9m4 0h3M4 18h3m4 0h9M13 6a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM17 12a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM11 18a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z',
  sidebar: 'M3 4h18v16H3z M9 4v16',
  maximize: 'M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5',
  minimize: 'M3 8h5V3m13 5h-5V3M8 21v-5H3m13 5v-5h5',
  billing: 'M3 5h18v14H3z M3 10h18 M7 15h4',
  support: 'M3 12a9 9 0 1 1 18 0v5h-4v-6h4 M3 11h4v6H3z M17 17v2a2 2 0 0 1-2 2h-3',
  layers: 'm12 3 9 5-9 5-9-5z M3 12l9 5 9-5M3 16l9 5 9-5',
} as const
export type IconName = keyof typeof paths

export function Icon({ name, size = 18 }: { name: IconName; size?: number }) {
  return <svg aria-hidden="true" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round"><path d={paths[name]} /></svg>
}

export function Button({ icon, variant = '', children, className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & {
  icon?: IconName; variant?: 'primary' | 'quiet' | 'danger' | ''; children?: ReactNode
}) {
  return <button type="button" {...props} className={`button ${variant} ${className}`}>{icon && <Icon name={icon} size={15} />}{children}</button>
}

export function Badge({ children, tone = 'neutral', dot = false }: { children: ReactNode; tone?: string; dot?: boolean }) {
  return <span className={`badge ${tone}`}>{dot && <i />}{children}</span>
}

export function Metric({ icon, tone, title, value, children }: {
  icon: IconName; tone: string; title: string; value: string; children?: ReactNode
}) {
  return <article className="panel metric">
    <div className="metric-label"><span className={`icon-tile ${tone}`}><Icon name={icon} size={18} /></span><h2>{title}</h2></div>
    <div className="metric-number">{value}</div>
    {children && <div className="metric-detail">{children}</div>}
  </article>
}

export function Progress({ value, label, tone = '' }: { value: number; label: string; tone?: string }) {
  return <div aria-label={label} className={`progress ${tone}`} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(value)}>
    <span style={{ width: `${value}%` }} />
  </div>
}

const counts = new Intl.NumberFormat('en-GB')
const dateFormat = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' })
const timeFormat = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' })
export const count = (value: number) => counts.format(value)
export const date = (value: string | number) => dateFormat.format(new Date(value))
export const time = (value: string | number) => timeFormat.format(new Date(value)) + ' UTC'
export const percent = (done: number, total: number) => total ? Math.min(100, done * 100 / total) : 0
export { bytes } from '../format'
export const duration = (seconds: number) => seconds >= 3600
  ? `${Math.floor(seconds / 3600)}h ${Math.floor(seconds / 60) % 60}m`
  : `${Math.floor(seconds / 60)}m ${String(seconds % 60).padStart(2, '0')}s`

export const statusTone: Record<string, string> = {
  Running: 'blue', Paused: 'yellow', Completed: 'green', 'Partial Failure': 'yellow', Failed: 'red', Cancelled: 'neutral',
  Archived: 'green', 'Purged Stubs': 'purple', 'In Progress': 'blue', 'Local Only': 'neutral',
}
export const storageName: Record<string, string> = { STANDARD: 'Standard', GLACIER_IR: 'Glacier IR', DEEP_ARCHIVE: 'Deep Archive' }
export const temperatureTone = { HOT: 'red', WARM: 'yellow', COLD: 'blue' } as const
