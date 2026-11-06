import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Notice } from '@/components/ui/notice'
import { useSession } from '@/features/auth/session'
import { useHold } from '@/features/bookings/api'
import { saleState, useEvent, useSeats } from '@/features/events/api'
import { SeatLegend, SeatMap } from '@/features/events/seat-map'
import { useJoinQueue, useQueuePlace } from '@/features/queue/api'
import { PassBanner, QueueTicket } from '@/features/queue/queue-ticket'
import { ApiError, newIdempotencyKey } from '@/lib/api'
import { formatClock, formatDateTime, formatMoney, plural } from '@/lib/format'
import type { EventDetail, Seat } from '@/lib/types'
import { useNow } from '@/lib/use-now'

const MAX_SEATS = 6

export function EventPage() {
  const { eventId = '' } = useParams()
  const event = useEvent(eventId)
  const now = useNow()

  if (event.isPending) return <p className="text-ink-soft">Loading the event…</p>
  if (event.isError) return <Notice tone="alert">{event.error.message}</Notice>

  const state = saleState(event.data, now)
  return (
    <div>
      <EventHeader event={event.data} />
      <div className="mt-10">
        {state === 'started' && <Notice>This event has started, so its seats are no longer on sale.</Notice>}
        {state === 'upcoming' && <SaleCountdown event={event.data} />}
        {state === 'queue' && <WaitingRoomGate event={event.data} />}
        {state === 'open' && <SeatPicker event={event.data} />}
      </div>
    </div>
  )
}

function EventHeader({ event }: { event: EventDetail }) {
  return (
    <header className="flex flex-col gap-6 border-b border-line pb-8 lg:flex-row lg:items-end lg:justify-between">
      <div>
        <h1 className="max-w-4xl text-5xl font-extrabold sm:text-6xl">{event.name}</h1>
        <p className="mt-3 text-lg text-ink-soft">
          {event.venue}, {formatDateTime(event.startsAt)}
        </p>
      </div>
      <dl className="flex flex-wrap gap-x-8 gap-y-3">
        {event.sections.map((section) => (
          <div key={section.name}>
            <dt className="text-sm text-ink-soft">{section.name}</dt>
            <dd className="font-display text-2xl font-bold tabular">{formatMoney(section.priceCents)}</dd>
          </div>
        ))}
        <div>
          <dt className="text-sm text-ink-soft">Seats left</dt>
          <dd className="font-display text-2xl font-bold tabular">
            {event.availableSeats} of {event.totalSeats}
          </dd>
        </div>
      </dl>
    </header>
  )
}

function SaleCountdown({ event }: { event: EventDetail }) {
  const now = useNow()
  const left = Date.parse(event.salesOpenAt) - now
  return (
    <section aria-labelledby="countdown-title" className="rounded-xl bg-ink px-6 py-10 text-paper sm:px-10">
      <h2 id="countdown-title" className="text-2xl font-bold">Tickets go on sale in</h2>
      <p className="mt-4 font-display text-7xl font-black leading-none tabular sm:text-[9rem]">{formatClock(left)}</p>
      <p className="mt-6 max-w-xl text-lg text-paper/80">
        {event.admitsPerMinute
          ? `Then a waiting room opens and lets in ${event.admitsPerMinute} buyers a minute, in the order they join. Staying on this page is enough.`
          : 'Seats can be picked as soon as the clock reaches zero. This page updates on its own.'}
      </p>
    </section>
  )
}

function WaitingRoomGate({ event }: { event: EventDetail }) {
  const { session } = useSession()
  const location = useLocation()
  const place = useQueuePlace(event.id, Boolean(session))
  const join = useJoinQueue(event.id)
  const joinedAt = useRef(Date.now())
  const now = useNow()

  if (!session) {
    return (
      <Notice>
        This sale has a waiting room, and your place in it belongs to your account.{' '}
        <Link to={`/sign-in?next=${encodeURIComponent(location.pathname)}`} className="font-semibold text-velvet underline underline-offset-2">
          Sign in to join the line
        </Link>
      </Notice>
    )
  }
  if (place.isPending) return <p className="text-ink-soft">Checking your place in line…</p>
  if (place.isError) return <Notice tone="alert">{place.error.message}</Notice>

  const current = place.data
  const passValid = current && Date.parse(current.passExpiresAt) > now
  if (!current || !passValid) {
    return (
      <section aria-labelledby="join-title" className="max-w-2xl">
        <h2 id="join-title" className="text-4xl font-bold">
          {current ? 'Your pass ran out' : 'This sale has a waiting room'}
        </h2>
        <p className="mt-3 text-lg text-ink-soft">
          {current
            ? 'You can join the line again; you will be placed at the end.'
            : `Everyone gets a place in line and is let in at ${event.admitsPerMinute} buyers a minute. Once it is your turn, you have 10 minutes to pick seats.`}
        </p>
        {join.isError && <div className="mt-4"><Notice tone="alert">{join.error.message}</Notice></div>}
        <Button className="mt-6" busy={join.isPending} onClick={() => {
          joinedAt.current = Date.now()
          join.mutate()
        }}>
          Join the line
        </Button>
      </section>
    )
  }

  if (!current.admitted && Date.parse(current.admittedAt) > now) {
    return <QueueTicket event={event} place={current} joinedAt={joinedAt.current} />
  }
  return (
    <div className="flex flex-col gap-6">
      <PassBanner place={current} />
      <SeatPicker event={event} />
    </div>
  )
}

function SeatPicker({ event }: { event: EventDetail }) {
  const { session } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const seats = useSeats(event.id, true)
  const hold = useHold()
  const [selected, setSelected] = useState<ReadonlySet<number>>(new Set())
  const [lost, setLost] = useState<ReadonlySet<number>>(new Set())
  const [message, setMessage] = useState<{ tone: 'alert' | 'info'; text: string; booking?: string } | null>(null)
  const attemptKey = useRef(newIdempotencyKey())

  const byId = useMemo(() => new Map((seats.data ?? []).map((seat) => [seat.id, seat])), [seats.data])

  // Someone else can take a seat you picked between two refreshes of the map: drop it and say so.
  useEffect(() => {
    const gone = [...selected].filter((id) => byId.get(id)?.status !== 'AVAILABLE')
    if (gone.length > 0 && !hold.isPending) {
      setSelected((current) => new Set([...current].filter((id) => !gone.includes(id))))
      setLost(new Set(gone))
      setMessage({ tone: 'info', text: `${describeSeats(gone, byId)} ${gone.length === 1 ? 'was' : 'were'} just taken by someone else.` })
    }
  }, [byId, selected, hold.isPending])

  const picked = [...selected].map((id) => byId.get(id)).filter((seat): seat is Seat => Boolean(seat))
  const total = picked.reduce((sum, seat) => sum + seat.priceCents, 0)

  function toggle(seat: Seat) {
    attemptKey.current = newIdempotencyKey()
    setMessage(null)
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(seat.id)) {
        next.delete(seat.id)
      } else if (next.size < MAX_SEATS) {
        next.add(seat.id)
      } else {
        setMessage({ tone: 'info', text: `One booking holds up to ${MAX_SEATS} seats.` })
      }
      return next
    })
  }

  function holdSeats() {
    if (!session) {
      navigate(`/sign-in?next=${encodeURIComponent(location.pathname)}`)
      return
    }
    hold.mutate(
      { eventId: event.id, seatIds: [...selected], key: attemptKey.current },
      {
        onSuccess: (booking) => navigate(`/bookings/${booking.id}`),
        onError: (error) => {
          attemptKey.current = newIdempotencyKey()
          if (!(error instanceof ApiError)) {
            setMessage({ tone: 'alert', text: 'The server could not be reached. Your seats were not held; try again.' })
            return
          }
          if (error.code === 'SEATS_UNAVAILABLE') {
            const taken = (error.body.unavailableSeatIds as number[] | undefined) ?? []
            setLost(new Set(taken))
            setSelected((current) => new Set([...current].filter((id) => !taken.includes(id))))
            setMessage({
              tone: 'alert',
              text: `${describeSeats(taken, byId)} ${taken.length === 1 ? 'was' : 'were'} taken a moment before you. Nothing was held, so pick again.`,
            })
          } else if (error.code === 'HOLD_ALREADY_ACTIVE') {
            setMessage({ tone: 'alert', text: 'You already hold seats for this event.', booking: error.body.bookingId as string })
          } else if (error.code === 'TOO_MANY_REQUESTS' && error.retryAfterSeconds) {
            setMessage({ tone: 'alert', text: `Too many requests from your account. Try again in ${error.retryAfterSeconds} s.` })
          } else {
            setMessage({ tone: 'alert', text: error.message })
          }
        },
      },
    )
  }

  if (seats.isPending) return <p className="text-ink-soft">Loading the seat map…</p>
  if (seats.isError) return <Notice tone="alert">{seats.error.message}</Notice>

  return (
    <div className="grid grid-cols-1 gap-8 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
      <div className="min-w-0 rounded-xl bg-paper-raised p-4 ring-1 ring-line sm:p-8">
        <div className="mb-8 flex flex-wrap items-center justify-between gap-4">
          <h2 className="text-3xl font-bold">Pick your seats</h2>
          <SeatLegend />
        </div>
        <SeatMap seats={seats.data} selected={selected} lost={lost} onToggle={toggle} disabled={hold.isPending} />
      </div>

      {/* On a phone the selection sticks to the bottom only once there is one, so it never hides an empty map. */}
      <aside aria-label="Your selection" className={`${picked.length > 0 ? 'sticky' : ''} bottom-0 -mx-4 border-t
        border-line bg-paper-raised p-4 sm:-mx-6 sm:px-6 lg:sticky lg:top-6 lg:mx-0 lg:rounded-xl lg:border-0 lg:p-6
        lg:ring-1 lg:ring-line`}>
        {message && (
          <div className="mb-4">
            <Notice tone={message.tone}>
              {message.text}{' '}
              {message.booking && (
                <Link to={`/bookings/${message.booking}`} className="font-semibold text-velvet underline underline-offset-2">
                  Go to that booking
                </Link>
              )}
            </Notice>
          </div>
        )}
        {picked.length === 0 ? (
          <p className="text-base text-ink-soft">Select up to {MAX_SEATS} seats on the map. Taken seats are grey.</p>
        ) : (
          <>
            <h2 className="text-2xl font-bold">{plural(picked.length, 'seat')}</h2>
            <ul className="mt-3 flex flex-col gap-1 text-base">
              {picked.map((seat) => (
                <li key={seat.id} className="flex justify-between gap-4 tabular">
                  <span>{seat.section}, row {seat.row}, seat {seat.number}</span>
                  <span className="text-ink-soft">{formatMoney(seat.priceCents)}</span>
                </li>
              ))}
            </ul>
            <p className="mt-4 flex justify-between border-t border-line pt-4 font-display text-3xl font-bold tabular">
              <span>Total</span>
              <span>{formatMoney(total)}</span>
            </p>
          </>
        )}
        <Button className="mt-4 w-full" disabled={picked.length === 0} busy={hold.isPending} onClick={holdSeats}>
          {session ? 'Hold these seats' : 'Sign in to hold seats'}
        </Button>
        <p className="mt-3 text-sm text-ink-soft">They are yours for 10 minutes while you pay.</p>
      </aside>
    </div>
  )
}

function describeSeats(ids: number[], byId: Map<number, Seat>): string {
  const labels = ids.map((id) => byId.get(id)).filter((seat): seat is Seat => Boolean(seat))
    .map((seat) => `row ${seat.row} seat ${seat.number}`)
  if (labels.length === 0) return ids.length === 1 ? 'A seat you picked' : 'Seats you picked'
  const text = labels.length === 1 ? labels[0] : `${labels.slice(0, -1).join(', ')} and ${labels.at(-1)}`
  return text.charAt(0).toUpperCase() + text.slice(1)
}
