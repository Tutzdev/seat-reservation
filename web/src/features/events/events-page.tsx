import { Link } from 'react-router'
import { Notice } from '@/components/ui/notice'
import { useSession } from '@/features/auth/session'
import { saleState, useEvents } from '@/features/events/api'
import { dayAndMonth, formatDateTime, formatUntil } from '@/lib/format'
import type { EventSummary } from '@/lib/types'
import { useNow } from '@/lib/use-now'

export function EventsPage() {
  const events = useEvents()
  const { isOrganizer } = useSession()

  return (
    <div>
      <h1 className="text-6xl font-extrabold">Upcoming events</h1>
      <p className="mt-3 max-w-2xl text-lg text-ink-soft">
        Pick an event to see its seats. Big sales open behind a waiting room that lets people in at a steady pace,
        so being fast on the button does not matter.
      </p>

      <div className="mt-10">
        {events.isPending && <p className="text-ink-soft">Loading events…</p>}
        {events.isError && <Notice tone="alert">{events.error.message}</Notice>}
        {events.data && events.data.content.length === 0 && (
          <Notice>
            No events are on the calendar yet.{' '}
            {isOrganizer && (
              <Link to="/organizer" className="font-semibold text-velvet underline underline-offset-2">
                Create the first one
              </Link>
            )}
          </Notice>
        )}
        {events.data && events.data.content.length > 0 && (
          <ul className="flex flex-col gap-4">
            {events.data.content.map((event) => (
              <EventRow key={event.id} event={event} />
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function EventRow({ event }: { event: EventSummary }) {
  const now = useNow()
  const state = saleState(event, now)
  const { day, month } = dayAndMonth(event.startsAt)

  const status = {
    upcoming: `Sale opens ${formatUntil(Date.parse(event.salesOpenAt) - now)}`,
    queue: `On sale through a waiting room, ${event.admitsPerMinute} buyers let in per minute`,
    open: 'On sale now',
    started: 'Already started',
  }[state]

  return (
    <li>
      <Link
        to={`/events/${event.id}`}
        className="group grid grid-cols-[4.5rem_1fr] items-stretch overflow-hidden rounded-lg bg-paper-raised
          ring-1 ring-line transition-shadow hover:ring-ink-soft sm:grid-cols-[6rem_1fr_auto]"
      >
        <div className="flex flex-col items-center justify-center bg-ink py-4 text-paper">
          <span className="font-display text-5xl font-extrabold leading-none tabular">{day}</span>
          <span className="mt-1 text-sm">{month}</span>
        </div>
        <div className="min-w-0 px-5 py-4">
          <h2 className="truncate text-3xl font-bold group-hover:text-velvet">{event.name}</h2>
          <p className="mt-1 text-base text-ink-soft">
            {event.venue}, {formatDateTime(event.startsAt)}
          </p>
          <p className={`mt-2 text-sm font-semibold ${state === 'started' ? 'text-ink-soft' : 'text-velvet'}`}>
            {status}
          </p>
        </div>
        <div className="hidden items-center border-l-2 border-dashed border-line px-6 text-base font-semibold
          text-velvet sm:flex">
          {state === 'started' ? 'View' : 'Find seats'}
        </div>
      </Link>
    </li>
  )
}
