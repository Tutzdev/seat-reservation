// The API's shapes, as the backend's records serialize them.

export type Role = 'CUSTOMER' | 'ORGANIZER'

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface EventSummary {
  id: string
  name: string
  venue: string
  startsAt: string
  salesOpenAt: string
  admitsPerMinute: number | null
}

export interface Section {
  name: string
  seats: number
  available: number
  priceCents: number
}

export interface EventDetail extends EventSummary {
  totalSeats: number
  availableSeats: number
  sections: Section[]
}

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'SOLD'

export interface Seat {
  id: number
  section: string
  row: string
  number: number
  priceCents: number
  status: SeatStatus
}

export interface QueuePlace {
  eventId: string
  admitted: boolean
  position: number
  admittedAt: string
  passExpiresAt: string
}

export type BookingStatus = 'HELD' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED' | 'REFUNDED'
export type PaymentStatus = 'PENDING' | 'CAPTURED' | 'DECLINED' | 'REFUND_PENDING' | 'REFUNDED'

export interface SeatLine {
  seatId: number
  section: string
  row: string
  number: number
  priceCents: number
}

export interface Booking {
  id: string
  eventId: string
  customerId: string
  status: BookingStatus
  totalCents: number
  expiresAt: string
  createdAt: string
  seats: SeatLine[]
  payment: {
    status: PaymentStatus
    customerAccountId: string
    transferId: string | null
    refundTransferId: string | null
    declineReason: string | null
  } | null
}

export interface Customer {
  id: string
  email: string
  name: string
  role: Role
  createdAt: string
}

export interface TokenResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
}

export interface SaleDashboard {
  eventId: string
  salesOpenAt: string
  seats: { total: number; available: number; held: number; sold: number }
  holds: { won: number; lost: number }
  waitingRoom: { admitsPerMinute: number; waiting: number; clearsAt: string | null } | null
}
