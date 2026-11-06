import { api, ApiError, newIdempotencyKey } from '@/lib/api'
import type { QueuePlace, Seat, TokenResponse } from '@/lib/types'

/**
 * A crowd of real buyers, run from the browser against the real API: each one signs up, logs in, joins the waiting
 * room, waits for its turn and tries to hold one or two random seats, picking again when someone was faster. Every
 * request goes through the same rate limits, waiting room and conditional UPDATE as a person's.
 */
export interface CrowdTally {
  started: number
  inLine: number
  won: number
  lost: number
  soldOut: number
  failed: number
}

export type CrowdEvent = { buyer: number; text: string; tone: 'won' | 'lost' | 'info' | 'failed' }

interface CrowdOptions {
  eventId: string
  buyers: number
  hasWaitingRoom: boolean
  onTally: (tally: CrowdTally) => void
  onEvent: (event: CrowdEvent) => void
  signal: AbortSignal
}

const PARALLEL = 24
const ATTEMPTS = 3
const PASSWORD = 'simulated buyer password'

export async function runCrowd(options: CrowdOptions): Promise<CrowdTally> {
  const tally: CrowdTally = { started: 0, inLine: 0, won: 0, lost: 0, soldOut: 0, failed: 0 }
  const runId = Date.now().toString(36)
  let next = 0

  const update = (change: (current: CrowdTally) => void) => {
    change(tally)
    options.onTally({ ...tally })
  }

  async function worker() {
    while (next < options.buyers && !options.signal.aborted) {
      const buyer = ++next
      update((t) => t.started++)
      try {
        await buy(buyer, runId, options, update)
      } catch (error) {
        if (options.signal.aborted) return
        update((t) => t.failed++)
        options.onEvent({ buyer, tone: 'failed', text: error instanceof Error ? error.message : 'failed' })
      }
    }
  }

  await Promise.all(Array.from({ length: Math.min(PARALLEL, options.buyers) }, worker))
  return tally
}

async function buy(buyer: number, runId: string, options: CrowdOptions, update: (change: (t: CrowdTally) => void) => void) {
  const { eventId, signal } = options
  const email = `crowd-${runId}-${buyer}@simulated.test`
  await api('/api/v1/auth/register', { body: { email, password: PASSWORD, name: `Buyer ${buyer}` }, token: null, signal })
  const { accessToken: token } = await api<TokenResponse>('/api/v1/auth/token', {
    body: { email, password: PASSWORD },
    token: null,
    signal,
  })

  if (options.hasWaitingRoom) {
    let place = await api<QueuePlace>(`/api/v1/events/${eventId}/queue`, { method: 'POST', token, signal })
    update((t) => t.inLine++)
    while (!place.admitted) {
      await sleep(Math.min(3_000, Math.max(500, Date.parse(place.admittedAt) - Date.now())), signal)
      place = await api<QueuePlace>(`/api/v1/events/${eventId}/queue`, { token, signal })
    }
    update((t) => t.inLine--)
  }

  for (let attempt = 0; attempt < ATTEMPTS; attempt++) {
    const seats = await api<Seat[]>(`/api/v1/events/${eventId}/seats`, { token, signal })
    const free = seats.filter((seat) => seat.status === 'AVAILABLE')
    if (free.length === 0) {
      update((t) => t.soldOut++)
      options.onEvent({ buyer, tone: 'info', text: 'found it sold out' })
      return
    }
    const wanted = pick(free, 1 + (buyer % 2))
    try {
      await api(`/api/v1/bookings`, {
        body: { eventId, seatIds: wanted.map((seat) => seat.id) },
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        token,
        signal,
      })
      update((t) => t.won++)
      options.onEvent({ buyer, tone: 'won', text: `held ${wanted.map((seat) => `${seat.row}${seat.number}`).join(' and ')}` })
      return
    } catch (error) {
      if (error instanceof ApiError && error.code === 'SEATS_UNAVAILABLE') {
        update((t) => t.lost++)
        options.onEvent({ buyer, tone: 'lost', text: `lost ${wanted.map((seat) => `${seat.row}${seat.number}`).join(' and ')} to someone faster` })
        continue
      }
      throw error
    }
  }
}

function pick<T>(items: T[], count: number): T[] {
  const copy = [...items]
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[copy[i], copy[j]] = [copy[j], copy[i]]
  }
  return copy.slice(0, Math.min(count, copy.length))
}

function sleep(milliseconds: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const timer = setTimeout(resolve, milliseconds)
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('Stopped', 'AbortError'))
    }, { once: true })
  })
}
