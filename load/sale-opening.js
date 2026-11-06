// A sale opening, end to end: thousands of buyers sign up, wait for the sale to open, go through the waiting
// room and race for the seats of one section. The run fails if any seat is held twice, if a buyer gets an answer
// the API does not document, or if holds get slow.
//
//   docker compose --profile load run --rm k6                  (against the Compose stack)
//   k6 run -e BASE_URL=http://localhost:8080 load/sale-opening.js
//
// Settings: BUYERS (2000), ROWS of 20 seats (20, so 400 seats), ADMITS_PER_MINUTE (3000),
// OPENS_IN_SECONDS (90, the time buyers have to sign up), ORGANIZER_EMAIL and ORGANIZER_PASSWORD.
import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BUYERS = Number(__ENV.BUYERS || 2000);
const ROWS = Number(__ENV.ROWS || 20);
const SEATS_PER_ROW = 20;
const SEATS = ROWS * SEATS_PER_ROW;
const ADMITS_PER_MINUTE = Number(__ENV.ADMITS_PER_MINUTE || 3000);
const OPENS_IN_SECONDS = Number(__ENV.OPENS_IN_SECONDS || 90);
const ATTEMPTS = 3;

const holdsWon = new Counter('holds_won');
const holdsLost = new Counter('holds_lost');
const seatsHeld = new Counter('seats_held');
const soldOut = new Counter('buyers_sold_out');
const unexpected = new Counter('unexpected_answers');
const queueWait = new Trend('queue_wait', true);
const seatsTakenInMap = new Gauge('seats_taken_in_map');

// 409 is the expected answer of a buyer who lost a seat to someone faster; it is not a failed request.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

export const options = {
    setupTimeout: '1m',
    scenarios: {
        sale: { executor: 'per-vu-iterations', vus: BUYERS, iterations: 1, maxDuration: '20m' },
    },
    thresholds: {
        // The point of the whole service: never more seats handed out than exist.
        seats_held: [`count<=${SEATS}`],
        unexpected_answers: ['count==0'],
        http_req_failed: ['rate<0.01'],
        'http_req_duration{name:hold}': ['p(95)<500', 'p(99)<1000'],
    },
};

export function setup() {
    const login = post('/api/v1/auth/token', {
        email: __ENV.ORGANIZER_EMAIL || 'organizer@tickets.dev',
        password: __ENV.ORGANIZER_PASSWORD || 'change-me-please',
    });
    if (login.status !== 200) {
        fail(`organizer login answered ${login.status}: set ORGANIZER_EMAIL and ORGANIZER_PASSWORD`);
    }
    const opensAt = Date.now() + OPENS_IN_SECONDS * 1000;
    const rows = Array.from({ length: ROWS }, (_, i) => `R${i + 1}`);
    const event = post('/api/v1/events', {
        name: `Sale opening ${new Date().toISOString()}`,
        venue: 'k6 Arena',
        startsAt: new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString(),
        salesOpenAt: new Date(opensAt).toISOString(),
        admitsPerMinute: ADMITS_PER_MINUTE,
        sections: [{ name: 'Floor', rows, seatsPerRow: SEATS_PER_ROW, priceCents: 25000 }],
    }, login.json('accessToken'));
    if (event.status !== 201) {
        fail(`creating the event answered ${event.status}: ${event.body}`);
    }
    return { eventId: event.json('id'), opensAt, runId: Date.now().toString(36) };
}

export default function (data) {
    const token = signUp(data);
    if (!token) {
        return;
    }

    // Everyone waits for the opening, like a crowd refreshing the page at 9:59.
    const untilOpen = (data.opensAt - Date.now()) / 1000;
    if (untilOpen > 0) {
        sleep(untilOpen + Math.random() * 0.5);
    }

    const joinedAt = Date.now();
    let place = expect(post(`/api/v1/events/${data.eventId}/queue`, null, token, 'join queue'), 200);
    while (place && !place.json('admitted')) {
        const wait = (Date.parse(place.json('admittedAt')) - Date.now()) / 1000;
        sleep(Math.min(5, Math.max(1, wait)));
        place = expect(get(`/api/v1/events/${data.eventId}/queue`, token, 'queue status'), 200);
    }
    if (!place) {
        return;
    }
    queueWait.add(Date.now() - joinedAt);

    for (let attempt = 0; attempt < ATTEMPTS; attempt++) {
        const seatMap = expect(get(`/api/v1/events/${data.eventId}/seats?section=Floor`, null, 'seat map'), 200);
        if (!seatMap) {
            return;
        }
        const available = seatMap.json().filter((seat) => seat.status === 'AVAILABLE').map((seat) => seat.id);
        if (available.length === 0) {
            soldOut.add(1);
            return;
        }
        // One or two seats each, picked at random: buyers collide on the same seats, as they do in a real sale.
        const wanted = pick(available, 1 + (exec.vu.idInTest % 2));
        const hold = post('/api/v1/bookings', { eventId: data.eventId, seatIds: wanted }, token, 'hold',
            { 'Idempotency-Key': `${data.runId}-${exec.vu.idInTest}-${attempt}` });
        if (hold.status === 201) {
            holdsWon.add(1);
            seatsHeld.add(wanted.length);
            return;
        }
        if (hold.status === 409 && hold.json('code') === 'SEATS_UNAVAILABLE') {
            holdsLost.add(1);
            continue;
        }
        unexpectedAnswer(hold);
        return;
    }
}

/** Counts the seats that ended up held, straight from the seat map, to compare with what buyers were told. */
export function teardown(data) {
    const seatMap = get(`/api/v1/events/${data.eventId}/seats`, null, 'seat map');
    const taken = seatMap.json().filter((seat) => seat.status !== 'AVAILABLE').length;
    seatsTakenInMap.add(taken);
    check(taken, { 'no more seats taken than exist': (count) => count <= SEATS });
}

export function handleSummary(data) {
    const metric = (name, field) => (data.metrics[name] ? data.metrics[name].values[field] : 0);
    const held = metric('seats_held', 'count');
    const taken = metric('seats_taken_in_map', 'value');
    const verdict = held === taken
        ? `OK: buyers were told they hold ${held} seats and the seat map shows ${taken} taken. No seat was sold twice.`
        : `MISMATCH: buyers were told they hold ${held} seats but the seat map shows ${taken} taken.`;
    const report = [
        '',
        `Sale opening: ${BUYERS} buyers, ${SEATS} seats, ${ADMITS_PER_MINUTE} admitted per minute`,
        `  holds won ${metric('holds_won', 'count')}, lost to a faster buyer ${metric('holds_lost', 'count')}, ` +
            `sold out before their turn ${metric('buyers_sold_out', 'count')}`,
        `  ${verdict}`,
        '',
    ].join('\n');
    return { stdout: textSummary(data, { indent: ' ', enableColors: true }) + report };
}

function signUp(data) {
    const email = `buyer-${data.runId}-${exec.vu.idInTest}@load.test`;
    const password = 'load test password';
    if (!expect(post('/api/v1/auth/register', { email, password, name: `Buyer ${exec.vu.idInTest}` }, null, 'register'), 201)) {
        return null;
    }
    const login = expect(post('/api/v1/auth/token', { email, password }, null, 'login'), 200);
    return login ? login.json('accessToken') : null;
}

function expect(response, status) {
    if (response.status === status) {
        return response;
    }
    unexpectedAnswer(response);
    return null;
}

function unexpectedAnswer(response) {
    unexpected.add(1, { status: String(response.status) });
    console.warn(`${response.request.method} ${response.url} answered ${response.status}: ${response.body}`);
}

function pick(ids, count) {
    const chosen = new Set();
    while (chosen.size < Math.min(count, ids.length)) {
        chosen.add(ids[Math.floor(Math.random() * ids.length)]);
    }
    return [...chosen];
}

function post(path, body, token, name, headers = {}) {
    return http.post(`${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), params(token, name, headers));
}

function get(path, token, name) {
    return http.get(`${BASE_URL}${path}`, params(token, name, {}));
}

function params(token, name, headers) {
    const all = { 'Content-Type': 'application/json', ...headers };
    if (token) {
        all.Authorization = `Bearer ${token}`;
    }
    return { headers: all, tags: name ? { name } : {} };
}
