import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { performance } from 'node:perf_hooks'

const baseUrl = required('INTERVIEW_MODEL_BASE_URL').replace(/\/+$/, '')
const model = required('INTERVIEW_MODEL_ID')
const apiKey = required('INTERVIEW_MODEL_API_KEY')
const sampleCount = Number(process.env.PHASE5_MODEL_SAMPLES || 20)
const inputPrice = Number(process.env.PHASE5_INPUT_PRICE_CNY_PER_MILLION || 0.8)
const outputPrice = Number(process.env.PHASE5_OUTPUT_PRICE_CNY_PER_MILLION || 2.0)
const timeoutMs = Number(process.env.PHASE5_MODEL_TIMEOUT_MS || 60000)
const endpoint = `${baseUrl}/chat/completions`
const results = []

if (!Number.isInteger(sampleCount) || sampleCount < 10 || sampleCount > 30) {
  throw new Error('PHASE5_MODEL_SAMPLES must be between 10 and 30.')
}

for (let sample = 1; sample <= sampleCount; sample += 1) {
  const started = performance.now()
  let ttftMs = null
  let totalMs = null
  let status = 'FAILED'
  let promptTokens = null
  let completionTokens = null
  let errorType = null
  const abort = new AbortController()
  const timeout = setTimeout(() => abort.abort(), timeoutMs)

  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
      signal: abort.signal,
      body: JSON.stringify({
        model,
        stream: true,
        stream_options: { include_usage: true },
        temperature: 0,
        max_tokens: 96,
        messages: [
          { role: 'system', content: '你是面试评测链路的合成性能探针。只返回一句简体中文，不含个人信息。' },
          { role: 'user', content: `样本 ${sample}：用一句话说明设计 HTTP 接口时如何保证重复请求安全。` },
        ],
      }),
    })
    if (!response.ok || !response.body) {
      errorType = `http_${response.status}`
      await response.body?.cancel().catch(() => {})
      throw new Error(errorType)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let pending = ''
    let done = false
    while (!done) {
      const read = await reader.read()
      done = read.done
      pending += decoder.decode(read.value || new Uint8Array(), { stream: !done })
      const lines = pending.split(/\r?\n/)
      pending = lines.pop() || ''
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const payloadText = line.slice(5).trim()
        if (!payloadText || payloadText === '[DONE]') continue
        let payload
        try { payload = JSON.parse(payloadText) } catch { continue }
        const content = payload.choices?.[0]?.delta?.content
        if (ttftMs === null && typeof content === 'string' && content.length > 0) {
          ttftMs = performance.now() - started
        }
        const usage = payload.usage
        if (usage && Number.isFinite(usage.prompt_tokens) && Number.isFinite(usage.completion_tokens)) {
          promptTokens = usage.prompt_tokens
          completionTokens = usage.completion_tokens
        }
      }
    }
    if (pending.startsWith('data:')) {
      const payloadText = pending.slice(5).trim()
      if (payloadText && payloadText !== '[DONE]') {
        try {
          const payload = JSON.parse(payloadText)
          const content = payload.choices?.[0]?.delta?.content
          if (ttftMs === null && typeof content === 'string' && content.length > 0) ttftMs = performance.now() - started
          const usage = payload.usage
          if (usage && Number.isFinite(usage.prompt_tokens) && Number.isFinite(usage.completion_tokens)) {
            promptTokens = usage.prompt_tokens
            completionTokens = usage.completion_tokens
          }
        } catch {}
      }
    }
    totalMs = performance.now() - started
    if (ttftMs === null) throw new Error('no_visible_text_token')
    status = 'SUCCESS'
  } catch (error) {
    totalMs = performance.now() - started
    errorType ||= error?.name === 'AbortError' ? 'timeout' : error?.message || 'request_failed'
  } finally {
    clearTimeout(timeout)
  }
  results.push({ sample, status, ttftMs: round(ttftMs), totalMs: round(totalMs), promptTokens, completionTokens, errorType })
  console.log(`sample=${sample}/${sampleCount} status=${status} ttftMs=${round(ttftMs) ?? 'n/a'} totalMs=${round(totalMs) ?? 'n/a'} error=${errorType || 'none'}`)
}

const successful = results.filter((item) => item.status === 'SUCCESS' && Number.isFinite(item.ttftMs))
const usageComplete = results.every((item) => item.status === 'SUCCESS' && Number.isFinite(item.promptTokens) && Number.isFinite(item.completionTokens))
const promptTokens = usageComplete ? results.reduce((sum, item) => sum + item.promptTokens, 0) : null
const completionTokens = usageComplete ? results.reduce((sum, item) => sum + item.completionTokens, 0) : null
const failureCount = results.length - successful.length
const ttftP95 = percentile(successful.map((item) => item.ttftMs), 0.95)
const totalP95 = percentile(successful.map((item) => item.totalMs), 0.95)
const costCny = usageComplete
  ? roundCost(promptTokens * inputPrice / 1_000_000 + completionTokens * outputPrice / 1_000_000)
  : null
const result = {
  measuredAtUtc: new Date().toISOString(),
  syntheticOnly: true,
  provider: process.env.INTERVIEW_MODEL_PROVIDER || 'configured-provider',
  model,
  measurement: 'Direct provider SSE; TTFT is first non-empty assistant text delta; app interview endpoint currently returns complete structured objects, not streaming tokens.',
  sampleCount,
  successCount: successful.length,
  failureCount,
  ttftMs: { p50: percentile(successful.map((item) => item.ttftMs), 0.5), p95: ttftP95, max: maximum(successful.map((item) => item.ttftMs)) },
  fullResponseMs: { p50: percentile(successful.map((item) => item.totalMs), 0.5), p95: totalP95, max: maximum(successful.map((item) => item.totalMs)) },
  usageReported: usageComplete,
  inputTokens: promptTokens,
  outputTokens: completionTokens,
  ratesCnyPerMillion: { input: inputPrice, output: outputPrice },
  measuredCallCostCny: costCny,
  monthlyBudgetCny: 300,
  monthlyCallsAtBudgetEstimate: costCny > 0 ? Math.floor(300 / (costCny / sampleCount)) : null,
  errorTypes: [...new Set(results.filter((item) => item.errorType).map((item) => item.errorType))],
  samples: results,
}
const outputPath = resolve(process.env.PHASE5_MODEL_RESULT_PATH || 'data/phase5/results/model-performance.json')
await mkdir(dirname(outputPath), { recursive: true })
await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8')
console.log(JSON.stringify({ result: outputPath, successCount: result.successCount, failureCount, ttftP95Ms: ttftP95, fullResponseP95Ms: totalP95, inputTokens: promptTokens, outputTokens: completionTokens, usageReported: usageComplete, measuredCallCostCny: costCny }))

if (failureCount > 0 || !usageComplete || ttftP95 === null || ttftP95 > 5000) process.exitCode = 1

function required(name) {
  const value = process.env[name]
  if (!value || value.startsWith('replace-with-')) throw new Error(`${name} is not configured; secret values are never printed.`)
  return value
}
function round(value) { return Number.isFinite(value) ? Math.round(value * 100) / 100 : null }
function roundCost(value) { return Number.isFinite(value) ? Math.round(value * 1_000_000) / 1_000_000 : null }
function percentile(values, rate) {
  if (!values.length) return null
  const sorted = [...values].sort((left, right) => left - right)
  return round(sorted[Math.ceil(rate * sorted.length) - 1])
}
function maximum(values) { return values.length ? round(Math.max(...values)) : null }
