let csrfToken = ''

function clearExpiredSession() {
  csrfToken = ''
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('interviewmirror-auth-expired'))
}

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message)
    this.status = status
    this.code = code
  }
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {})
  if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (options.method && !['GET', 'HEAD', 'OPTIONS'].includes(options.method.toUpperCase()) && csrfToken) {
    headers.set('X-XSRF-TOKEN', csrfToken)
  }
  let response
  try {
    response = await fetch(path, { ...options, headers, credentials: 'include' })
  } catch {
    throw new ApiError(0, 'BACKEND_UNAVAILABLE', '无法连接本地服务，请确认后端已启动。')
  }
  const payload = response.status === 204 ? null : await response.json().catch(() => null)
  if (!response.ok) {
    const error = payload?.error || {}
    if (response.status === 401 && !path.includes('/auth/')) {
      clearExpiredSession()
    }
    throw new ApiError(response.status, error.code || 'REQUEST_FAILED', error.message || '请求失败，请稍后重试。')
  }
  return payload?.data
}

export async function refreshCsrf() {
  csrfToken = ''
  const data = await request('/api/v1/auth/csrf')
  csrfToken = data.token
  return csrfToken
}

export async function login(identifier, password) {
  await refreshCsrf()
  const user = await request('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ identifier, password }),
  })
  await refreshCsrf()
  return user
}

export async function currentUser() {
  return request('/api/v1/auth/me')
}

export async function logout() {
  await request('/api/v1/auth/logout', { method: 'POST' })
  csrfToken = ''
}

export async function listResources(type) {
  const query = type ? `?type=${encodeURIComponent(type)}` : ''
  return request(`/api/v1/resources${query}`)
}

export async function createResource(resource) {
  return request('/api/v1/resources', { method: 'POST', body: JSON.stringify(resource) })
}

export async function updateResource(id, resource) {
  return request(`/api/v1/resources/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(resource) })
}

export async function deleteResource(id) {
  return request(`/api/v1/resources/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export async function listFiles() {
  return request('/api/v1/files')
}

export async function uploadFile(file) {
  const form = new FormData()
  form.set('file', file)
  return request('/api/v1/files', { method: 'POST', body: form })
}

export async function deleteFile(id) {
  return request(`/api/v1/files/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export async function downloadFile(id) {
  if (!csrfToken) await refreshCsrf()
  const response = await fetch(`/api/v1/files/${encodeURIComponent(id)}/content`, { credentials: 'include' })
  if (response.status === 401) clearExpiredSession()
  if (!response.ok) throw new ApiError(response.status, 'FILE_DOWNLOAD_FAILED', '文件下载失败。')
  return response.blob()
}

function documentPath(type, id = '') {
  const base = type === 'RESUME' ? '/api/v1/resumes' : '/api/v1/question-banks'
  return id ? `${base}/${encodeURIComponent(id)}` : base
}

export async function listDocuments(type, usableOnly = false) {
  const query = usableOnly ? '?usableOnly=true' : ''
  return request(`${documentPath(type)}${query}`)
}

export async function createDocument(type, file) {
  const form = new FormData()
  form.set('file', file)
  return request(documentPath(type), { method: 'POST', body: form })
}

export async function createQuestionBank(title) {
  return request(`${documentPath('QUESTION_BANK')}/manual`, {
    method: 'POST', body: JSON.stringify({ title }),
  })
}

export async function getDocument(type, id) {
  return request(documentPath(type, id))
}

export async function updateDocument(type, id, { title, contentVersion, content }) {
  return request(documentPath(type, id), {
    method: 'PUT', body: JSON.stringify({ title, contentVersion, content }),
  })
}

export async function confirmDocument(type, id) {
  return request(`${documentPath(type, id)}/confirm`, { method: 'POST' })
}

export async function retryDocument(type, id) {
  return request(`${documentPath(type, id)}/retry`, { method: 'POST' })
}

export async function deleteDocument(type, id) {
  return request(documentPath(type, id), { method: 'DELETE' })
}

export async function parseTask(id) {
  return request(`/api/v1/parse-tasks/${encodeURIComponent(id)}`)
}

export async function validateInterviewSource(type, id) {
  const route = type === 'RESUME' ? 'resumes' : 'question-banks'
  return request(`/api/v1/interview-sources/${route}/${encodeURIComponent(id)}`)
}

export async function createInterview(interview) {
  return request('/api/v1/interviews', { method: 'POST', body: JSON.stringify(interview) })
}

export async function listInterviews() {
  return request('/api/v1/interviews')
}

export async function getInterview(id) {
  return request(`/api/v1/interviews/${encodeURIComponent(id)}`)
}

export async function startInterview(id) {
  return request(`/api/v1/interviews/${encodeURIComponent(id)}/start`, { method: 'POST' })
}

export async function getInterviewTurns(id) {
  return request(`/api/v1/interviews/${encodeURIComponent(id)}/turns`)
}

export async function answerInterview(id, turnId, clientRequestId, answer) {
  return request(`/api/v1/interviews/${encodeURIComponent(id)}/answers`, {
    method: 'POST', body: JSON.stringify({ turnId, clientRequestId, answer }),
  })
}

export async function replaceInterviewQuestion(id, turnId, clientRequestId) {
  return request(`/api/v1/interviews/${encodeURIComponent(id)}/replace-question`, {
    method: 'POST', body: JSON.stringify({ turnId, clientRequestId }),
  })
}

export async function endInterview(id, clientRequestId) {
  return request(`/api/v1/interviews/${encodeURIComponent(id)}/end`, {
    method: 'POST', body: JSON.stringify({ clientRequestId }),
  })
}

export async function listReports() {
  return request('/api/v1/reports')
}

export async function deleteInterviewReport(interviewId) {
  return request(`/api/v1/interviews/${encodeURIComponent(interviewId)}/report`, { method: 'DELETE' })
}

export async function getReport(id) {
  return request(`/api/v1/reports/${encodeURIComponent(id)}`)
}

export async function getInterviewReportStatus(interviewId) {
  return request(`/api/v1/interviews/${encodeURIComponent(interviewId)}/report-status`)
}

export async function requestInterviewReport(interviewId) {
  return request(`/api/v1/interviews/${encodeURIComponent(interviewId)}/reports`, { method: 'POST' })
}

export async function retryInterviewReport(interviewId) {
  return request(`/api/v1/interviews/${encodeURIComponent(interviewId)}/reports/retry`, { method: 'POST' })
}

export async function downloadReportPdf(reportId) {
  let response
  try {
    response = await fetch(`/api/v1/reports/${encodeURIComponent(reportId)}/pdf`, { credentials: 'include' })
  } catch {
    throw new ApiError(0, 'BACKEND_UNAVAILABLE', '无法连接本地服务，请确认后端已启动。')
  }
  if (response.status === 401) clearExpiredSession()
  if (!response.ok) {
    const payload = await response.json().catch(() => null)
    throw new ApiError(response.status, payload?.error?.code || 'REPORT_PDF_FAILED',
      payload?.error?.message || '报告 PDF 下载失败。')
  }
  return response.blob()
}

export function openInterviewEvents(id) {
  if (typeof EventSource === 'undefined') return null
  return new EventSource(`/api/v1/interviews/${encodeURIComponent(id)}/events`, { withCredentials: true })
}
