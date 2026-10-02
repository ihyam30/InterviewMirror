import assert from 'node:assert/strict'
import { test } from 'node:test'
import { currentUser, login, logout, refreshCsrf, ApiError } from './api.js'
import { cancelPendingReview } from './pending-review.js'

const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: { 'Content-Type': 'application/json' },
})

test('login obtains CSRF, sends credentials, refreshes token, and reads the user', async () => {
  const calls = []
  let csrfCount = 0
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    if (url === '/api/v1/auth/csrf') return json({ data: { token: `csrf-${++csrfCount}` } })
    if (url === '/api/v1/auth/login') return json({ data: { username: 'demo1' } })
    if (url === '/api/v1/auth/me') return json({ data: { username: 'demo1' } })
    if (url === '/api/v1/auth/logout') return json({ data: null })
    throw new Error(`unexpected request ${url}`)
  }

  const user = await login('demo1@local.interviewmirror', 'MirrorDemo1!')
  assert.equal(user.username, 'demo1')
  const loginRequest = calls.find((call) => call.url === '/api/v1/auth/login')
  assert.equal(loginRequest.options.headers.get('X-XSRF-TOKEN'), 'csrf-1')
  assert.equal(loginRequest.options.credentials, 'include')
  assert.deepEqual(JSON.parse(loginRequest.options.body), { identifier: 'demo1@local.interviewmirror', password: 'MirrorDemo1!' })
  assert.equal((await currentUser()).username, 'demo1')
  await logout()
  assert.equal(calls.some((call) => call.url === '/api/v1/auth/logout'), true)
})

test('API errors carry status and server error code', async () => {
  globalThis.fetch = async () => json({ error: { code: 'RESOURCE_NOT_FOUND', message: 'not found' } }, 404)
  await assert.rejects(refreshCsrf(), (error) => {
    assert.ok(error instanceof ApiError)
    assert.equal(error.status, 404)
    assert.equal(error.code, 'RESOURCE_NOT_FOUND')
    return true
  })
})

test('pending review remains open when deleting its uploaded file fails', async () => {
  const pendingReview = { fileId: 'file-123' }
  let closed = false
  const deletionError = new Error('service unavailable')

  await assert.rejects(
    cancelPendingReview(pendingReview, async (fileId) => {
      assert.equal(fileId, 'file-123')
      throw deletionError
    }, () => { closed = true }),
    deletionError,
  )

  assert.equal(closed, false)
  assert.equal(pendingReview.fileId, 'file-123')

  await cancelPendingReview(pendingReview, async (fileId) => {
    assert.equal(fileId, 'file-123')
  }, () => { closed = true })
  assert.equal(closed, true)
})

test('pending review closes after file deletion succeeds or no uploaded file exists', async () => {
  let deletedFileId
  let closeCount = 0
  await cancelPendingReview({ fileId: 'file-456' }, async (fileId) => { deletedFileId = fileId }, () => { closeCount += 1 })
  assert.equal(deletedFileId, 'file-456')
  assert.equal(closeCount, 1)

  await cancelPendingReview({ fileId: null }, async () => assert.fail('deleteFile should not run'), () => { closeCount += 1 })
  assert.equal(closeCount, 2)
})
