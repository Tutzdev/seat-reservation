// Tickets are sold in reais through wallet-ledger's BRL accounts; amounts travel as integer cents.
const money = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'BRL' })

export function formatMoney(cents: number): string {
  return money.format(cents / 100)
}

const dateTime = new Intl.DateTimeFormat('en-US', {
  weekday: 'short',
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
})
const time = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit', second: '2-digit' })
const month = new Intl.DateTimeFormat('en-US', { month: 'short' })

export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso))
}

export function formatTime(iso: string): string {
  return time.format(new Date(iso))
}

export function dayAndMonth(iso: string): { day: string; month: string } {
  const date = new Date(iso)
  return { day: String(date.getDate()), month: month.format(date) }
}

/** 9:41, or 1:02:07 past an hour. */
export function formatClock(milliseconds: number): string {
  const total = Math.max(0, Math.ceil(milliseconds / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = String(total % 60).padStart(2, '0')
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${seconds}` : `${minutes}:${seconds}`
}

/** "in 3 days", "in 2 h 5 min", "in 40 s". */
export function formatUntil(milliseconds: number): string {
  const seconds = Math.max(0, Math.round(milliseconds / 1000))
  if (seconds < 60) return `in ${seconds} s`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `in ${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 48) return `in ${hours} h ${minutes % 60} min`
  return `in ${Math.round(hours / 24)} days`
}

export function seatLabel(seat: { section: string; row: string; number: number }): string {
  return `${seat.section}, row ${seat.row}, seat ${seat.number}`
}

export function plural(count: number, one: string, many = `${one}s`): string {
  return `${count} ${count === 1 ? one : many}`
}
