import { Link } from 'react-router'
import { Notice } from '@/components/ui/notice'
import { useMyBookings } from '@/features/bookings/api'
import { formatDateTime, formatMoney, plural } from '@/lib/format'
import type { BookingStatus } from '@/lib/types'

const statusText: Record<BookingStatus, { label: string; className: string }> = {
  HELD: { label: 'Held, waiting for payment', className: 'bg-highlight text-ink' },
  CONFIRMED: { label: 'Paid', className: 'bg-go text-white' },
  CANCELLED: { label: 'Released', className: 'bg-taken text-ink' },
  EXPIRED: { label: 'Expired', className: 'bg-taken text-ink' },
  REFUNDED: { label: 'Refunded', className: 'bg-taken text-ink' },
}

export function MyBookingsPage() {
  const bookings = useMyBookings()
  return (
    <div>
      <h1 className="text-6xl font-extrabold">My tickets</h1>
      <div className="mt-10">
        {bookings.isPending && <p className="text-ink-soft">Loading your bookings…</p>}
        {bookings.isError && <Notice tone="alert">{bookings.error.message}</Notice>}
        {bookings.data && bookings.data.content.length === 0 && (
          <Notice>
            No tickets yet.{' '}
            <Link to="/" className="font-semibold text-velvet underline underline-offset-2">Find an event</Link>
          </Notice>
        )}
        <ul className="flex flex-col gap-3">
          {bookings.data?.content.map((booking) => {
            const status = statusText[booking.status]
            return (
              <li key={booking.id}>
                <Link to={`/bookings/${booking.id}`} className="flex flex-wrap items-center justify-between gap-4
                  rounded-lg bg-paper-raised px-5 py-4 ring-1 ring-line hover:ring-ink-soft">
                  <div>
                    <p className="font-display text-2xl font-bold">
                      {booking.seats.map((seat) => `${seat.row}${seat.number}`).join(', ')}
                    </p>
                    <p className="text-sm text-ink-soft">
                      {plural(booking.seats.length, 'seat')} in {booking.seats[0]?.section}, booked{' '}
                      {formatDateTime(booking.createdAt)}
                    </p>
                  </div>
                  <div className="flex items-center gap-4">
                    <span className="font-display text-2xl font-bold tabular">{formatMoney(booking.totalCents)}</span>
                    <span className={`rounded-full px-3 py-1 text-sm font-semibold ${status.className}`}>
                      {status.label}
                    </span>
                  </div>
                </Link>
              </li>
            )
          })}
        </ul>
      </div>
    </div>
  )
}
