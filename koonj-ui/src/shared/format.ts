export function bytes(value: number) {
  if (!value) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  const power = Math.min(units.length - 1, Math.floor(Math.log10(value) / 3))
  return new Intl.NumberFormat('en-GB', { maximumFractionDigits: 1 }).format(value / 1000 ** power) + ' ' + units[power]
}
