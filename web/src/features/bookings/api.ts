import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { Booking, Page } from '@/lib/types'
import { eventKeys } from '@/features/events/api'

export const bookingKeys = {
  mine: ['bookings'] as const,
  one: (id: string) => ['bookings', id] as const,
}

export function useBooking(id: string) {
  return useQuery({
    queryKey: bookingKeys.one(id),
    queryFn: () => api<Booking>(`/api/v1/bookings/${id}`),
    // A hold can expire, and a payment can be settled by the ledger's events while the page is open.
    refetchInterval: (query) => (query.state.data?.status === 'HELD' ? 5_000 : false),
  })
}

export function useMyBookings() {
  return useQuery({
    queryKey: bookingKeys.mine,
    queryFn: () => api<Page<Booking>>('/api/v1/bookings?size=50'),
  })
}

/** The Idempotency-Key belongs to the attempt: retrying the same attempt cannot create a second booking. */
export function useHold() {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: ({ eventId, seatIds, key }: { eventId: string; seatIds: number[]; key: string }) =>
      api<Booking>('/api/v1/bookings', { body: { eventId, seatIds }, headers: { 'Idempotency-Key': key } }),
    onSuccess: (booking) => {
      queries.setQueryData(bookingKeys.one(booking.id), booking)
      queries.invalidateQueries({ queryKey: bookingKeys.mine })
    },
    onSettled: (_booking, _error, { eventId }) => queries.invalidateQueries({ queryKey: eventKeys.seats(eventId) }),
  })
}

function useBookingAction(path: (id: string) => string) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<Booking>(path(id), { method: 'POST' }),
    onSuccess: (booking) => {
      queries.setQueryData(bookingKeys.one(booking.id), booking)
      queries.invalidateQueries({ queryKey: bookingKeys.mine })
      queries.invalidateQueries({ queryKey: eventKeys.seats(booking.eventId) })
    },
  })
}

export function useCancel() {
  return useBookingAction((id) => `/api/v1/bookings/${id}/cancel`)
}

export function useRefund() {
  return useBookingAction((id) => `/api/v1/bookings/${id}/refund`)
}

/** Safe to retry: the booking id is the ledger's Idempotency-Key, so a second click never charges twice. */
export function usePay() {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: ({ id, customerAccountId }: { id: string; customerAccountId: string }) =>
      api<Booking>(`/api/v1/bookings/${id}/payment`, { body: { customerAccountId } }),
    onSuccess: (booking) => {
      queries.setQueryData(bookingKeys.one(booking.id), booking)
      queries.invalidateQueries({ queryKey: bookingKeys.mine })
    },
    onSettled: (_booking, _error, { id }) => queries.invalidateQueries({ queryKey: bookingKeys.one(id) }),
  })
}
