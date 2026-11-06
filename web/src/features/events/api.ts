import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { EventDetail, EventSummary, Page, Seat } from '@/lib/types'

export const eventKeys = {
  all: ['events'] as const,
  one: (id: string) => ['events', id] as const,
  seats: (id: string) => ['events', id, 'seats'] as const,
}

export function useEvents() {
  return useQuery({
    queryKey: eventKeys.all,
    queryFn: () => api<Page<EventSummary>>('/api/v1/events?size=50'),
  })
}

export function useEvent(id: string) {
  return useQuery({
    queryKey: eventKeys.one(id),
    queryFn: () => api<EventDetail>(`/api/v1/events/${id}`),
    refetchInterval: 10_000,
  })
}

/** The seat map refreshes every two seconds while it is open, so seats taken by others disappear on their own. */
export function useSeats(id: string, live: boolean) {
  return useQuery({
    queryKey: eventKeys.seats(id),
    queryFn: () => api<Seat[]>(`/api/v1/events/${id}/seats`),
    enabled: live,
    refetchInterval: live ? 2_000 : false,
  })
}

export type SaleState = 'upcoming' | 'queue' | 'open' | 'started'

export function saleState(event: EventSummary, now: number): SaleState {
  if (Date.parse(event.startsAt) <= now) return 'started'
  if (Date.parse(event.salesOpenAt) > now) return 'upcoming'
  return event.admitsPerMinute ? 'queue' : 'open'
}
