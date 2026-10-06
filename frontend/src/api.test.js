import assert from 'node:assert/strict'
import { test } from 'node:test'
import { currentUser, login, logout, refreshCsrf, restoreSession, ApiError, listReports, getReport,
  getInterviewReportStatus, requestInterviewReport, retryInterviewReport, downloadReportPdf } from './api.js'
import { cancelPendingReview } from './pending-review.js'
import { canReviewDocument, canRetryDocument, canUseDocument, documentStatusLabel, questionBankReviewCopy } from './document-state.js'
import { answerInterview, createDocument, createInterview, createQuestionBank, endInterview, getInterview, getInterviewTurns,
  listInterviews, replaceInterviewQuestion, startInterview, updateDocument, validateInterviewSource } from './api.js'
import { buildResumeContent, projectRowsForReview } from './document-content.js'

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

test('restoring an authenticated session refreshes CSRF before write requests', async () => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    if (url === '/api/v1/auth/csrf') return json({ data: { token: 'csrf-after-reload' } })
    if (url === '/api/v1/auth/me') return json({ data: { username: 'demo1' } })
    if (url === '/api/v1/auth/logout') return json({ data: null })
    if (url === '/api/v1/question-banks/manual') return json({ data: { id: 'bank-1', status: 'PENDING' } })
    throw new Error(`unexpected request ${url}`)
  }

  await logout()
  calls.length = 0
  assert.equal((await restoreSession()).username, 'demo1')
  await createQuestionBank('Fresh after reload')

  assert.deepEqual(calls.map(({ url }) => url), [
    '/api/v1/auth/me',
    '/api/v1/auth/csrf',
    '/api/v1/question-banks/manual',
  ])
  const restoredCreateRequest = calls[2]
  assert.equal(restoredCreateRequest.options.headers.get('X-XSRF-TOKEN'), 'csrf-after-reload')
  assert.equal(restoredCreateRequest.options.credentials, 'include')

  await logout()
  calls.length = 0
  await createQuestionBank('Write without prior restore')
  assert.deepEqual(calls.map(({ url }) => url), [
    '/api/v1/auth/csrf',
    '/api/v1/question-banks/manual',
  ])
  const fallbackCreateRequest = calls[1]
  assert.equal(fallbackCreateRequest.options.headers.get('X-XSRF-TOKEN'), 'csrf-after-reload')
  assert.equal(fallbackCreateRequest.options.credentials, 'include')
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

test('document lifecycle labels and actions require the server confirmed flag', () => {
  assert.equal(documentStatusLabel('PENDING'), '等待解析')
  assert.equal(documentStatusLabel('PROCESSING'), '正在解析')
  assert.equal(documentStatusLabel('PARSED'), '解析完成 · 待确认')
  assert.equal(documentStatusLabel('FAILED'), '解析失败')
  assert.equal(canReviewDocument({ status: 'PARSED' }), true)
  assert.equal(canRetryDocument({ status: 'FAILED' }), true)
  assert.equal(canUseDocument({ status: 'PARSED', usableForInterview: false }), false)
  assert.equal(canUseDocument({ status: 'CONFIRMED', usableForInterview: true }), true)
})

test('question bank review copy distinguishes uploaded and manually created banks', () => {
  assert.deepEqual(questionBankReviewCopy({ fileId: 'uploaded-file' }), {
    description: '来源文件已完成本地解析。保存修改后需再次确认，才能用于面试。',
    listTitle: '解析出的题目',
  })
  assert.deepEqual(questionBankReviewCopy({ fileId: null }), {
    description: '这是手工创建的题库。保存修改后需确认，才能用于面试。',
    listTitle: '题目列表',
  })
})

test('document API sends multipart data and uses owner-confirmation gate endpoint', async () => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return json({ data: { id: 'doc-1', status: 'PENDING' } })
  }
  await createDocument('RESUME', new File(['fixture'], 'resume.pdf', { type: 'application/pdf' }))
  await updateDocument('QUESTION_BANK', 'bank-1', {
    title: 'Bank', contentVersion: 2, content: { schemaVersion: 'v1', questions: [] },
  })
  await validateInterviewSource('RESUME', 'resume-1')
  assert.equal(calls[0].url, '/api/v1/resumes')
  assert.ok(calls[0].options.body instanceof FormData)
  assert.equal(calls[1].url, '/api/v1/question-banks/bank-1')
  assert.equal(JSON.parse(calls[1].options.body).contentVersion, 2)
  assert.equal(calls[2].url, '/api/v1/interview-sources/resumes/resume-1')
})

test('interview API uses authenticated JSON requests for lifecycle and idempotent answers', async () => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return json({ data: { id: 'interview-1', status: 'RUNNING' } })
  }
  await createInterview({ schemaVersion: '1.1.0', mode: 'COMPREHENSIVE', modelDataConsent: true })
  await startInterview('interview-1')
  await getInterview('interview-1')
  await getInterviewTurns('interview-1')
  await listInterviews()
  await answerInterview('interview-1', 'turn-1', 'answer-request-1', 'candidate answer')
  await replaceInterviewQuestion('interview-1', 'turn-2', 'replace-request-1')
  await endInterview('interview-1', 'end-request-1')
  assert.deepEqual(calls.map((call) => call.url), [
    '/api/v1/interviews', '/api/v1/interviews/interview-1/start', '/api/v1/interviews/interview-1',
    '/api/v1/interviews/interview-1/turns', '/api/v1/interviews',
    '/api/v1/interviews/interview-1/answers', '/api/v1/interviews/interview-1/replace-question',
    '/api/v1/interviews/interview-1/end',
  ])
  assert.deepEqual(JSON.parse(calls[5].options.body), {
    turnId: 'turn-1', clientRequestId: 'answer-request-1', answer: 'candidate answer',
  })
  assert.equal(calls[5].options.credentials, 'include')
})

test('report APIs use owner-authenticated routes and PDF download returns the binary response', async () => {
  const calls = []
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url, options })
    if (url.endsWith('/pdf')) return new Response(new Blob(['%PDF-1.7']), { status: 200, headers: { 'Content-Type': 'application/pdf' } })
    return json({ data: null })
  }

  await listReports()
  await getReport('report/1')
  await getInterviewReportStatus('interview/1')
  await requestInterviewReport('interview/1')
  await retryInterviewReport('interview/1')
  const pdf = await downloadReportPdf('report/1')

  assert.equal(calls[0].url, '/api/v1/reports')
  assert.equal(calls[1].url, '/api/v1/reports/report%2F1')
  assert.equal(calls[2].url, '/api/v1/interviews/interview%2F1/report-status')
  assert.equal(calls[3].url, '/api/v1/interviews/interview%2F1/reports')
  assert.equal(calls[4].options.method, 'POST')
  assert.equal(calls[5].url, '/api/v1/reports/report%2F1/pdf')
  assert.equal(calls[5].options.credentials, 'include')
  assert.equal(pdf.type, 'application/pdf')
})

test('resume review can edit project details without dropping parsed technologies or outcomes', () => {
  const original = {
    schemaVersion: 'interviewmirror.resume-content.v1',
    projects: [{ name: 'RAG Workbench', technologies: ['Java', 'Spring AI'], outcomes: ['Recall@5 0.92'], sourcePage: 2 }],
  }
  const [project] = projectRowsForReview(original.projects)
  project.name = 'RAG Interview Workbench'
  project.technologiesText += ', PostgreSQL'
  project.outcomesText += '\nReduced p95 to 420 ms'
  const content = buildResumeContent(original, {
    personalInfo: { name: 'Candidate', email: '', phone: '', location: '' },
    educationText: '', experienceText: '', projects: [project], skillsText: '', awardsText: '', metricsText: '',
  })
  assert.deepEqual(content.projects, [{
    name: 'RAG Interview Workbench',
    technologies: ['Java', 'Spring AI', 'PostgreSQL'],
    outcomes: ['Recall@5 0.92', 'Reduced p95 to 420 ms'],
    sourcePage: 2,
  }])
  assert.deepEqual(content.personalInfo, { name: 'Candidate' })
})
