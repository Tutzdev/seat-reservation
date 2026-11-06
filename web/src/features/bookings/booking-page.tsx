import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Link, useParams } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Notice } from '@/components/ui/notice'
import { useBooking, useCancel, usePay, useRefund } from '@/features/bookings/api'
import { useEvent } from '@/features/events/api'
import { ApiError } from '@/lib/api'
import { formatClock, formatDateTime, formatMoney } from '@/lib/format'
import type { Booking, EventDetail } from '@/lib/types'
import { useNow } from '@/lib/use-now'

export function BookingPage() {
  const { bookingId = '' } = useParams()
  const booking = useBooking(bookingId)
  const event = useEvent(booking.data?.eventId ?? '')

  if (booking.isPending) return <p className="text-ink-soft">Loading your booking…</p>
  if (booking.isError) return <Notice tone="alert">{booking.error.message}</Notice>

  const data = booking.data
  return (
    <div className="mx-auto max-w-3xl">
      <Link to="/bookings" className="text-sm font-semibold text-ink-soft hover:text-ink">My tickets</Link>
      <h1 className="mt-2 text-5xl font-extrabold sm:text-6xl">{event.data?.name ?? 'Your booking'}</h1>
      {event.data && (
        <p className="mt-2 text-lg text-ink-soft">{event.data.venue}, {formatDateTime(event.data.startsAt)}</p>
      )}
      <div className="mt-10">
        {data.status === 'HELD' && <HeldBooking booking={data} />}
        {data.status === 'CONFIRMED' && <ConfirmedBooking booking={data} event={event.data} />}
        {data.status !== 'HELD' && data.status !== 'CONFIRMED' && <EndedBooking booking={data} />}
      </div>
    </div>
  )
}

function SeatList({ booking }: { booking: Booking }) {
  return (
    <ul className="divide-y divide-line">
      {booking.seats.map((seat) => (
        <li key={seat.seatId} className="flex items-baseline justify-between gap-4 py-3 tabular">
          <span className="text-lg">
            {seat.section}, row <strong>{seat.row}</strong>, seat <strong>{seat.number}</strong>
          </span>
          <span className="text-ink-soft">{formatMoney(seat.priceCents)}</span>
        </li>
      ))}
      <li className="flex justify-between pt-4 font-display text-3xl font-bold tabular">
        <span>Total</span>
        <span>{formatMoney(booking.totalCents)}</span>
      </li>
    </ul>
  )
}

const paySchema = z.object({
  customerAccountId: z.uuid('Paste the id of your wallet account, like 3f6c2b1e-….'),
})

function HeldBooking({ booking }: { booking: Booking }) {
  const now = useNow()
  const pay = usePay()
  const cancel = useCancel()
  const left = Date.parse(booking.expiresAt) - now
  const pending = booking.payment?.status === 'PENDING'
  const form = useForm<z.infer<typeof paySchema>>({
    resolver: zodResolver(paySchema),
    defaultValues: {
      customerAccountId: booking.payment?.customerAccountId
        ?? (import.meta.env.VITE_DEMO_WALLET_ACCOUNT_ID as string | undefined) ?? '',
    },
  })

  const submit = form.handleSubmit(({ customerAccountId }) => pay.mutate({ id: booking.id, customerAccountId }))

  return (
    <div className="grid grid-cols-1 gap-10 md:grid-cols-[minmax(0,1fr)_17rem]">
      <div>
        <h2 className="text-3xl font-bold">Your seats are held</h2>
        <div className="mt-4"><SeatList booking={booking} /></div>

        <form onSubmit={submit} noValidate className="mt-10 flex flex-col gap-4">
          <h2 className="text-3xl font-bold">Pay</h2>
          {pay.isError && <Notice tone="alert">{payError(pay.error)}</Notice>}
          {pending && !pay.isPending && (
            <Notice>The last payment attempt has no answer yet. Paying again is safe: it cannot charge twice.</Notice>
          )}
          <Field
            label="Wallet account"
            hint="The wallet-ledger account the tickets are charged to."
            autoComplete="off"
            spellCheck={false}
            {...form.register('customerAccountId')}
            error={form.formState.errors.customerAccountId?.message}
          />
          <div className="flex flex-wrap gap-3">
            <Button type="submit" busy={pay.isPending} disabled={left <= 0}>
              Pay {formatMoney(booking.totalCents)}
            </Button>
            <Button type="button" variant="quiet" busy={cancel.isPending} disabled={pay.isPending || pending}
              onClick={() => cancel.mutate(booking.id)}>
              Release these seats
            </Button>
          </div>
          {cancel.isError && <Notice tone="alert">{cancel.error.message}</Notice>}
        </form>
      </div>

      <aside aria-label="Time left" className="self-start rounded-xl bg-ink p-6 text-paper md:sticky md:top-6">
        <p className="text-base">Held for you for</p>
        <p className="mt-2 font-display text-6xl font-black leading-none tabular" role="timer" aria-live="off">
          {formatClock(left)}
        </p>
        <p className="mt-4 text-sm text-paper/75">
          When the clock runs out, the seats go back on sale. A payment already on its way still counts.
        </p>
      </aside>
    </div>
  )
}

function payError(error: Error): string {
  if (!(error instanceof ApiError)) return 'The server could not be reached. Paying again is safe.'
  switch (error.code) {
    case 'PAYMENT_DECLINED':
      return `The wallet declined the payment (${String(error.body.reason ?? 'no reason given')}). Your seats are still held; try another account.`
    case 'PAYMENT_UNAVAILABLE':
      return 'The wallet did not confirm the payment yet. Pay again in a few seconds: it will not charge twice.'
    case 'PAYMENT_REFUNDED':
      return 'The payment arrived after the seats were released, so it was refunded to your wallet.'
    default:
      return error.message
  }
}

function ConfirmedBooking({ booking, event }: { booking: Booking; event?: EventDetail }) {
  const refund = useRefund()
  return (
    <div className="flex flex-col gap-10">
      <section aria-labelledby="ticket-title" className="grid overflow-hidden rounded-xl bg-paper-raised ring-1
        ring-line sm:grid-cols-[1fr_12rem]">
        <div className="p-6 sm:p-8">
          <h2 id="ticket-title" className="text-3xl font-bold text-go">Paid. These seats are yours.</h2>
          <div className="mt-4"><SeatList booking={booking} /></div>
        </div>
        <div className="perforated-top sm:perforated-left flex flex-col justify-between border-t-2 border-dashed border-line bg-ink p-6
          text-paper sm:border-l-2 sm:border-t-0">
          <p className="text-sm">Admit {booking.seats.length}</p>
          <p className="font-display text-4xl font-black leading-none">
            {booking.seats.map((seat) => `${seat.row}${seat.number}`).join(' ')}
          </p>
          <p className="text-xs text-paper/70">{event ? formatDateTime(event.startsAt) : ''}</p>
        </div>
      </section>

      <section aria-labelledby="refund-title" className="max-w-xl">
        <h2 id="refund-title" className="text-2xl font-bold">Can no longer go?</h2>
        <p className="mt-2 text-base text-ink-soft">
          Give the seats back up to 24 hours before the event. They go on sale again and the money returns to the
          wallet that paid.
        </p>
        {refund.isError && <div className="mt-3"><Notice tone="alert">{refund.error.message}</Notice></div>}
        <Button className="mt-4" variant="danger" busy={refund.isPending} onClick={() => refund.mutate(booking.id)}>
          Refund these tickets
        </Button>
      </section>
    </div>
  )
}

function EndedBooking({ booking }: { booking: Booking }) {
  const text = {
    CANCELLED: 'You released these seats. They are back on sale.',
    EXPIRED: 'The 10 minutes ran out before a payment arrived, so these seats went back on sale.',
    REFUNDED: 'Refunded. The seats are back on sale and the money is on its way back to your wallet.',
  }[booking.status as 'CANCELLED' | 'EXPIRED' | 'REFUNDED']
  return (
    <div className="flex flex-col gap-6">
      <Notice>{text}</Notice>
      <SeatList booking={booking} />
      <Link to={`/events/${booking.eventId}`} className="font-semibold text-velvet underline underline-offset-2">
        Back to the seat map
      </Link>
    </div>
  )
}
