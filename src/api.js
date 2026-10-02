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
