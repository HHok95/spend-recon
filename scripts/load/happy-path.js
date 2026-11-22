// Thirty seconds of the happy path: one new cardholder, one charge, one
// matching receipt, one reconcile. Each iteration uses a new owner, so the
// receipt locks do not overlap. Run this after the app has already served
// traffic. The summary includes the whole 30 seconds.
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 4,
  duration: '30s',
  summaryTrendStats: ['avg', 'med', 'p(50)', 'p(95)', 'p(99)', 'count'],
};

const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const apiKey = __ENV.RECON_API_KEY || 'local-dev-key';

function id() {
  return `${Date.now().toString(16)}-${__VU}-${__ITER}-${Math.random().toString(16).slice(2)}`;
}

export default function () {
  const token = id();
  const owner = `load-${token}`;
  const headers = {
    'Content-Type': 'application/json',
    'X-API-Key': apiKey,
  };
  const charge = http.post(
    `${base}/transactions`,
    JSON.stringify({
      cardOwnerId: owner,
      merchant: 'Woolworths',
      amount: '42.50',
      currency: 'AUD',
      txnDate: '2026-11-18',
      tax: '3.86',
    }),
    {
      headers: Object.assign({ 'Idempotency-Key': `hp-txn-${token}` }, headers),
      tags: { name: 'POST /transactions' },
    },
  );
  check(charge, { 'charge 201': (r) => r.status === 201 });
  if (charge.status !== 201) {
    return;
  }

  const receipt = http.post(
    `${base}/documents`,
    JSON.stringify({
      kind: 'RECEIPT',
      cardOwnerId: owner,
      merchant: 'Woolworths',
      amount: '42.50',
      currency: 'AUD',
      docDate: '2026-11-18',
      tax: '3.86',
    }),
    {
      headers: Object.assign({ 'Idempotency-Key': `hp-doc-${token}` }, headers),
      tags: { name: 'POST /documents' },
    },
  );
  check(receipt, { 'receipt 201': (r) => r.status === 201 });
  if (receipt.status !== 201) {
    return;
  }

  const match = http.post(`${base}/reconcile/${charge.json('id')}`, null, {
    headers: {
      'X-API-Key': apiKey,
      'Idempotency-Key': `hp-recon-${token}`,
    },
    tags: { name: 'POST /reconcile/{txnId}' },
  });
  check(match, {
    'reconcile 200': (r) => r.status === 200,
    'auto-approved': (r) => r.status === 200 && r.json('status') === 'APPROVED',
  });
}

export function handleSummary(data) {
  const duration = data.metrics.http_req_duration.values;
  const iteration = data.metrics.iteration_duration.values;
  const reqs = data.metrics.http_reqs.values;
  const failed = data.metrics.http_req_failed.values;
  const checks = data.metrics.checks.values;
  const iters = data.metrics.iterations.values;
  const text = [
    `testRunDurationMs=${data.state.testRunDurationMs}`,
    `http_reqs count=${reqs.count} rate=${reqs.rate}`,
    `iterations count=${iters.count} rate=${iters.rate}`,
    `http_req_failed rate=${failed.rate}`,
    `checks rate=${checks.rate} passes=${checks.passes} fails=${checks.fails}`,
    `http_req_duration p50=${duration['p(50)']} p95=${duration['p(95)']} p99=${duration['p(99)']} avg=${duration.avg}`,
    `iteration_duration p50=${iteration['p(50)']} p95=${iteration['p(95)']} p99=${iteration['p(99)']} avg=${iteration.avg}`,
    '',
  ].join('\n');
  return {
    stdout: text,
    '.load/happy-path-summary.json': JSON.stringify(data, null, 2),
  };
}
