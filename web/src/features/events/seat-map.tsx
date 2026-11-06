import { Fragment, useMemo } from 'react'
import { formatMoney, plural } from '@/lib/format'
import type { Seat } from '@/lib/types'

interface Row {
  label: string
  seats: Seat[]
}

interface SectionBlock {
  name: string
  priceCents: number
  available: number
  rows: Row[]
}

function widest(section: SectionBlock): number {
  return Math.max(...section.rows.map((row) => row.seats.length))
}

/** Seats arrive in layout order (ids follow the seat map), so grouping keeps sections and rows in place. */
function layout(seats: Seat[]): SectionBlock[] {
  const sections = new Map<string, SectionBlock>()
  for (const seat of seats) {
    let section = sections.get(seat.section)
    if (!section) {
      section = { name: seat.section, priceCents: seat.priceCents, available: 0, rows: [] }
      sections.set(seat.section, section)
    }
    let row = section.rows.find((candidate) => candidate.label === seat.row)
    if (!row) {
      row = { label: seat.row, seats: [] }
      section.rows.push(row)
    }
    row.seats.push(seat)
    if (seat.status === 'AVAILABLE') section.available += 1
  }
  return [...sections.values()]
}

interface SeatMapProps {
  seats: Seat[]
  selected: ReadonlySet<number>
  /** Seats you tried to hold that someone else got first; they flash once. */
  lost: ReadonlySet<number>
  onToggle?: (seat: Seat) => void
  disabled?: boolean
}

export function SeatMap({ seats, selected, lost, onToggle, disabled = false }: SeatMapProps) {
  const sections = useMemo(() => layout(seats), [seats])

  return (
    <div className="flex flex-col gap-10">
      <div aria-hidden className="mx-auto w-3/4 rounded-b-[50%] bg-ink py-2 text-center text-sm text-paper">
        Stage
      </div>
      {sections.map((section) => (
        <section key={section.name} aria-label={`${section.name}, ${formatMoney(section.priceCents)} each`}>
          <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4">
            <h3 className="text-2xl font-bold">{section.name}</h3>
            <p className="text-sm text-ink-soft tabular">
              {formatMoney(section.priceCents)} each, {plural(section.available, 'seat')} left
            </p>
          </div>
          {/* Seats shrink to fit the container, down to a size a finger can still hit; past that, the map scrolls. */}
          <div className="overflow-x-auto pb-2">
            <div
              className="mx-auto grid items-center gap-1 sm:gap-1.5"
              style={{
                gridTemplateColumns: `1.75rem repeat(${widest(section)}, minmax(1rem, 1.75rem)) 1.75rem`,
                maxWidth: `${widest(section) * 2.125 + 4}rem`,
              }}
            >
              {section.rows.map((row) => (
                <Fragment key={row.label}>
                  <span className="pr-1 text-right text-xs text-ink-soft">{row.label}</span>
                  {row.seats.map((seat) => (
                    <SeatButton
                      key={seat.id}
                      seat={seat}
                      selected={selected.has(seat.id)}
                      lost={lost.has(seat.id)}
                      onToggle={onToggle}
                      disabled={disabled}
                    />
                  ))}
                  {/* Shorter rows are padded so the row label on the right stays in its column. */}
                  {Array.from({ length: widest(section) - row.seats.length }, (_, i) => <span key={i} />)}
                  <span className="pl-1 text-xs text-ink-soft">{row.label}</span>
                </Fragment>
              ))}
            </div>
          </div>
        </section>
      ))}
    </div>
  )
}

function SeatButton({ seat, selected, lost, onToggle, disabled }: {
  seat: Seat
  selected: boolean
  lost: boolean
  onToggle?: (seat: Seat) => void
  disabled: boolean
}) {
  const available = seat.status === 'AVAILABLE'
  const look = selected
    ? 'bg-highlight ring-2 ring-ink'
    : available
      ? 'bg-velvet hover:bg-velvet-deep'
      : 'bg-taken'
  const state = selected ? 'selected' : available ? 'available' : 'taken'
  return (
    <button
      type="button"
      onClick={() => onToggle?.(seat)}
      disabled={disabled || (!available && !selected) || !onToggle}
      aria-pressed={onToggle ? selected : undefined}
      aria-label={`Row ${seat.row}, seat ${seat.number}, ${state}`}
      title={`Row ${seat.row}, seat ${seat.number}`}
      className={`aspect-square w-full rounded-t-[40%] rounded-b-sm transition-colors disabled:cursor-default
        ${look} ${lost ? 'animate-[lost_900ms_ease-out_1] outline-3 outline-alert' : ''}`}
    />
  )
}

export function SeatLegend({ picking = true }: { picking?: boolean }) {
  const items = [
    { label: 'Available', className: 'bg-velvet' },
    ...(picking ? [{ label: 'Your pick', className: 'bg-highlight ring-2 ring-ink' }] : []),
    { label: picking ? 'Taken' : 'Held or sold', className: 'bg-taken' },
  ]
  return (
    <ul className="flex flex-wrap gap-x-6 gap-y-2 text-sm text-ink-soft">
      {items.map((item) => (
        <li key={item.label} className="flex items-center gap-2">
          <span aria-hidden className={`size-4 rounded-t-md rounded-b-sm ${item.className}`} />
          {item.label}
        </li>
      ))}
    </ul>
  )
}
