import http from 'k6/http'
import { check, group } from 'k6'
import { Counter, Trend } from 'k6/metrics'

const base = __ENV.BASE_URL || 'http://127.0.0.1:18080'
const endpointNames = ['resumes', 'questionBanks', 'interviews', 'reports', 'me']
const latency = Object.fromEntries(endpointNames.map((name) => [name, new Trend(`api_${name}_duration`, true)]))
const requestErrors = new Counter('api_request_errors')
const requestCount = new Counter('api_request_count')
let loggedIn = false

export const options = {
  noCookiesReset: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: { api_read_latency: { executor: 'shared-iterations', vus: 1, iterations: 500, maxDuration: '5m' } },
  thresholds: {
    api_request_errors: ['count==0'],
    api_resumes_duration: ['p(95)<300'],
    api_questionBanks_duration: ['p(95)<300'],
    api_interviews_duration: ['p(95)<300'],
    api_reports_duration: ['p(95)<300'],
    api_me_duration: ['p(95)<300'],
  },
}

function payload(response, label) {
  requestCount.add(1, { endpoint: label })
  const valid = response.status === 200 && response.json('data') !== null
  if (!valid) requestErrors.add(1, { endpoint: label })
  check(response, { [`${label} returned 200 with data`]: () => valid })
  return valid
}

function authenticate() {
  if (loggedIn) return true
  const csrfResponse = http.get(`${base}/api/v1/auth/csrf`, { tags: { endpoint: 'auth' } })
  if (!payload(csrfResponse, 'csrf')) return false
  const token = csrfResponse.json('data.token')
  const loginResponse = http.post(`${base}/api/v1/auth/login`, JSON.stringify({
    identifier: 'demo1', password: __ENV.DEMO1_PASSWORD,
  }), { headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, tags: { endpoint: 'auth' } })
  if (!payload(loginResponse, 'login')) return false
  const refresh = http.get(`${base}/api/v1/auth/csrf`, { tags: { endpoint: 'auth' } })
  if (!payload(refresh, 'csrf_refresh')) return false
  loggedIn = true
  return true
}

function get(name, path, trend) {
  const response = http.get(`${base}${path}`, { tags: { endpoint: name } })
  latency[trend].add(response.timings.duration)
  if (!payload(response, name)) requestErrors.add(0, { endpoint: name })
}

export default function () {
  if (!authenticate()) return
  const index = (__ITER % endpointNames.length)
  if (index === 0) group('resumes', () => get('resumes', '/api/v1/resumes?usableOnly=true', 'resumes'))
  else if (index === 1) group('questionBanks', () => get('questionBanks', '/api/v1/question-banks?usableOnly=true', 'questionBanks'))
  else if (index === 2) group('interviews', () => get('interviews', '/api/v1/interviews', 'interviews'))
  else if (index === 3) group('reports', () => get('reports', '/api/v1/reports', 'reports'))
  else group('me', () => get('me', '/api/v1/auth/me', 'me'))
}

export function handleSummary(data) {
  const output = JSON.stringify({
    measuredAtUtc: new Date().toISOString(),
    baseUrl: base,
    syntheticOnly: true,
    requestsPerGroup: 100,
    requestCount: data.metrics.api_request_count?.values.count ?? 0,
    errorCount: data.metrics.api_request_errors?.values.count ?? 0,
    successCount: (data.metrics.api_request_count?.values.count ?? 0) - (data.metrics.api_request_errors?.values.count ?? 0),
    errorRate: (data.metrics.api_request_count?.values.count ?? 0) > 0
      ? (data.metrics.api_request_errors?.values.count ?? 0) / data.metrics.api_request_count.values.count
      : null,
    groups: Object.fromEntries(endpointNames.map((name) => {
      const metric = data.metrics[`api_${name}_duration`]
      return [name, metric ? {
        count: metric.values.count,
        p50Ms: metric.values.med,
        p95Ms: metric.values['p(95)'],
        p99Ms: metric.values['p(99)'],
        maxMs: metric.values.max,
      } : null]
    })),
    thresholdsPassed: Object.values(data.metrics).every((metric) => !metric.thresholds || Object.values(metric.thresholds).every((x) => x.ok)),
  }, null, 2)
  return { 'data/phase5/results/api-performance.json': output, stdout: `${output}\n` }
}
