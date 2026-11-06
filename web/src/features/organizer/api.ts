import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { EventDetail, SaleDashboard } from '@/lib/types'
import { eventKeys } from '@/features/events/api'

export interface NewEvent {
  name: string
  venue: string
  startsAt: string
  salesOpenAt?: string
  admitsPerMinute?: number
  sections: { name: string; rows: string[]; seatsPerRow: number; priceCents: number }[]
}

export function useCreateEvent() {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: (event: NewEvent) => api<EventDetail>('/api/v1/events', { body: event }),
    onSuccess: () => queries.invalidateQueries({ queryKey: eventKeys.all }),
  })
}

export function useSaleDashboard(eventId: string) {
  return useQuery({
    queryKey: ['dashboard', eventId],
    queryFn: () => api<SaleDashboard>(`/api/v1/events/${eventId}/dashboard`),
    refetchInterval: 2_000,
  })
}
