export const catalogColumns = {
  name: 'Name',
  size: 'Size',
  state: 'Storage state',
  temperature: 'Temperature',
  modified: 'Modified',
  integrity: 'Integrity',
  tags: 'Tags',
} as const

export type CatalogColumn = keyof typeof catalogColumns
export type CatalogSort = { column: CatalogColumn; direction: 'ascending' | 'descending' }
export type SortValue = string | number | undefined

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })

export function compareCatalogValues(left: SortValue, right: SortValue, direction: CatalogSort['direction']) {
  if (left === undefined) return right === undefined ? 0 : 1
  if (right === undefined) return -1
  const order = typeof left === 'number' && typeof right === 'number'
    ? left - right : collator.compare(String(left), String(right))
  return direction === 'ascending' ? order : -order
}
