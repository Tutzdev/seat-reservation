import { formatClock, formatTime } from '@/lib/format'
import type { EventDetail, QueuePlace } from '@/lib/types'
import { useNow } from '@/lib/use-now'

/**
 * The one loud thing in the interface: your place in line, printed on a highlighter-yellow stub. Yellow always
 * means "yours" here. The number counts down on its own between polls, from the admission time the server gave.
 */
export function QueueTicket({ event, place, joinedAt }: { event: EventDetail; place: QueuePlace; joinedAt: number }) {
  const now = useNow()
  const admittedAt = Date.parse(place.admittedAt)
  const interval = 60_000 / (event.admitsPerMinute ?? 60)
  const waiting = Math.max(0, admittedAt - now)
  const ahead = Math.ceil(waiting / interval)
  const total = Math.max(1, admittedAt - Math.min(joinedAt, admittedAt))
  const progress = Math.min(1, 1 - waiting / total)

  return (
    <section aria-labelledby="queue-title" className="grid overflow-hidden rounded-xl bg-highlight text-ink
      shadow-[0_1px_0_var(--line)] sm:grid-cols-[1fr_14rem]">
      <div className="p-6 sm:p-10">
        <h2 id="queue-title" className="text-2xl font-bold">You are in line</h2>
        <p className="mt-6 font-display text-7xl font-black leading-none tabular sm:text-[10rem]" aria-live="polite"
          aria-atomic>
          {ahead}
        </p>
        <p className="mt-2 text-lg">
          {ahead === 1 ? 'person' : 'people'} ahead of you. Keep this page open: it moves on its own.
        </p>
        <div className="mt-8 h-2 overflow-hidden rounded-full bg-ink/15" aria-hidden>
          <div className="h-full rounded-full bg-ink transition-[width] duration-500 ease-out"
            style={{ width: `${progress * 100}%` }} />
        </div>
        <p className="mt-3 text-base tabular">
          Your turn at {formatTime(place.admittedAt)}, in {formatClock(waiting)}.
        </p>
      </div>
      <div className="perforated-top relative border-t-2 border-dashed border-ink/30 bg-highlight p-6
        sm:perforated-left sm:border-l-2 sm:border-t-0 sm:p-8">
        <p className="text-sm">Admit one</p>
        <p className="mt-2 font-display text-3xl font-bold leading-tight">{event.name}</p>
        <p className="mt-3 text-sm">{event.venue}</p>
        <p className="mt-6 text-sm">
          The room lets in {event.admitsPerMinute} buyers a minute. Your place does not depend on how often you
          refresh.
        </p>
      </div>
    </section>
  )
}

/** Once you are in: how long the pass lasts before the place goes back to the end of the line. */
export function PassBanner({ place }: { place: QueuePlace }) {
  const now = useNow()
  const left = Date.parse(place.passExpiresAt) - now
  return (
    <div role="status" className="flex flex-wrap items-baseline gap-x-4 gap-y-1 rounded-lg bg-highlight px-5 py-3">
      <p className="font-display text-2xl font-bold">It is your turn.</p>
      <p className="text-base tabular">
        Pick your seats within <strong>{formatClock(left)}</strong>, or your place goes to the end of the line.
      </p>
    </div>
  )
}
