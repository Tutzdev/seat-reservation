import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '@/lib/api'
import type { QueuePlace } from '@/lib/types'

export const queueKey = (eventId: string) => ['queue', eventId] as const

/**
 * Your place in an event's waiting room, or null when you have not joined. It is polled at a pace that follows
 * the wait: every five seconds while the turn is far, every second when it is close, not at all once you are in.
 */
export function useQueuePlace(eventId: string, enabled: boolean) {
  return useQuery({
    queryKey: queueKey(eventId),
    enabled,
    queryFn: async () => {
      try {
        return await api<QueuePlace>(`/api/v1/events/${eventId}/queue`)
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return null
        throw error
      }
    },
    refetchInterval: (query) => {
      const place = query.state.data
      if (!place) return false
      if (place.admitted) {
        // Wake up when the pass runs out, so the page offers to queue again.
        return Math.max(1_000, Date.parse(place.passExpiresAt) - Date.now() + 500)
      }
      return Math.min(5_000, Math.max(1_000, Date.parse(place.admittedAt) - Date.now()))
    },
  })
}

export function useJoinQueue(eventId: string) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: () => api<QueuePlace>(`/api/v1/events/${eventId}/queue`, { method: 'POST' }),
    onSuccess: (place) => queries.setQueryData(queueKey(eventId), place),
  })
}
