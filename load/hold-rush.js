// 10,000 distinct buyers race for 100 seats; correctness is judged by verify.sql, not by these counters.
import http from 'k6/http';
import k6crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'https://localhost';
const SECRET = __ENV.SEATLOCK_AUTH_JWT_SECRET;
const SEATS = 100;

export const options = {
  insecureSkipTLSVerify: true, // Caddy's local CA cert is not trusted by k6
  scenarios: {
    rush: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 500),
      iterations: 10000,
      maxDuration: '10m',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    // never-failing thresholds exist only so k6 prints per-request-type percentiles
    'http_req_duration{name:hold}': ['p(99)<60000'],
    'http_req_duration{name:pay}': ['p(99)<60000'],
    'http_req_duration{name:pay_replay}': ['p(99)<60000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409));

const holdsCreated = new Counter('holds_created');
const holdsConflicted = new Counter('holds_conflicted');
const paysOk = new Counter('pays_ok');
const payReplaysMatched = new Counter('pay_replays_matched');
const unexpected = new Counter('unexpected_status'); // tagged by step and status, so a failure says what it was
let logged = 0;
function note(step, r) {
  unexpected.add(1, { step, status: String(r.status) });
  if (logged++ < 3) console.error(`${step} unexpected: ${r.status} ${r.error} ${String(r.body).slice(0, 120)}`);
}

const json = { 'Content-Type': 'application/json' };

function mintToken(sub) {
  const enc = (o) => encoding.b64encode(JSON.stringify(o), 'rawurl');
  const now = Math.floor(Date.now() / 1000);
  const head = enc({ alg: 'HS256', typ: 'JWT' });
  const body = enc({ iss: 'seatlock', sub, iat: now, exp: now + 3600 });
  const sig = k6crypto.hmac('sha256', SECRET, `${head}.${body}`, 'base64rawurl');
  return `${head}.${body}.${sig}`;
}

export function setup() {
  if (!SECRET) fail('set SEATLOCK_AUTH_JWT_SECRET');
  const user = `org_${Math.random().toString(36).slice(2, 12)}`;
  const creds = JSON.stringify({ username: user, password: 'load-test-pass' });
  let r = http.post(`${BASE}/auth/signup`, creds, { headers: json });
  if (r.status !== 201) fail(`signup ${r.status} ${r.body}`);
  r = http.post(`${BASE}/auth/login`, creds, { headers: json });
  if (r.status !== 200) fail(`login ${r.status} ${r.body}`);
  const auth = { ...json, Authorization: `Bearer ${r.json('accessToken')}` };
  r = http.post(`${BASE}/events`, JSON.stringify({
    name: 'Hold rush', startsAt: '2030-01-01T20:00:00Z', seatCount: SEATS, priceCents: 2500, currency: 'MYR',
  }), { headers: auth });
  if (r.status !== 201) fail(`create event ${r.status} ${r.body}`);
  const eventId = r.json('id');
  r = http.get(`${BASE}/events/${eventId}/seats`, { headers: auth });
  const seatIds = r.json().map((s) => s.id);
  if (seatIds.length !== SEATS) fail(`expected ${SEATS} seats, got ${seatIds.length}`);
  console.log(`EVENT_ID=${eventId}`);
  return { eventId, seatIds };
}

export default function (data) {
  const headers = { ...json, Authorization: `Bearer ${mintToken(crypto.randomUUID())}` };
  const seat = data.seatIds[Math.floor(Math.random() * data.seatIds.length)];
  const hold = http.post(`${BASE}/events/${data.eventId}/holds`, JSON.stringify({ seatIds: [seat] }),
    { headers, tags: { name: 'hold' } });
  if (hold.status === 409) {
    holdsConflicted.add(1);
    return;
  }
  if (!check(hold, { 'hold 201': (r) => r.status === 201 })) {
    note('hold', hold);
    return;
  }
  holdsCreated.add(1);

  const payHeaders = { ...headers, 'Idempotency-Key': crypto.randomUUID() };
  const url = `${BASE}/holds/${hold.json('id')}/pay`;
  const pay = http.post(url, null, { headers: payHeaders, tags: { name: 'pay' } });
  if (!check(pay, { 'pay accepted': (r) => r.status === 202 || r.status === 201 || r.status === 200 })) {
    note('pay', pay);
    return;
  }
  paysOk.add(1);

  const replay = http.post(url, null, { headers: payHeaders, tags: { name: 'pay_replay' } });
  if (check(replay, {
    'replay same payment id': (r) => (r.status === 200 || r.status === 202) && r.json('id') === pay.json('id'),
  })) payReplaysMatched.add(1);
  else note('replay', replay);
}
