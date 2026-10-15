# Seat Reservation

Reserved-seating ticket API: hold seats for a few minutes while the customer checks out, without ever giving the same seat to two people.

[![CI](https://github.com/Tutzdev/seat-reservation/actions/workflows/ci.yml/badge.svg)](https://github.com/Tutzdev/seat-reservation/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-9a3412?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot 4](https://img.shields.io/badge/Spring_Boot-4-9a3412?style=flat-square&logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-9a3412?style=flat-square&logo=postgresql&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-migrations-9a3412?style=flat-square&logo=flyway&logoColor=white)
![Testcontainers](https://img.shields.io/badge/Testcontainers-JUnit_5-9a3412?style=flat-square&logo=junit5&logoColor=white)

> Built in phases. Phase 1 is the seat inventory: events, seat maps, holds and their expiry. Phase 2 (current) pays for a hold through [wallet-ledger](https://github.com/Tutzdev/wallet-ledger), another project of mine, and sells the seats. See the [roadmap](#roadmap).

## The problem

A ticket sale looks like `if (seat.isFree()) seat.sell()`. Then the sale opens:

- Two thousand people click the same seat in the same second. The check passes for many of them before anyone writes, and the seat is sold more than once.
- Someone holds four seats and closes the tab. The seats have to go back on sale by themselves, and quickly, because the show sells out in minutes.
- A buyer picks seats 12 and 13, but 13 was just taken. Holding only 12 is worse than holding nothing.
- The mobile app times out and retries. The customer now has two holds and blocks twice as many seats.
- Two requests for overlapping seats lock rows in opposite order and deadlock each other.
- The payment request times out. Did the money move? Retrying may charge twice; not retrying may lose a paid sale.
- The payment lands one second after the hold expired, and someone else just took one of the seats. The customer has paid for seats they cannot have.

## Backend highlights

- **A conditional UPDATE is the whole concurrency control.** `UPDATE seats SET status = 'HELD' ... WHERE id = ? AND status = 'AVAILABLE'` returns 1 for the winner and 0 for everyone else. A transaction waiting on the row lock re-checks the `WHERE` after the winner commits. No `SELECT ... FOR UPDATE`, no version column, no retry loop.
- **All or nothing.** Seats are claimed one by one inside a single transaction; if any is taken, the request answers `409 SEATS_UNAVAILABLE` with the taken ids and everything rolls back.
- **No deadlocks.** Seats are always claimed in ascending id order, so overlapping requests wait for each other instead of locking in a cycle. A test fires 60 overlapping requests in random order to prove it.
- **Abandoned holds free their seats the moment they expire.** The hold `UPDATE` also matches seats whose `held_until` has passed. A background sweeper marks old holds as `EXPIRED` in batches with `FOR UPDATE SKIP LOCKED`, but correctness never waits for it.
- **Idempotent holds.** `POST /bookings` requires an `Idempotency-Key`. A retry returns the original booking (`200` + `Idempotent-Replayed: true`); the same key with other seats is rejected. Concurrent retries meet on a unique constraint.
- **The database guards its own invariants.** A `CHECK` constraint ties `status`, `booking_id` and `held_until` together, so a seat can never be `HELD` without an owner and a deadline.
- **Paying never charges twice.** The ledger `Idempotency-Key` is derived from the booking id, so a client retry, a retry after a timeout and the background reconciler all land on the same transfer.
- **Record, call, record.** A `PENDING` payment is committed before the HTTP call and the outcome after it. No row lock is held while the ledger answers. If the process dies mid-call, the row says exactly which call to repeat.
- **A compensating refund instead of a distributed transaction.** When the money moved but the booking ended meanwhile (the deadline passed and a seat was resold, or the customer cancelled), the transaction that finds out marks the payment `REFUND_PENDING` and frees the seats; a reversal is then posted on the ledger.
- **A late payment still wins when it can.** Selling matches seats by booking id, not by deadline, so a payment that lands just after the deadline sells the seats if nobody took them. The expiry sweeper leaves holds with a charge in flight alone.
- **Nothing is left unsettled.** A reconciler repeats the idempotent ledger calls for payments nobody is waiting for anymore: a charge whose answer was lost, or a refund the ledger did not take.
- **Time is injected.** Every "now" comes from a `Clock`. Tests move a mutable clock past the deadline instead of sleeping.
- **Errors as Problem Details (RFC 9457)** with a stable `code` (`SEATS_UNAVAILABLE`, `EVENT_STARTED`, `IDEMPOTENCY_KEY_REUSED`...) and field-level validation errors.

## Business rules

| Rule | Where it is enforced | Error code |
| --- | --- | --- |
| A seat is held by at most one booking at a time | Conditional `UPDATE` in `SeatRepository.hold` | `SEATS_UNAVAILABLE` (409) |
| A booking gets every requested seat or none | One transaction in `SeatHolder`, rolled back on the first lost seat | `SEATS_UNAVAILABLE` (409) |
| A hold lasts 10 minutes; after that its seats are free for anyone | `held_until <= now` in the hold `UPDATE`, `Booking.statusAt` | n/a |
| At most 6 seats per booking, each at most once | `SeatHolder` | `TOO_MANY_SEATS`, `DUPLICATE_SEAT` (422) |
| Every seat must belong to the event | `SeatHolder` | `UNKNOWN_SEAT` (422) |
| No holds once the event has started | `Event.hasStarted` | `EVENT_STARTED` (422) |
| Cancelling twice is harmless; an expired hold cannot be cancelled | `Booking.cancel` | `BOOKING_NOT_ACTIVE` (409) |
| An Idempotency-Key is bound to one request | `BookingService` + `UNIQUE (idempotency_key)` | `IDEMPOTENCY_KEY_REUSED` (422) |
| A seat map has unique section names and unique rows per section, up to 20,000 seats | `SeatLayout` | `DUPLICATE_SECTION`, `DUPLICATE_ROW`, `TOO_MANY_SEATS` (422) |
| Events start in the future | `Event.schedule` | `EVENT_IN_THE_PAST` (422) |
| Only a live hold can be paid | `PaymentRecorder` | `BOOKING_NOT_ACTIVE` (409) |
| A booking is charged at most once, however many times payment is retried | Ledger `Idempotency-Key` derived from the booking id | n/a |
| A pending payment cannot switch to another account | `PaymentRecorder` | `PAYMENT_IN_PROGRESS` (409) |
| A declined payment keeps the hold and can be retried, also from another account | `Payment.retry` | `PAYMENT_DECLINED` (422) |
| A charge that lands after the booking ended is refunded and its seats go back on sale | `PaymentRecorder.capture` | `PAYMENT_REFUNDED` (409) |
| A sold seat is never freed by a deadline, the sweeper or a new hold | `SOLD` status in the conditional `UPDATE`s | `SEATS_UNAVAILABLE` (409) |
| A confirmed booking cannot be cancelled | `Booking.cancel` | `BOOKING_NOT_ACTIVE` (409) |
| `HELD` ⇔ booking and deadline; `SOLD` ⇔ booking and no deadline | `CHECK` constraint (`V5` migration) | n/a |
| A payment's status matches the transfers it records | `CHECK` constraint (`V6` migration) | n/a |

## How a hold works

```mermaid
sequenceDiagram
    participant C as Client
    participant S as BookingService
    participant H as SeatHolder
    participant DB as PostgreSQL

    C->>S: POST /bookings (Idempotency-Key: k)
    S->>DB: booking with key k already exists?
    alt retry
        DB-->>S: existing booking
        S-->>C: 200 + Idempotent-Replayed
    else first request
        S->>H: hold(command)
        H->>DB: INSERT booking (key k, unique)
        loop each seat, ascending id
            H->>DB: UPDATE seats ... WHERE id = ? AND (AVAILABLE OR held_until <= now)
            DB-->>H: 1 row = mine, 0 rows = taken
        end
        alt every seat is mine
            H-->>S: COMMIT
            S-->>C: 201 + Location
        else some seat was taken
            H-->>S: ROLLBACK (seats and booking)
            S-->>C: 409 SEATS_UNAVAILABLE + unavailableSeatIds
        end
    end
```

The booking row is inserted **before** any seat is touched. Two concurrent retries with the same key therefore collide on the unique index and never compete for the seats; the loser rolls back and returns the winner's booking.

## How a payment works

```mermaid
sequenceDiagram
    participant C as Client
    participant P as PaymentService
    participant R as PaymentRecorder
    participant L as wallet-ledger
    participant DB as PostgreSQL

    C->>P: POST /bookings/{id}/payment
    P->>R: start
    R->>DB: lock booking, INSERT payment PENDING, COMMIT
    P->>L: POST /transfers (Idempotency-Key: booking-{id})
    alt declined
        L-->>P: 422 INSUFFICIENT_FUNDS
        P->>R: payment DECLINED, hold kept
        P-->>C: 422 PAYMENT_DECLINED
    else timeout or 5xx
        P-->>C: 503 PAYMENT_UNAVAILABLE, payment stays PENDING
        Note over C,L: a retry or the reconciler repeats the same idempotent call
    else transfer posted
        L-->>P: 201 + transfer id
        P->>R: capture
        R->>DB: lock booking, UPDATE seats SET SOLD WHERE booking_id = ?
        alt every seat sold
            R->>DB: booking CONFIRMED, payment CAPTURED, COMMIT
            P-->>C: 200 CONFIRMED
        else a seat was resold after the deadline, or the booking was cancelled
            R->>DB: free the remaining seats, payment REFUND_PENDING, COMMIT
            P->>L: POST /transfers/{id}/reversal (Idempotency-Key: refund-{id})
            P->>R: payment REFUNDED
            P-->>C: 409 PAYMENT_REFUNDED
        end
    end
```

No transaction spans this service and the ledger. Each step commits on its own and every step after the first can be repeated safely, so the worst a crash can do is leave a payment `PENDING` or `REFUND_PENDING` for the reconciler to finish.

```
src/main/java/dev/tutz/reservation
├── event      Event, Seat, SeatLayout (seat map rules), seat map and availability queries
├── booking    Booking aggregate, SeatHolder (write path), BookingService (idempotency), HoldExpirySweeper,
│              Payment, PaymentService + PaymentRecorder (pay, capture, refund), PaymentReconciler
├── payment    PaymentGateway port and the wallet-ledger HTTP adapter (token cache, error mapping)
└── common     Problem Details handler, Clock, paging, OpenAPI
```

## API

OpenAPI UI: `/swagger-ui.html`.

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/v1/events` | Create an event and generate its seat map from sections |
| `GET` | `/api/v1/events` | Upcoming events (paginated) |
| `GET` | `/api/v1/events/{id}` | Event with seats and available seats per section |
| `GET` | `/api/v1/events/{id}/seats` | Seat map with each seat's status, optionally `?section=` |
| `POST` | `/api/v1/bookings` | Hold seats (requires `Idempotency-Key`) |
| `GET` | `/api/v1/bookings/{id}` | One booking with its seats and price |
| `POST` | `/api/v1/bookings/{id}/cancel` | Cancel a hold and release its seats |
| `POST` | `/api/v1/bookings/{id}/payment` | Pay for a hold through wallet-ledger and confirm it (safe to retry, no header needed) |

```bash
curl -X POST localhost:8080/api/v1/events -H "Content-Type: application/json" -d '{
  "name": "Rock in Rio", "venue": "Parque Olímpico", "startsAt": "2027-09-12T20:00:00Z",
  "sections": [
    {"name": "Floor", "rows": ["A", "B", "C"], "seatsPerRow": 20, "priceCents": 45000},
    {"name": "Upper", "rows": ["D", "E"], "seatsPerRow": 30, "priceCents": 20000}
  ]}'

curl -X POST localhost:8080/api/v1/bookings \
  -H "Idempotency-Key: checkout-7f3a9c21" \
  -H "Content-Type: application/json" \
  -d '{"eventId": "…", "seatIds": [101, 102]}'

curl -X POST localhost:8080/api/v1/bookings/{id}/payment \
  -H "Content-Type: application/json" \
  -d '{"customerAccountId": "…"}'
```

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Some of the requested seats are no longer available.",
  "instance": "/api/v1/bookings",
  "code": "SEATS_UNAVAILABLE",
  "unavailableSeatIds": [102],
  "timestamp": "2026-10-14T03:21:07.512Z"
}
```

## Technical decisions

- **Conditional UPDATE instead of `SELECT ... FOR UPDATE` or `@Version`.** With a pessimistic read lock, the check and the write are two statements and the lock is easy to forget on a new code path. With optimistic versioning, 1,999 of 2,000 buyers get an exception and the client has to retry for nothing. The conditional UPDATE does the check and the write atomically, and the row count is the answer.
- **The seat row is the single source of truth for who holds it now.** `bookings` records what was asked for, `booking_seats` keeps an immutable copy of each line and price, and `seats.booking_id` says who owns the seat at this moment. When an expired hold is taken over, the old booking keeps its history and the seat simply points elsewhere.
- **Lazy expiry plus a sweeper, not a sweeper alone.** If only a background job released seats, every expired seat would stay blocked until the next run, which is exactly when demand peaks. Treating `held_until <= now` as free makes expiry exact; the sweeper only keeps the stored status honest for reports.
- **Failed holds are not remembered by the idempotency key.** A hold that lost a seat rolls back, key included, so the client can retry the same request once the seat frees up. Only successful holds are replayed.
- **Seats reference events by id, not through a JPA association.** An event can have 20,000 seats and nothing should ever load them through the event. They are inserted in JDBC batches of 100 with a pooled sequence, so creating an event is a few round trips instead of one per seat.
- **No lock is held across the ledger call.** The booking row is locked only in the short transactions before and after it. A slow ledger delays one payment; it does not freeze the booking for cancels, the sweeper or other payments.
- **The booking id as the payment's idempotency key, instead of a client header.** A booking is paid once, so the key is natural and the client cannot get it wrong. It is also what lets the reconciler repeat a call nobody is waiting for.
- **Synchronous HTTP to the ledger, for now.** The customer is waiting for a yes or no, and the ledger is idempotent, so a direct call with timeouts plus a reconciler is enough. Consuming the ledger's events from Kafka is on the roadmap.
- **A port for the payment gateway.** It has one real adapter, but the tests need a fake that can decline, time out after charging and run code mid-call, which a mocked HTTP server makes awkward. The adapter itself is tested against a mocked server.
- **Amounts are `long` cents.** No floating point anywhere near money.

## Tests

```bash
./mvnw verify   # needs Docker for Testcontainers
```

- `BookingConcurrencyTests`: 100 virtual threads released by the same latch race for one seat, and exactly one wins. Sixty requests for random overlapping sets of three seats finish without a deadlock, and no seat ends up in two bookings.
- `IdempotentHoldTests`: a retry returns the original booking, a reused key with other seats is rejected, 20 concurrent retries create one booking, and a hold that lost its seats can be retried with the same key.
- `HoldExpiryTests`: an expired hold frees its seats before the sweeper runs, the sweeper never touches a seat another booking took over, and active holds are left alone.
- `PaymentServiceTests`: paying sells the seats, a second call does not charge again, a decline keeps the hold, a timeout after the charge is settled by a retry without a second charge, free bookings skip the ledger.
- `LatePaymentTests`: the deadline passes while the charge is in flight. Seats nobody took are still sold; a resold seat means a refund and the other seats back on sale; a cancel mid-payment means a refund.
- `PaymentConcurrencyTests`: 20 simultaneous payments for one booking charge once. A payment racing a cancel, and a late payment racing another customer for the same seat, always end with one owner and no money kept for nothing.
- `PaymentReconcilerTests`: lost charges are confirmed or refunded, recent ones are left to the request still waiting, failed refunds are pushed again, and the sweeper leaves holds with a charge in flight alone.
- `LedgerPaymentGatewayTests`: the HTTP conversation with the ledger against a mocked server, covering token reuse and renewal, decline codes, and timeouts and 5xx as unknown outcomes.
- `BookingApiTests` / `PaymentApiTests` / `EventApiTests`: the HTTP contract, meaning status codes, `Location` and `Idempotent-Replayed` headers, and Problem Details bodies.
- `BookingServiceTests`, `EventServiceTests`, `SeatLayoutTests`: all-or-nothing holds, cancel, limits and seat map rules.

The integration tests run against PostgreSQL 17 in Testcontainers, because row locks, `SKIP LOCKED` and the `CHECK` constraints cannot be faked by an in-memory database. CI runs everything on every push.

## Running locally

```bash
docker compose up --build    # PostgreSQL + API on :8080
```

Without Docker for the app, start only the database and run the API from Maven:

```bash
docker compose up -d postgres
./mvnw spring-boot:run
```

Payments need a running [wallet-ledger](https://github.com/Tutzdev/wallet-ledger). Start it on port 8081 (`PORT=8081 ./mvnw spring-boot:run` in that project), open a BRL account for the venue, and copy `.env.example` to `.env` with the ledger operator and that account id. The application refuses to start without them.

Hold duration, seat limits, the sweeper and the reconciler are configured under `reservation.*` in `application.yml`.

## Roadmap

- ~~**Phase 2: payment.**~~ Done: pay through [wallet-ledger](https://github.com/Tutzdev/wallet-ledger) with the booking id as the `Idempotency-Key`, a compensating refund when the payment lands after the booking ended, and a reconciler for unknown outcomes.
- **Phase 2.5: ledger events.** Consume the ledger's transfer events from Kafka with an inbox table that drops duplicates, so a payment the ledger posted is settled here even before a retry or the reconciler gets to it.
- **Phase 3: customers.** JWT authentication, idempotency keys scoped per customer, one active hold per customer per event, refunds of confirmed bookings on request.
- **Phase 4: sale opening.** A virtual waiting room in Redis and rate limiting per customer.
- **Phase 5: proof under load.** A k6 scenario with thousands of buyers on one section, Micrometer metrics for won and lost holds, and OpenTelemetry tracing across this service and the ledger.
