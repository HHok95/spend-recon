// Reconcile each charge written by scripts/seed.sh, once.
// One iteration is one POST /reconcile. Owners do not share receipts.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const seed = JSON.parse(open('../../.load/seed.json'));
const charges = seed.charges;

export const options = {
  scenarios: {
    reconcile: {
      executor: 'shared-iterations',
      vus: 4,
      iterations: charges.length,
      maxDuration: '3m',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(50)', 'p(95)', 'p(99)', 'count'],
};

const base = __ENV.BASE_URL || seed.baseUrl || 'http://127.0.0.1:8080';
const apiKey = __ENV.RECON_API_KEY || 'local-dev-key';

export default function () {
  const charge = charges[exec.scenario.iterationInTest];
  const response = http.post(`${base}/reconcile/${charge.id}`, null, {
    headers: {
      'X-API-Key': apiKey,
      'Idempotency-Key': `load-recon-${charge.id}`,
    },
    tags: { name: 'POST /reconcile/{txnId}' },
  });
  check(response, {
    'reconcile 200': (r) => r.status === 200,
    'status matches the seed case': (r) => r.status === 200 && r.json('status') === charge.expect,
  });
}

export function handleSummary(data) {
  return {
    stdout: summaryText(data),
    '.load/reconcile-seed-summary.json': JSON.stringify(data, null, 2),
  };
}

function summaryText(data) {
  const duration = data.metrics.http_req_duration.values;
  const reqs = data.metrics.http_reqs.values;
  const failed = data.metrics.http_req_failed.values;
  const checks = data.metrics.checks.values;
  return [
    `testRunDurationMs=${data.state.testRunDurationMs}`,
    `http_reqs count=${reqs.count} rate=${reqs.rate}`,
    `http_req_failed rate=${failed.rate}`,
    `checks rate=${checks.rate} passes=${checks.passes} fails=${checks.fails}`,
    `http_req_duration p50=${duration['p(50)']} p95=${duration['p(95)']} p99=${duration['p(99)']} avg=${duration.avg}`,
    '',
  ].join('\n');
}
