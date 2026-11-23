import type { TemperaturePolicy } from '../../api/services/managementService'

const millisecondsPerDay = 86_400_000
const defaultTemperaturePolicy: TemperaturePolicy = { hotDays: 15, coldDays: 30 }

export function temperatureOf(modified?: string | null, policy = defaultTemperaturePolicy, now = Date.now()) {
  const time = Date.parse(modified ?? '')
  if (!Number.isFinite(time)) return undefined
  const age = (now - time) / millisecondsPerDay
  return age < policy.hotDays ? 'HOT' : age < policy.coldDays ? 'WARM' : 'COLD'
}

export function fileAge(created?: string | null, modified?: string | null, now = Date.now()) {
  const creationTime = Date.parse(created ?? '')
  const fromCreation = Number.isFinite(creationTime) && creationTime <= now
  const time = fromCreation ? creationTime : Date.parse(modified ?? '')
  if (!Number.isFinite(time) || time > now) return undefined
  const days = Math.floor((now - time) / millisecondsPerDay)
  const [value, unit] = days >= 365 ? [Math.floor(days / 365), 'year'] : days >= 30 ? [Math.floor(days / 30), 'month'] : [days, 'day']
  const age = value ? `${value} ${unit}${value === 1 ? '' : 's'}` : 'Less than a day'
  return fromCreation ? age : `${age} since last modified`
}
