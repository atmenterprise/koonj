import { isObjectStorage } from '../../api/mappers'
import type { StorageConnectionDTO, StorageUsageDTO } from '../../api/types'

export const savingsRate = 0.02
export const currency = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP', maximumFractionDigits: 2 })
export const monthlySavings = (sizeBytes: number) => sizeBytes / 1e9 * savingsRate

type Totals = { fileCount: number; sizeBytes: number }
export type LocationUsage = {
  connection: StorageConnectionDTO
  stored: Totals
  reclaimed: Totals
  classes: Map<string, Totals>
}

const emptyTotals = (): Totals => ({ fileCount: 0, sizeBytes: 0 })

function addTotals(target: Totals, value: Totals) {
  target.fileCount += value.fileCount
  target.sizeBytes += value.sizeBytes
}

export function summarizeUsage(connections: StorageConnectionDTO[], usage: StorageUsageDTO) {
  const locations = new Map<string, LocationUsage>()
  const mounted = emptyTotals()
  const object = emptyTotals()
  const reclaimed = emptyTotals()
  for (const connection of connections) {
    locations.set(connection.id, { connection, stored: emptyTotals(), reclaimed: emptyTotals(), classes: new Map() })
  }
  for (const entry of usage.footprints) {
    const location = locations.get(entry.connectionId)
    if (!location) continue
    addTotals(location.stored, entry)
    if (isObjectStorage(location.connection.providerType)) {
      addTotals(object, entry)
      const name = entry.storageClass ?? 'STANDARD'
      const totals = location.classes.get(name) ?? emptyTotals()
      addTotals(totals, entry)
      location.classes.set(name, totals)
    } else {
      addTotals(mounted, entry)
    }
  }
  for (const entry of usage.reclaimed) {
    const source = locations.get(entry.sourceConnectionId)
    const target = locations.get(entry.targetConnectionId)
    if (!source || !target || isObjectStorage(source.connection.providerType) || !isObjectStorage(target.connection.providerType)) continue
    addTotals(source.reclaimed, entry)
    addTotals(target.reclaimed, entry)
    addTotals(reclaimed, entry)
  }
  return { locations, mounted, object, reclaimed }
}
