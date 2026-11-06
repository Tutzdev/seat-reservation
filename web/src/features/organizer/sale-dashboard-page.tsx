import { useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Notice } from '@/components/ui/notice'
import { useEvent, useSeats } from '@/features/events/api'
import { SeatLegend, SeatMap } from '@/features/events/seat-map'
import { useSaleDashboard } from '@/features/organizer/api'
import { runCrowd, type CrowdEvent, type CrowdTally } from '@/features/organizer/crowd'
import { formatClock, formatDateTime, formatUntil } from '@/lib/format'
import type { EventDetail, SaleDashboard } from '@/lib/types'
import { useNow } from '@/lib/use-now'

const NONE = new Set<number>()

export function SaleDashboardPage() {
  const { eventId = '' } = useParams()
  const event = useEvent(eventId)
  const dashboard = useSaleDashboard(eventId)
  const seats = useSeats(eventId, true)

  if (event.isPending) return <p className="text-ink-soft">Loading the sale…</p>
  if (event.isError) return <Notice tone="alert">{event.error.message}</Notice>

  return (
    <div className="flex flex-col gap-10">
      <header>
        <Link to="/organizer" className="text-sm font-semibold text-ink-soft hover:text-ink">Organizer</Link>
        <h1 className="mt-2 max-w-4xl text-5xl font-extrabold sm:text-6xl">{event.data.name}</h1>
        <p className="mt-2 text-lg text-ink-soft">
          {event.data.venue}, {formatDateTime(event.data.startsAt)}.{' '}
          <Link to={`/events/${eventId}`} className="font-semibold text-velvet underline underline-offset-2">
            Open the buyer's page
          </Link>
        </p>
      </header>

      {dashboard.isError && <Notice tone="alert">{dashboard.error.message}</Notice>}
      {dashboard.data && <Numbers dashboard={dashboard.data} />}

      <div className="grid grid-cols-1 gap-8 lg:grid-cols-[minmax(0,1fr)_22rem] lg:items-start">
        <section aria-labelledby="map-title" className="min-w-0 rounded-xl bg-paper-raised p-4 ring-1 ring-line sm:p-8">
          <div className="mb-8 flex flex-wrap items-center justify-between gap-4">
            <h2 id="map-title" className="text-3xl font-bold">Seat map, live</h2>
            <SeatLegend picking={false} />
          </div>
          {seats.data && <SeatMap seats={seats.data} selected={NONE} lost={NONE} />}
        </section>
        <CrowdPanel event={event.data} />
      </div>
    </div>
  )
}

function Numbers({ dashboard }: { dashboard: SaleDashboard }) {
  const now = useNow()
  const { seats, holds, waitingRoom } = dashboard
  const share = (count: number) => `${(count / Math.max(1, seats.total)) * 100}%`
  const opensIn = Date.parse(dashboard.salesOpenAt) - now

  return (
    <section aria-label="Sale numbers" className="flex flex-col gap-6">
      <div>
        <div className="flex h-5 overflow-hidden rounded-full bg-velvet" role="img"
          aria-label={`${seats.sold} sold, ${seats.held} held, ${seats.available} available of ${seats.total}`}>
          <div className="h-full bg-ink transition-[width] duration-700" style={{ width: share(seats.sold) }} />
          <div className="h-full bg-taken transition-[width] duration-700" style={{ width: share(seats.held) }} />
        </div>
        <dl className="mt-4 grid grid-cols-2 gap-6 sm:grid-cols-4">
          <Stat label="Sold" value={seats.sold} swatch="bg-ink" />
          <Stat label="Held, waiting for payment" value={seats.held} swatch="bg-taken" />
          <Stat label="Available" value={seats.available} swatch="bg-velvet" />
          <Stat label="Seats" value={seats.total} />
        </dl>
      </div>
      <dl className="grid grid-cols-2 gap-6 border-t border-line pt-6 sm:grid-cols-4">
        <Stat label="Holds won" value={holds.won} />
        <Stat label="Lost to a faster buyer" value={holds.lost} />
        {waitingRoom ? (
          <>
            <Stat label="Waiting in line" value={waitingRoom.waiting} />
            <div>
              <dt className="text-sm text-ink-soft">Line clears</dt>
              <dd className="font-display text-4xl font-bold tabular">
                {waitingRoom.clearsAt ? formatClock(Date.parse(waitingRoom.clearsAt) - now) : 'Empty'}
              </dd>
              <dd className="text-sm text-ink-soft">{waitingRoom.admitsPerMinute} let in per minute</dd>
            </div>
          </>
        ) : (
          <div className="col-span-2">
            <dt className="text-sm text-ink-soft">Waiting room</dt>
            <dd className="text-lg">None: buyers pick seats as soon as the sale opens.</dd>
          </div>
        )}
      </dl>
      {opensIn > 0 && <Notice>The sale opens {formatUntil(opensIn)}. Buyers see a countdown until then.</Notice>}
    </section>
  )
}

function Stat({ label, value, swatch }: { label: string; value: number; swatch?: string }) {
  return (
    <div>
      <dt className="flex items-center gap-2 text-sm text-ink-soft">
        {swatch && <span aria-hidden className={`size-3 rounded-sm ${swatch}`} />}
        {label}
      </dt>
      <dd className="font-display text-4xl font-bold tabular">{value}</dd>
    </div>
  )
}

// On the ink panel: yellow is a seat won, a light red a race lost.
const logTone: Record<CrowdEvent['tone'], string> = {
  won: 'text-highlight',
  lost: 'text-[#ff9a94]',
  info: 'text-paper/70',
  failed: 'text-[#ff9a94]',
}

/**
 * Sends real buyers through the real API from this browser, so the waiting room and the seat race can be watched
 * without a load-testing tool. The full-size version is the k6 scenario in load/.
 */
function CrowdPanel({ event }: { event: EventDetail }) {
  const [buyers, setBuyers] = useState('60')
  const [tally, setTally] = useState<CrowdTally | null>(null)
  const [log, setLog] = useState<CrowdEvent[]>([])
  const [running, setRunning] = useState(false)
  const controller = useRef<AbortController | null>(null)
  const now = useNow()
  const saleOpen = Date.parse(event.salesOpenAt) <= now && Date.parse(event.startsAt) > now
  const count = Math.min(300, Math.max(1, Number(buyers) || 0))

  async function start() {
    controller.current = new AbortController()
    setRunning(true)
    setLog([])
    try {
      await runCrowd({
        eventId: event.id,
        buyers: count,
        hasWaitingRoom: Boolean(event.admitsPerMinute),
        signal: controller.current.signal,
        onTally: setTally,
        onEvent: (entry) => setLog((current) => [entry, ...current].slice(0, 12)),
      })
    } finally {
      setRunning(false)
    }
  }

  return (
    <aside aria-labelledby="crowd-title" className="rounded-xl bg-ink p-6 text-paper lg:sticky lg:top-6">
      <h2 id="crowd-title" className="text-3xl font-bold">Send a crowd</h2>
      <p className="mt-2 text-sm text-paper/75">
        Simulated buyers sign up, queue and race for seats through the same API as everyone else. Each holds one or
        two random seats.
      </p>
      <div className="mt-5 flex items-end gap-3 [&_label]:text-paper">
        <Field label="Buyers" type="number" min={1} max={300} value={buyers} disabled={running}
          onChange={(change) => setBuyers(change.target.value)} className="w-28 [&_input]:text-ink" />
        {running ? (
          <Button variant="quiet" className="text-paper ring-paper/40 hover:bg-paper/10" onClick={() => controller.current?.abort()}>
            Stop
          </Button>
        ) : (
          <Button onClick={start} disabled={!saleOpen}>Send {count} buyers</Button>
        )}
      </div>
      {!saleOpen && <p className="mt-3 text-sm text-highlight">The crowd can be sent once the sale is open.</p>}

      {tally && (
        <dl className="mt-6 grid grid-cols-3 gap-4 border-t border-paper/20 pt-5 tabular">
          <CrowdStat label="Started" value={tally.started} />
          <CrowdStat label="In line" value={tally.inLine} />
          <CrowdStat label="Holding" value={tally.won} />
          <CrowdStat label="Lost a race" value={tally.lost} />
          <CrowdStat label="Sold out" value={tally.soldOut} />
          <CrowdStat label="Failed" value={tally.failed} />
        </dl>
      )}
      {log.length > 0 && (
        <ol aria-label="Latest buyers" className="mt-5 flex flex-col gap-1 border-t border-paper/20 pt-4 text-sm">
          {log.map((entry, index) => (
            <li key={`${entry.buyer}-${index}`} className={logTone[entry.tone]}>
              Buyer {entry.buyer} {entry.text}
            </li>
          ))}
        </ol>
      )}
    </aside>
  )
}

function CrowdStat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-xs text-paper/70">{label}</dt>
      <dd className="font-display text-3xl font-bold">{value}</dd>
    </div>
  )
}
