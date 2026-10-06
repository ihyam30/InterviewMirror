<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { restoreSession, confirmDocument, createDocument, createQuestionBank, deleteDocument, deleteInterviewReport, downloadFile, getDocument, listDocuments, login as apiLogin, logout as apiLogout, retryDocument, updateDocument, validateInterviewSource, createInterview, listInterviews, getInterview, startInterview, getInterviewTurns, answerInterview, replaceInterviewQuestion, endInterview, openInterviewEvents, listReports as apiListReports, getReport as apiGetReport, getInterviewReportStatus, requestInterviewReport, retryInterviewReport, downloadReportPdf } from './api.js'
import { canReviewDocument, canRetryDocument, documentStatusLabel, questionBankReviewCopy } from './document-state.js'
import { resumeViewModel } from './resume-display.js'
import { reportTaskLabel, visibleReportScores } from './report-display.js'

const navItems = [
  { id: 'home', label: '工作台', icon: 'home' },
  { id: 'practice', label: '模拟面试', icon: 'mic' },
  { id: 'reports', label: '历史报告', icon: 'report' },
  { id: 'banks', label: '自定义题库', icon: 'bank' },
  { id: 'resumes', label: '我的简历', icon: 'resume' },
]

const resumes = ref([])
const banks = ref([])
const reports = ref([])
const deletingReportInterviews = ref(new Set())
const reportDetails = ref({})
const page = ref('home')
const selectedNav = computed(() => (page.value === 'reportDetail' ? 'reports' : page.value === 'resumeDetail' ? 'resumes' : page.value))
const selectedResumeDetailId = ref(null)
const activeResume = computed(() => resumes.value.find((resume) => resume.id === selectedResumeDetailId.value) ?? null)
const activeResumeView = computed(() => activeResume.value ? resumeViewModel(activeResume.value.content, activeResume.value.name) : null)
const selectedReportId = ref(null)
const currentReport = computed(() => reportDetails.value[selectedReportId.value]
  ?? reports.value.find((report) => report.id === selectedReportId.value)
  ?? null)
const reportRadar = computed(() => {
  const scores = currentReport.value?.scores || []
  const centerX = 120, centerY = 92, radius = 58
  const dimensionCount = Math.max(scores.length, 1)
  const points = scores.map((item, index) => {
    const angle = -Math.PI / 2 + (Math.PI * 2 * index) / dimensionCount
    const distance = item.score == null ? null : radius * (item.score / 5)
    return {
      ...item,
      x: centerX + Math.cos(angle) * (distance ?? 0),
      y: centerY + Math.sin(angle) * (distance ?? 0),
      axisX: centerX + Math.cos(angle) * radius,
      axisY: centerY + Math.sin(angle) * radius,
      labelX: centerX + Math.cos(angle) * (radius + 19),
      labelY: centerY + Math.sin(angle) * (radius + 19),
    }
  })
  const gridPolygons = [1, 0.75, 0.5].map((scale) => points.map((point, index) => {
    const angle = -Math.PI / 2 + (Math.PI * 2 * index) / dimensionCount
    return `${centerX + Math.cos(angle) * radius * scale},${centerY + Math.sin(angle) * radius * scale}`
  }).join(' '))
  return { points, gridPolygons, polygon: points.length >= 3 && points.every((item) => item.score != null)
    ? points.map((item) => `${item.x},${item.y}`).join(' ') : '' }
})
const reportStatus = ref(null)
const reportBusy = ref(false)
const mode = ref('COMPREHENSIVE')
const selectedResumeId = ref(resumes.value.find((resume) => resume.default)?.id ?? resumes.value[0]?.id ?? '')
const selectedBankId = ref(banks.value[0]?.id ?? '')
const jdText = ref('')
const interviewStage = ref('setup')
const answerText = ref('')
const isThinking = ref(false)
const messages = ref([])
const turns = ref([])
const activeInterview = ref(null)
const lastCompletedInterviewId = ref(null)
const reportPollingInterviewId = ref(null)
const showReturnToCompletedResult = ref(false)
const reportPollingError = ref('')
const modelDataConsent = ref(false)
const pendingInterviewId = ref(null)
const pendingAnswerRequest = ref(null)
const answerScrollTarget = ref(null)
const pendingReplaceRequest = ref(null)
const pendingEndRequest = ref(null)
const reportTitleDraft = ref('')
const toast = ref('')
const reviewDialog = ref(false)
const pendingReview = ref(null)
const pendingReviewCopy = computed(() => questionBankReviewCopy(pendingReview.value))
const resumeInput = ref(null)
const bankInput = ref(null)
let toastTimer
let interviewEvents = null
let reportPollTimer
let reportHistoryPollTimer
let reportPollFailureCount = 0
let reportHistoryRequest = null
let reportHistoryRevision = 0
const parseTimers = new Map()
const authReady = ref(false)
const currentUserInfo = ref(null)
const loginForm = ref({ identifier: 'demo1', password: '' })
const loginBusy = ref(false)
const loginError = ref('')

const activeTurn = computed(() => activeInterview.value?.activeTurn ?? null)
const chatTimeline = ref(null)

watch(activeTurn, async (turn) => {
  const target = answerScrollTarget.value
  if (!target || !turn?.id) return
  if (activeInterview.value?.id !== target.interviewId) {
    answerScrollTarget.value = null
    return
  }
  if (turn.id === target.turnId) return

  answerScrollTarget.value = null
  await nextTick()
  if (page.value !== 'practice' || interviewStage.value !== 'active') return
  const nextQuestion = chatTimeline.value?.querySelector('[data-active-turn="true"]')
  if (!nextQuestion) return

  const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  nextQuestion.scrollIntoView?.({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'center' })
}, { flush: 'post' })

const interviewLiveLabel = computed(() => {
  if (activeInterview.value?.status === 'COMPLETING') return '正在安全结束面试'
  if (activeInterview.value?.transitionPending) return '正在生成下一题'
  return '练习进行中'
})
const interviewProgress = computed(() => ({
  current: Math.min(activeInterview.value?.currentMainIndex ?? 0, activeInterview.value?.mainQuestionTarget ?? 6),
  target: activeInterview.value?.mainQuestionTarget ?? 6,
}))

async function replaceCurrentQuestion() {
  if (!activeInterview.value || !activeTurn.value || isThinking.value) return
  if (!pendingReplaceRequest.value || pendingReplaceRequest.value.turnId !== activeTurn.value.id) {
    pendingReplaceRequest.value = { turnId: activeTurn.value.id, clientRequestId: crypto.randomUUID() }
  }
  isThinking.value = true
  try {
    await replaceInterviewQuestion(activeInterview.value.id, pendingReplaceRequest.value.turnId,
      pendingReplaceRequest.value.clientRequestId)
    pendingReplaceRequest.value = null
    await refreshInterview(activeInterview.value.id)
    showToast('已更换当前问题')
  } catch (error) {
    showToast(error.message || '暂时无法更换问题')
  } finally { isThinking.value = false }
}

function closeInterviewEvents() {
  interviewEvents?.close()
  interviewEvents = null
}

function displayInterviewTurns(rows) {
  turns.value = rows.map((turn) => ({ question: turn.question, answer: turn.answer, type: turn.type, status: turn.status, id: turn.id }))
  messages.value = []
  for (const turn of rows) {
    messages.value.push({ role: 'assistant', content: turn.question, time: '面试官', turnId: turn.id })
    if (turn.answer) messages.value.push({ role: 'user', content: turn.answer, time: '我', turnId: turn.id })
  }
}

async function refreshInterview(interviewId, { restore = false } = {}) {
  const [interview, turnList] = await Promise.all([getInterview(interviewId), getInterviewTurns(interviewId)])
  activeInterview.value = interview
  displayInterviewTurns(turnList.turns || [])
  if (restore) {
    mode.value = interview.mode
    selectedResumeId.value = interview.resumeId || ''
    selectedBankId.value = interview.questionBankId || ''
    reportTitleDraft.value = interview.title
    interviewStage.value = interview.status === 'COMPLETE' ? 'complete' : 'active'
    page.value = 'practice'
  }
  if (interview.status === 'COMPLETE') {
    lastCompletedInterviewId.value = interviewId
    closeInterviewEvents()
    interviewStage.value = 'complete'
    pendingEndRequest.value = null
    isThinking.value = false
  } else if (!interview.transitionPending && interview.status === 'RUNNING') {
    isThinking.value = false
  }
  return interview
}

function connectInterviewEvents(interviewId) {
  closeInterviewEvents()
  interviewEvents = openInterviewEvents(interviewId)
  if (!interviewEvents) return
  for (const eventName of ['interview.started', 'interview.question', 'interview.followup', 'interview.answer.saved', 'interview.completed', 'interview.error']) {
    interviewEvents.addEventListener(eventName, () => {
      refreshInterview(interviewId).catch(() => {})
    })
  }
  interviewEvents.onerror = () => {
    // EventSource reconnects with Last-Event-ID; REST remains the source of truth.
  }
}

function remoteItem(resource) {
  const content = resource.content || {}
  const questions = Array.isArray(content.questions) ? content.questions : []
  const projects = Array.isArray(content.projects) ? content.projects : []
  const education = Array.isArray(content.education) ? content.education : []
  const skills = Array.isArray(content.skills) ? content.skills : []
  const extension = resource.originalFilename?.split('.').pop()?.toUpperCase() || '自建'
  return {
    ...resource, content, id: resource.id, resourceId: resource.id, fileId: resource.fileId,
    name: resource.title, fileName: resource.originalFilename, format: extension,
    size: resource.sizeBytes ? formatSize(resource.sizeBytes) : '手动创建',
    updated: new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(resource.updatedAt)),
    ready: resource.usableForInterview === true, statusLabel: documentStatusLabel(resource.status),
    questions: questions.length, questionItems: questions,
    skills: skills.join('、'), project: projects.map((item) => item.name || item.description || '').filter(Boolean).join('；'),
    projects: projects.length, educationText: education.map((item) => item.details || item.institution || '').filter(Boolean).join('；'),
  }
}

async function loadOwnedResources() {
  const [resumeRows, bankRows] = await Promise.all([listDocuments('RESUME'), listDocuments('QUESTION_BANK')])
  resumes.value = resumeRows.map(remoteItem)
  banks.value = bankRows.map(remoteItem)
  selectedResumeId.value = resumes.value.find((item) => item.default)?.id ?? resumes.value[0]?.id ?? ''
  selectedBankId.value = banks.value[0]?.id ?? ''
}

onMounted(async () => {
  try {
    currentUserInfo.value = await restoreSession()
    restoreExplicitReportReturn()
    await loadOwnedResources()
    await loadReportHistory()
    const interviewRows = await listInterviews()
    const recent = interviewRows.find((item) => item.status === 'RUNNING')
    if (recent) {
      await refreshInterview(recent.id, { restore: true })
      connectInterviewEvents(recent.id)
    }
  } catch (error) {
    if (error.status !== 401) loginError.value = error.message
  } finally {
    authReady.value = true
  }
})

async function submitLogin() {
  loginBusy.value = true
  loginError.value = ''
  try {
    currentUserInfo.value = await apiLogin(loginForm.value.identifier.trim(), loginForm.value.password)
    loginForm.value.password = ''
    restoreExplicitReportReturn()
    await loadOwnedResources()
    await loadReportHistory()
  } catch (error) {
    loginError.value = error.message
  } finally {
    loginBusy.value = false
  }
}

async function signOut() {
  closeInterviewEvents()
  clearInterval(reportPollTimer)
  clearInterval(reportHistoryPollTimer)
  reportPollingInterviewId.value = null
  lastCompletedInterviewId.value = null
  clearExplicitReportReturn()
  showReturnToCompletedResult.value = false
  reportPollingError.value = ''
  try { await apiLogout() } catch { /* clear local view even if the server is unreachable */ }
  currentUserInfo.value = null
  resumes.value = []
  banks.value = []
  reports.value = []
  reportDetails.value = {}
  selectedReportId.value = null
}

function handleExpiredSession() {
  clearInterval(reportPollTimer)
  clearInterval(reportHistoryPollTimer)
  reportPollingInterviewId.value = null
  lastCompletedInterviewId.value = null
  clearExplicitReportReturn()
  showReturnToCompletedResult.value = false
  reportPollingError.value = ''
  currentUserInfo.value = null
  resumes.value = []
  banks.value = []
  loginError.value = '登录状态已失效，请重新登录。'
}

window.addEventListener('interviewmirror-auth-expired', handleExpiredSession)
onUnmounted(() => window.removeEventListener('interviewmirror-auth-expired', handleExpiredSession))
onUnmounted(() => parseTimers.forEach((timer) => clearTimeout(timer)))
onUnmounted(closeInterviewEvents)
onUnmounted(() => clearInterval(reportPollTimer))
onUnmounted(() => clearInterval(reportHistoryPollTimer))

const pageHeading = computed(() => ({
  home: ['工作台', '为下一场面试，先练一次。'],
  practice: ['模拟面试', '选择练习方式，开始一场专注的模拟面试。'],
  reports: ['历史报告', '回看每一次练习，找到持续进步的证据。'],
  reportDetail: ['面试复盘', '把表现拆解清楚，让下一次准备更有方向。'],
  banks: ['自定义题库', '整理你的题目，在专项面试中逐题练习。'],
  resumes: ['我的简历', '查看解析后的简历内容，确认后即可用于综合面试。'],
  resumeDetail: ['简历详情', '查看本地解析出的简历内容与个人经历。'],
}[page.value] ?? ['工作台', '']))

const comprehensiveCount = computed(() => reports.value.filter((report) => report.modeCode === 'COMPREHENSIVE').length)
const averageScore = computed(() => {
  const scored = reports.value.filter((report) => Number.isFinite(report.overallScore))
  if (!scored.length) return '—'
  return Math.round(scored.reduce((total, report) => total + report.overallScore, 0) / scored.length)
})
const generatedReportCount = computed(() => reports.value.filter((report) => report.id).length)
const reportStatusLabel = computed(() => {
  if (reportPollingError.value) return '暂时无法获取报告状态'
  const status = reportStatus.value?.reportTask?.status
  if (status === 'PENDING') return '报告排队中'
  if (status === 'PROCESSING') return '正在生成报告'
  if (status === 'FAILED') return '报告生成失败'
  if (status === 'SUCCESS') return '报告已生成'
  return '等待报告任务'
})

function formatReportDate(value) {
  if (!value) return '时间未知'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function mapReportSummary(item) {
  const report = item.report || item
  const summary = report.summary || {}
  const modeCode = report.mode || item.mode || 'COMPREHENSIVE'
  const scores = visibleReportScores(report.scores || {}, modeCode).map(([key, score]) => ({
    key, label: scoreLabels[key] || key, score: score.status === 'ASSESSED' ? score.value : null,
    status: score.status,
    note: score.status === 'UNASSESSED'
      ? '本场没有足够的直接面试证据，无法评估此项。可重新评估报告；若本场没有相关回答，该维度会继续保持未评估。'
      : score.status === 'NOT_APPLICABLE' ? '此维度不适用于本场面试。' : score.rationale || '',
    evidence: score.evidence || [],
  }))
  return {
    id: item.id || report.reportId,
    interviewId: item.interviewId || report.interviewId,
    title: report.title || item.title || '面试报告',
    modeCode,
    mode: modeCode === 'QUESTION_BANK' ? '题库专项' : '综合面试',
    date: formatReportDate(item.completedAt || report.completedAt),
    questionCount: Number.isInteger(item.questionCount) ? item.questionCount : Array.isArray(report.turns) ? report.turns.length : 0,
    overallScore: Number.isFinite(summary.overallScore) ? summary.overallScore : Number.isFinite(item.overallScore) ? item.overallScore : null,
    overall: summary.overallReview || '报告生成中',
    overallEvidence: summary.overallReviewEvidence || [],
    overallReviewEvidenceStatus: summary.overallReviewEvidenceStatus || item.overallReviewEvidenceStatus || null,
    scores,
    hasUnassessedDimensions: item.hasUnassessedDimensions === true || scores.some((score) => score.status === 'UNASSESSED'),
    turns: (report.turns || []).map((turn) => ({
      id: turn.turnId, question: turn.question, answer: turn.answer, note: turn.feedback,
      strengths: turn.strengths || [], improvements: turn.improvements || [], evidence: turn.evidence || [],
    })),
    strengths: (report.strengths || []).map((entry) => typeof entry === 'string' ? entry : entry.text),
    weaknesses: (report.risks || []).map((entry) => typeof entry === 'string' ? entry : entry.text),
    suggestions: (report.recommendations || []).map((entry) => `${entry.action}${entry.why ? `：${entry.why}` : ''}`),
    learning: (report.learningPath || []).map((entry) => `${entry.objective}${entry.activities?.length ? `：${entry.activities.join('；')}` : ''}`),
    raw: report,
    sourceSnapshot: item.sourceSnapshot || {},
    jdText: item.sourceSnapshot?.jdText || '',
    resumeId: report.sourceSnapshot?.resumeSnapshotId || '',
    bankId: report.sourceSnapshot?.questionBankSnapshotId || '',
    reportStatus: report.status || item.reportStatus || item.status || null,
    reportTaskStatus: item.reportTaskStatus || (item.id || report.reportId ? 'SUCCESS' : 'PENDING'),
    reportErrorMessage: item.reportErrorMessage || '',
  }
}

const scoreLabels = {
  TECHNICAL_DEPTH: '技术深度', PROJECT_EXPERIENCE: '项目经验', JOB_MATCH: '岗位匹配',
  COMMUNICATION: '沟通表达', LOGICAL_STRUCTURE: '逻辑结构', PROBLEM_SOLVING: '问题解决',
}

async function loadReportHistory() {
  if (reportHistoryRequest) return reportHistoryRequest
  const requestRevision = reportHistoryRevision
  reportHistoryRequest = (async () => {
    const rows = await apiListReports()
    if (requestRevision !== reportHistoryRevision) return
    reports.value = rows.map((row) => mapReportSummary(row))
    if (!selectedReportId.value || !reports.value.some((row) => row.id === selectedReportId.value)) {
      selectedReportId.value = reports.value.find((row) => row.id)?.id || null
    }
  })()
  try { await reportHistoryRequest }
  finally { reportHistoryRequest = null }
}

async function pollCompletedReport() {
  const interviewId = reportPollingInterviewId.value
  if (!interviewId) return
  try {
    let status = await getInterviewReportStatus(interviewId)
    if (!status.reportTask) {
      await requestInterviewReport(interviewId)
      status = await getInterviewReportStatus(interviewId)
    }
    reportStatus.value = status
    reportPollingError.value = ''
    reportPollFailureCount = 0
    if (status.reportId && status.reportTask?.status === 'SUCCESS') await loadReportHistory()
    const reportDone = ['SUCCESS', 'FAILED'].includes(status.reportTask?.status)
    if (reportDone) {
      clearInterval(reportPollTimer)
      reportPollTimer = undefined
    }
  } catch (error) {
    reportPollingError.value = `暂时无法读取报告进度：${error.message || '后端连接失败'}。可以重新检查状态。`
    reportPollFailureCount += 1
    if (reportPollFailureCount >= 5) {
      clearInterval(reportPollTimer)
      reportPollTimer = undefined
    }
  }
}

function startReportPolling(interviewId = lastCompletedInterviewId.value) {
  if (!interviewId) return
  clearInterval(reportPollTimer)
  if (reportPollingInterviewId.value !== interviewId) reportStatus.value = null
  reportPollingInterviewId.value = interviewId
  reportPollingError.value = ''
  reportPollFailureCount = 0
  pollCompletedReport()
  reportPollTimer = setInterval(pollCompletedReport, 2000)
}

async function retryCompletedReport() {
  if (!lastCompletedInterviewId.value) return
  try {
    await retryInterviewReport(lastCompletedInterviewId.value)
    startReportPolling(lastCompletedInterviewId.value)
    showToast('已重新排队生成报告')
  } catch (error) { showToast(error.message) }
}

async function openCompletedReport() {
  const reportId = reportStatus.value?.reportId
  if (!reportId) return
  await loadReportHistory()
  const row = reports.value.find((item) => item.id === reportId)
  if (row) await openReport(row)
}

function showToast(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 2600)
}

function navigate(destination) {
  if (destination === 'practice') {
    restoreExplicitReportReturn()
  } else {
    showReturnToCompletedResult.value = false
    clearExplicitReportReturn()
  }
  page.value = destination
  if (destination === 'practice') interviewStage.value = 'setup'
}

watch(page, (destination) => {
  clearInterval(reportHistoryPollTimer)
  reportHistoryPollTimer = undefined
  if (destination !== 'reports') return
  loadReportHistory().then(startReportHistoryPolling).catch((error) => showToast(error.message))
})

function startReportHistoryPolling() {
  clearInterval(reportHistoryPollTimer)
  if (page.value !== 'reports') return
  reportHistoryPollTimer = setInterval(() => {
    if (!reports.value.some((report) => ['PENDING', 'PROCESSING'].includes(report.reportTaskStatus))) {
      clearInterval(reportHistoryPollTimer)
      reportHistoryPollTimer = undefined
      return
    }
    loadReportHistory().catch((error) => showToast(error.message))
  }, 3000)
}

function explicitReportReturnKey() {
  const username = currentUserInfo.value?.username
  return username ? `interviewmirror.return-completed-report.${username}` : null
}

function restoreExplicitReportReturn() {
  const key = explicitReportReturnKey()
  if (!key) return
  try {
    const interviewId = sessionStorage.getItem(key)
    if (interviewId) {
      lastCompletedInterviewId.value = interviewId
      showReturnToCompletedResult.value = true
    }
  } catch { /* session storage may be disabled; in-memory state still works */ }
}

function clearExplicitReportReturn() {
  const key = explicitReportReturnKey()
  if (key) {
    try { sessionStorage.removeItem(key) } catch { /* in-memory state is still cleared */ }
  }
}

async function returnToLastCompletedInterview(interviewId = lastCompletedInterviewId.value) {
  if (!interviewId) return
  try {
    await refreshInterview(interviewId, { restore: true })
    lastCompletedInterviewId.value = interviewId
    reportStatus.value = await getInterviewReportStatus(interviewId)
    showReturnToCompletedResult.value = false
    clearExplicitReportReturn()
    reportPollingError.value = ''
    page.value = 'practice'
    if (['PENDING', 'PROCESSING'].includes(reportStatus.value.reportTask?.status)) {
      startReportPolling(interviewId)
    }
  } catch (error) { showToast(error.message || '无法恢复最近一次面试结果') }
}

function returnToInterviewSetup() {
  const interviewId = lastCompletedInterviewId.value
    || (activeInterview.value?.status === 'COMPLETE' ? activeInterview.value.id : null)
    || reportPollingInterviewId.value
  lastCompletedInterviewId.value = interviewId
  showReturnToCompletedResult.value = Boolean(interviewId)
  const key = explicitReportReturnKey()
  if (key && interviewId) {
    try { sessionStorage.setItem(key, interviewId) } catch { /* in-memory state still reveals the return button */ }
  }
  pendingInterviewId.value = null
  page.value = 'practice'
  interviewStage.value = 'setup'
}

async function retryHistoryReport(report) {
  if (!report.interviewId || report.reportTaskStatus !== 'FAILED') return
  try {
    await retryInterviewReport(report.interviewId)
    await loadReportHistory()
    startReportHistoryPolling()
    showToast('已重新排队生成报告')
  } catch (error) { showToast(error.message || '报告重试失败') }
}

async function retryIncompleteAssessment(report = currentReport.value) {
  const needsSummaryVerification = ['UNVERIFIED', 'EXTRACTIVE_FALLBACK'].includes(report?.overallReviewEvidenceStatus)
  if (!report?.interviewId || (!needsSummaryVerification && !report.hasUnassessedDimensions)) return
  try {
    await retryInterviewReport(report.interviewId)
    reports.value = reports.value.map((item) => item.interviewId === report.interviewId
      ? { ...item, reportTaskStatus: 'PENDING', overallReviewEvidenceStatus: needsSummaryVerification ? 'UNVERIFIED' : item.overallReviewEvidenceStatus }
      : item)
    if (report.id && reportDetails.value[report.id]) {
      reportDetails.value = { ...reportDetails.value, [report.id]: { ...reportDetails.value[report.id], reportTaskStatus: 'PENDING' } }
    }
    page.value = 'reports'
    await loadReportHistory()
    startReportHistoryPolling()
    showToast('报告已重新进入评估队列，可在历史报告中查看进度')
  } catch (error) { showToast(error.message || '报告重新评估失败') }
}

async function deleteReportHistory(report) {
  const interviewId = report?.interviewId
  if (!interviewId || deletingReportInterviews.value.has(interviewId)) return
  if (!window.confirm(`删除“${report.title}”？这会同时删除本场问答记录、报告及其 PDF，且无法恢复。`)) return

  deletingReportInterviews.value = new Set(deletingReportInterviews.value).add(interviewId)
  try {
    await deleteInterviewReport(interviewId)
    reportHistoryRevision += 1
    const reportId = report.id || reports.value.find((item) => item.interviewId === interviewId)?.id
    reports.value = reports.value.filter((item) => item.interviewId !== interviewId)
    if (reportId) {
      const nextDetails = { ...reportDetails.value }
      delete nextDetails[reportId]
      reportDetails.value = nextDetails
      if (selectedReportId.value === reportId) selectedReportId.value = reports.value.find((item) => item.id)?.id || null
    }
    if (lastCompletedInterviewId.value === interviewId) {
      lastCompletedInterviewId.value = null
      showReturnToCompletedResult.value = false
      clearExplicitReportReturn()
    }
    if (activeInterview.value?.id === interviewId) {
      activeInterview.value = null
      turns.value = []
      messages.value = []
      reportStatus.value = null
      answerText.value = ''
      interviewStage.value = 'setup'
    }
    if (reportPollingInterviewId.value === interviewId) {
      clearInterval(reportPollTimer)
      reportPollTimer = undefined
      reportPollingInterviewId.value = null
    }
    if (page.value === 'reportDetail') page.value = 'reports'
    const pendingHistoryRequest = reportHistoryRequest
    if (pendingHistoryRequest) await pendingHistoryRequest.catch(() => {})
    await loadReportHistory()
    showToast('报告和关联面试记录已删除')
  } catch (error) {
    showToast(error.message || '报告删除失败，请稍后重试')
  } finally {
    const remaining = new Set(deletingReportInterviews.value)
    remaining.delete(interviewId)
    deletingReportInterviews.value = remaining
  }
}

async function openReport(report) {
  if (['PENDING', 'PROCESSING'].includes(report.reportTaskStatus)) {
    showToast('报告正在生成，请在历史报告中查看进度')
    return
  }
  selectedReportId.value = report.id
  page.value = 'reportDetail'
  reportBusy.value = true
  try {
    const detail = await apiGetReport(report.id)
    const mapped = mapReportSummary(detail)
    reportDetails.value = { ...reportDetails.value, [report.id]: mapped }
    reports.value = reports.value.map((item) => item.id === report.id ? { ...item, ...mapped } : item)
  } catch (error) { showToast(error.message || '报告加载失败') }
  finally { reportBusy.value = false }
}

function restartReport(report) {
  mode.value = report.modeCode === 'COMPREHENSIVE' || report.mode === '综合面试' ? 'COMPREHENSIVE' : 'QUESTION_BANK'
  const resume = resumes.value.find((item) => item.id === report.resumeId && item.ready)
    ?? resumes.value.find((item) => item.ready && item.default)
    ?? resumes.value.find((item) => item.ready)
  const bank = banks.value.find((item) => item.id === report.bankId && item.ready)
    ?? banks.value.find((item) => item.ready)
  selectedResumeId.value = resume?.id ?? ''
  selectedBankId.value = bank?.id ?? ''
  jdText.value = mode.value === 'COMPREHENSIVE' ? report.jdText ?? '' : ''
  navigate('practice')
  if (mode.value === 'COMPREHENSIVE' && !resume) showToast('原简历当前不可用，请先选择或确认一份简历。')
  if (mode.value === 'QUESTION_BANK' && !bank) showToast('原题库当前不可用，请先选择或确认一份题库。')
}

function selectMode(nextMode) {
  mode.value = nextMode
}

async function beginInterview() {
  if (!modelDataConsent.value) return showToast('请先确认面试资料将发送至所选模型服务处理')
  isThinking.value = true
  if (mode.value === 'COMPREHENSIVE') {
    const resume = resumes.value.find((item) => item.id === selectedResumeId.value && item.ready)
    if (!resume) { isThinking.value = false; return showToast('请先选择一份已确认的简历') }
    if (jdText.value.trim().length > 1500) { isThinking.value = false; return showToast('JD 请控制在 1500 字以内') }
    try { await validateInterviewSource('RESUME', resume.id) } catch (error) { isThinking.value = false; return showToast(error.message) }
    reportTitleDraft.value = jdText.value.trim().slice(0, 26) || 'AI 应用 / AI 全栈实习'
  } else {
    const bank = banks.value.find((item) => item.id === selectedBankId.value && item.ready)
    if (!bank) { isThinking.value = false; return showToast('请先选择一份已确认的题库') }
    try { await validateInterviewSource('QUESTION_BANK', bank.id) } catch (error) { isThinking.value = false; return showToast(error.message) }
    reportTitleDraft.value = bank.name
  }
  try {
    if (!pendingInterviewId.value) {
      pendingAnswerRequest.value = null
      pendingReplaceRequest.value = null
      pendingEndRequest.value = null
      const createBody = {
        schemaVersion: '1.1.0', clientRequestId: crypto.randomUUID(), mode: mode.value,
        locale: 'zh-CN', modelDataConsent: true,
        ...(mode.value === 'COMPREHENSIVE'
          ? { resumeId: selectedResumeId.value, ...(jdText.value.trim() ? { jdText: jdText.value.trim() } : {}) }
          : { questionBankId: selectedBankId.value }),
      }
      const created = await createInterview(createBody)
      pendingInterviewId.value = created.id
    }
    const started = await startInterview(pendingInterviewId.value)
    showReturnToCompletedResult.value = false
    clearExplicitReportReturn()
    activeInterview.value = started
    await refreshInterview(started.id)
    pendingInterviewId.value = null
    answerText.value = ''
    interviewStage.value = 'active'
    page.value = 'practice'
    connectInterviewEvents(started.id)
  } catch (error) {
    showToast(error.message || '面试启动失败；配置已保留，可重试开始')
  } finally {
    isThinking.value = false
  }
}

async function submitAnswer() {
  const answer = answerText.value.trim()
  if (!answer || isThinking.value || !activeInterview.value || !activeTurn.value) return
  pendingAnswerRequest.value ??= { clientRequestId: crypto.randomUUID(), turnId: activeTurn.value.id, answer }
  answerScrollTarget.value = { interviewId: activeInterview.value.id, turnId: pendingAnswerRequest.value.turnId }
  isThinking.value = true
  try {
    await answerInterview(activeInterview.value.id, pendingAnswerRequest.value.turnId,
      pendingAnswerRequest.value.clientRequestId, pendingAnswerRequest.value.answer)
    pendingAnswerRequest.value = null
    answerText.value = ''
    await refreshInterview(activeInterview.value.id)
    if (activeInterview.value.transitionPending) showToast('回答已安全保存，面试官正在准备下一题')
  } catch (error) {
    showToast(`${error.message} 可重试提交，系统会识别重复请求。`)
  } finally {
    isThinking.value = false
  }
}

async function finishInterview() {
  if (!activeInterview.value) { interviewStage.value = 'setup'; return }
  if (activeInterview.value.status !== 'COMPLETE') {
    isThinking.value = true
    try {
      pendingEndRequest.value ??= crypto.randomUUID()
      await endInterview(activeInterview.value.id, pendingEndRequest.value)
      pendingEndRequest.value = null
      await refreshInterview(activeInterview.value.id)
    } catch (error) {
      showToast(error.message || '结束面试失败，请刷新后重试')
      await refreshInterview(activeInterview.value.id).catch(() => {})
      return
    } finally { isThinking.value = false }
  }
  closeInterviewEvents()
  lastCompletedInterviewId.value = activeInterview.value.id
  showReturnToCompletedResult.value = false
  reportPollingError.value = ''
  interviewStage.value = 'complete'
  loadReportHistory().catch((error) => showToast(error.message || '面试已保存，但历史报告列表暂时未刷新。'))
  startReportPolling(activeInterview.value.id)
}

async function exportPdf() {
  if (!currentReport.value?.id) return showToast('报告尚未生成，暂时不能导出 PDF。')
  try {
    const blob = await downloadReportPdf(currentReport.value.id)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `interviewmirror-report-${currentReport.value.id}.pdf`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) { showToast(error.message) }
}

function openResumePicker() {
  resumeInput.value?.click()
}

async function onResumeSelected(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (file.size > 20 * 1024 * 1024) return showToast('文件不能超过 20MB')
  try {
    const document = await createDocument('RESUME', file)
    resumes.value.unshift(remoteItem(document))
    selectedResumeId.value = document.id
    page.value = 'resumes'
    showToast('简历已上传，正在排队解析')
    pollDocument('RESUME', document.id)
  } catch (error) { showToast(error.message) }
}

function openBankPicker() {
  bankInput.value?.click()
}

async function onBankSelected(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (file.size > 20 * 1024 * 1024) return showToast('文件不能超过 20MB')
  try {
    const document = await createDocument('QUESTION_BANK', file)
    banks.value.unshift(remoteItem(document))
    selectedBankId.value = document.id
    page.value = 'banks'
    showToast('题库已上传，正在排队解析')
    pollDocument('QUESTION_BANK', document.id)
  } catch (error) { showToast(error.message) }
}

function replaceDocument(type, document) {
  const collection = type === 'RESUME' ? resumes : banks
  const mapped = remoteItem(document)
  const index = collection.value.findIndex((item) => item.id === document.id)
  if (index < 0) collection.value.unshift(mapped)
  else collection.value.splice(index, 1, mapped)
}

function pollDocument(type, id) {
  if (parseTimers.has(id)) return
  const poll = async () => {
    try {
      const document = await getDocument(type, id)
      replaceDocument(type, document)
      if (['PENDING', 'PROCESSING'].includes(document.status)) {
        parseTimers.set(id, setTimeout(poll, 1800))
        return
      }
      parseTimers.delete(id)
      if (document.status === 'PARSED') {
        if (type === 'RESUME') showToast('简历解析完成，可查看内容并确认使用')
        else {
          showToast('题库解析完成，请检查并确认资料')
          openDocumentReview(type, document)
        }
      } else if (document.status === 'FAILED') {
        showToast(document.parseTask?.errorMessage || '解析失败，可重新解析')
      }
    } catch (error) {
      parseTimers.delete(id)
      showToast(error.message)
    }
  }
  parseTimers.set(id, setTimeout(poll, 1200))
}

function openDocumentReview(type, document) {
  if (type === 'RESUME' || type === 'resume') return showToast('简历内容采用只读预览，请从简历卡片打开')
  if (!canReviewDocument(document)) return showToast(documentStatusLabel(document.status))
  const content = structuredClone(document.content || {})
  pendingReview.value = {
    id: document.id, type: 'bank', name: document.title, fileId: document.fileId,
    contentVersion: document.contentVersion, content,
    questions: Array.isArray(content.questions) ? content.questions.map((item, index) => ({
      position: index + 1, stem: item.stem || '', answer: item.answer || '', category: item.category || '',
    })) : [],
  }
  reviewDialog.value = true
}

async function openDocument(type, document) {
  try {
    const detail = await getDocument(type, document.id)
    replaceDocument(type, detail)
    openDocumentReview(type, detail)
  } catch (error) { showToast(error.message) }
}

async function openResumeDetails(resume) {
  if (!['PARSED', 'CONFIRMED'].includes(resume.status)) return showToast(documentStatusLabel(resume.status))
  try {
    const detail = await getDocument('RESUME', resume.id)
    replaceDocument('RESUME', detail)
    selectedResumeDetailId.value = detail.id
    page.value = 'resumeDetail'
  } catch (error) { showToast(error.message) }
}

async function confirmResume(resume) {
  try {
    const confirmed = await confirmDocument('RESUME', resume.id)
    replaceDocument('RESUME', confirmed)
    selectedResumeId.value = resume.id
    showToast('简历已确认，可用于综合面试')
  } catch (error) { showToast(error.message) }
}

async function createBlankBank() {
  try {
    const document = await createQuestionBank('我的新题库')
    banks.value.unshift(remoteItem(document))
    selectedBankId.value = document.id
    openDocumentReview('QUESTION_BANK', document)
  } catch (error) { showToast(error.message) }
}

async function retryParsing(type, document) {
  try {
    const restarted = await retryDocument(type, document.id)
    replaceDocument(type, restarted)
    showToast('已重新排队解析')
    pollDocument(type, document.id)
  } catch (error) { showToast(error.message) }
}

function addQuestion() {
  pendingReview.value?.questions.push({ position: pendingReview.value.questions.length + 1, stem: '', answer: '', category: '' })
}

function removeQuestion(index) {
  pendingReview.value?.questions.splice(index, 1)
}

function buildReviewedContent(draft) {
  const questions = draft.questions.map((item, index) => ({
    position: index + 1, stem: item.stem.trim(), answer: item.answer.trim(),
    ...(item.category.trim() ? { category: item.category.trim() } : {}),
  })).filter((item) => item.stem)
  return { schemaVersion: 'interviewmirror.question-bank-content.v1', questions }
}

async function saveReviewedDocument(alsoConfirm = false) {
  const draft = pendingReview.value
  if (!draft) return
  const type = 'QUESTION_BANK'
  try {
    const saved = await updateDocument(type, draft.id, {
      title: draft.name,
      contentVersion: draft.contentVersion,
      content: buildReviewedContent(draft),
    })
    let finalDocument = saved
    if (alsoConfirm) finalDocument = await confirmDocument(type, draft.id)
    replaceDocument(type, finalDocument)
    if (alsoConfirm) {
      pendingReview.value = null
      reviewDialog.value = false
      showToast('资料已确认，可用于面试')
    } else {
      openDocumentReview(type, saved)
      showToast('修改已保存；请再次确认后用于面试')
    }
  } catch (error) { showToast(error.message) }
}

async function confirmReview() {
  await saveReviewedDocument(true)
}

async function cancelReview() {
  pendingReview.value = null
  reviewDialog.value = false
}

async function removeOwnedResource(collection, resource) {
  try {
    await deleteDocument(collection === resumes ? 'RESUME' : 'QUESTION_BANK', resource.id)
    collection.value = collection.value.filter((item) => item.id !== resource.id)
    if (selectedResumeId.value === resource.id) selectedResumeId.value = resumes.value[0]?.id ?? ''
    if (selectedBankId.value === resource.id) selectedBankId.value = banks.value[0]?.id ?? ''
    showToast('资料和关联文件已删除')
  } catch (error) { showToast(error.message) }
}

async function deleteResume(resume) { await removeOwnedResource(resumes, resume) }

async function setDefaultResume(resume) {
  selectedResumeId.value = resume.id
  showToast('已选择本次综合面试使用的简历')
}

async function deleteBank(bank) {
  await removeOwnedResource(banks, bank)
}

async function downloadOwnedFile(fileId, filename) {
  try {
    const blob = await downloadFile(fileId)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename || 'interviewmirror-upload'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) { showToast(error.message) }
}

function useBankForPractice(bank) {
  selectedBankId.value = bank.id
  mode.value = 'QUESTION_BANK'
  page.value = 'practice'
  interviewStage.value = 'setup'
}

function formatSize(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function resetDemo() {
  loadReportHistory().then(() => showToast('已刷新本地面试报告')).catch((error) => showToast(error.message))
}
</script>

<template>
  <section v-if="!authReady" class="auth-screen"><div class="auth-card"><div class="brand-mark"><span></span></div><h1>正在连接本地服务</h1><p>正在检查登录状态与私有资料空间…</p></div></section>
  <section v-else-if="!currentUserInfo" class="auth-screen">
    <form class="auth-card" @submit.prevent="submitLogin">
      <div class="auth-brand"><span class="brand-mark"><span></span></span><span><strong>面镜</strong><small>InterviewMirror</small></span></div>
      <span class="section-kicker">LOCAL PRACTICE SPACE</span>
      <h1>登录你的练习空间</h1>
      <p>简历、题库和文件会隔离保存在你的本地账号下。</p>
      <label class="field-label" for="login-username">用户名或邮箱</label>
      <input id="login-username" v-model="loginForm.identifier" class="text-field auth-input" autocomplete="username" required maxlength="160" />
      <label class="field-label" for="login-password">密码</label>
      <input id="login-password" v-model="loginForm.password" class="text-field auth-input" type="password" autocomplete="current-password" required maxlength="200" />
      <p v-if="loginError" class="auth-error" role="alert">{{ loginError }}</p>
      <button class="primary-button auth-submit" type="submit" :disabled="loginBusy">{{ loginBusy ? '正在登录…' : '登录' }} <span>→</span></button>
      <small class="auth-hint">本地演示账号：demo1 / MirrorDemo1!　·　demo2 / MirrorDemo2!</small>
    </form>
  </section>
  <div v-else class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="#home" @click.prevent="navigate('home')">
        <span class="brand-mark"><span></span></span>
        <span class="brand-copy"><strong>面镜</strong><small>InterviewMirror</small></span>
      </a>

      <div class="workspace-label">练习空间</div>
      <nav class="primary-nav" aria-label="主导航">
        <button v-for="item in navItems" :key="item.id" class="nav-item" :class="{ active: selectedNav === item.id }" @click="navigate(item.id)">
          <svg v-if="item.icon === 'home'" viewBox="0 0 24 24" aria-hidden="true"><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z" /></svg>
          <svg v-else-if="item.icon === 'mic'" viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="3" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3m-4 0h8" /></svg>
          <svg v-else-if="item.icon === 'report'" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 3h8l4 4v14H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z"/><path d="M14 3v5h5M9 12h6m-6 4h6" /></svg>
          <svg v-else-if="item.icon === 'bank'" viewBox="0 0 24 24" aria-hidden="true"><path d="m3 9 9-6 9 6M5 10v9m5-9v9m4-9v9m5-9v9M3 21h18M2 9h20" /></svg>
          <svg v-else viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 8h6m-6 4h6m-6 4h4" /></svg>
          <span>{{ item.label }}</span>
          <span v-if="item.id === 'reports'" class="nav-count">{{ reports.length }}</span>
        </button>
      </nav>

      <div class="sidebar-bottom">
        <div class="local-card">
          <span class="local-pulse"></span>
        <div><strong>本地私有空间</strong><small>文件经账号校验后访问</small></div>
      </div>
        <button class="profile-button" @click="signOut" title="退出登录">
          <span class="avatar">{{ currentUserInfo.displayName.slice(0, 1) }}</span>
          <span class="profile-copy"><strong>{{ currentUserInfo.displayName }}</strong><small>{{ currentUserInfo.username }} · 退出</small></span>
          <span class="more-dots">···</span>
        </button>
      </div>
    </aside>

    <main class="main-area">
      <header class="topbar">
        <div class="breadcrumbs"><span>面镜</span><span class="crumb-slash">/</span><strong>{{ pageHeading[0] }}</strong></div>
        <div class="topbar-actions">
          <span class="demo-pill"><span></span>交互演示</span>
          <button class="icon-button" title="重置演示数据" @click="resetDemo">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 12a9 9 0 1 0 2.64-6.36L3 8"/><path d="M3 3v5h5m4-1v5l3 2"/></svg>
          </button>
          <button class="help-button" @click="showToast(`已登录 ${currentUserInfo.username}，私有资料通过本地 API 保存`)" >{{ currentUserInfo.username }}</button>
        </div>
      </header>

      <div class="page-content">
        <template v-if="page === 'home'">
          <section class="hero-card">
            <div class="hero-copy">
              <span class="eyebrow"><span class="sparkle">✦</span> 给下一场面试一点准备时间</span>
              <h1>练得更清楚，<br /><span>表达就更有底气。</span></h1>
              <p>把简历、岗位要求和真实问题变成一次有反馈的练习。每一次回答，都能成为下一次进步的线索。</p>
              <button class="primary-button hero-button" @click="navigate('practice')">开始模拟面试 <span>→</span></button>
              <div class="hero-proof"><span class="proof-icon">✓</span>AI 追问 · 证据复盘 · 本地演示</div>
            </div>
            <div class="hero-visual" aria-hidden="true">
              <div class="sun-orbit orbit-one"></div><div class="sun-orbit orbit-two"></div>
              <div class="hero-sun"><span>镜</span></div>
              <div class="mini-chat chat-top"><span class="mini-chat-dot"></span><div><small>AI 面试官</small><strong>你是如何评估项目效果的？</strong></div></div>
              <div class="mini-chat chat-bottom"><span class="mini-avatar">林</span><div><small>你的回答</small><strong>我先建立了固定的问题集…</strong></div></div>
              <div class="visual-note"><span>✦</span> 一次只进步一点点</div>
            </div>
          </section>

          <section class="section-block">
            <div class="section-heading"><div><span class="section-kicker">PRACTICE</span><h2>选择一种练习方式</h2><p>从岗位综合模拟开始，或围绕自己的题库集中突破。</p></div><button class="text-button" @click="navigate('practice')">全部面试方式 <span>→</span></button></div>
            <div class="practice-cards">
              <button class="practice-card comprehensive-card" @click="selectMode('COMPREHENSIVE'); navigate('practice')">
                <span class="card-icon icon-yellow"><svg viewBox="0 0 24 24"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z"/><path d="M8 7h8m-8 4h5"/></svg></span>
                <span class="card-label">推荐练习</span><strong>综合面试</strong><span class="card-description">结合简历和目标岗位，进行贴近真实面试的多轮追问。</span>
                <span class="card-meta"><span>简历必选</span><span>JD 可选</span></span>
                <span class="card-arrow">开始练习 <b>→</b></span>
                <span class="card-decoration decoration-sun"></span>
              </button>
              <button class="practice-card bank-card" @click="selectMode('QUESTION_BANK'); navigate('practice')">
                <span class="card-icon icon-peach"><svg viewBox="0 0 24 24"><path d="m3 9 9-6 9 6M5 10v9m5-9v9m4-9v9m5-9v9M3 21h18M2 9h20"/></svg></span>
                <span class="card-label label-neutral">集中训练</span><strong>专项面试</strong><span class="card-description">从自定义题库逐题抽问，专注练习知识点与表达。</span>
                <span class="card-meta"><span>选择题库</span><span>动态追问</span><span>逐题复盘</span></span>
                <span class="card-arrow">进入题库练习 <b>→</b></span>
                <span class="card-decoration decoration-grid"></span>
              </button>
            </div>
          </section>

          <section class="overview-grid">
            <div class="overview-card progress-card">
              <div class="overview-head"><div><span class="section-kicker">YOUR PROGRESS</span><h3>练习概览</h3></div><span class="date-chip">本地记录</span></div>
              <div class="stat-row">
                <div class="stat-item"><strong>{{ reports.length }}</strong><span>已完成面试</span><small>持续积累中</small></div>
                <div class="stat-item"><strong>{{ averageScore }}<small class="stat-unit">分</small></strong><span>平均表现</span><small>基于历史报告</small></div>
                <div class="stat-item"><strong>{{ comprehensiveCount }}</strong><span>综合面试</span><small>含岗位要求</small></div>
              </div>
              <div class="progress-footer"><span class="progress-spark">✦</span><span>每次练习都会留下一条进步线索</span><button @click="navigate('reports')">查看报告 <b>→</b></button></div>
            </div>
            <div class="overview-card prep-card">
              <span class="prep-bubble">✦</span><span class="section-kicker">BEFORE YOU START</span><h3>先准备好这些</h3>
              <button class="prep-link" @click="navigate('resumes')"><span class="prep-number">01</span><span><strong>确认一份简历</strong><small>{{ resumes.length }} 份简历 · {{ resumes.filter((r) => r.ready).length }} 份可用</small></span><b>→</b></button>
              <button class="prep-link" @click="navigate('banks')"><span class="prep-number">02</span><span><strong>整理你的题库</strong><small>{{ banks.length }} 份题库 · 随时开始专项练习</small></span><b>→</b></button>
            </div>
          </section>

          <section class="section-block recent-block">
            <div class="section-heading compact-heading"><div><span class="section-kicker">RECENT REPORTS</span><h2>最近的练习</h2></div><button class="text-button" @click="navigate('reports')">查看全部 <span>→</span></button></div>
            <div v-if="reports.length" class="recent-list">
              <button v-for="report in reports.slice(0, 2)" :key="report.interviewId" class="recent-report" @click="report.id ? openReport(report) : navigate('reports')">
                <span class="recent-file"><svg viewBox="0 0 24 24"><path d="M7 3h8l4 4v14H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z"/><path d="M14 3v5h5M9 12h6m-6 4h6"/></svg></span>
                <span class="recent-main"><strong>{{ report.title }}</strong><small>{{ report.mode }} <i>·</i> {{ report.date }} <i>·</i> {{ reportTaskLabel(report.reportTaskStatus, Boolean(report.id)) }}</small></span>

                <span class="score-mini"><small>综合表现</small><strong v-if="report.overallScore != null">{{ report.overallScore }}<small>分</small></strong><strong v-else>—</strong></span>
                <span v-if="report.id" class="row-arrow">→</span><span v-else class="report-wait-mark">···</span>
              </button>
            </div>
            <div v-else class="empty-state"><span>✦</span><strong>还没有面试记录</strong><p>完成第一场练习后，报告会出现在这里。</p></div>
          </section>
          <footer class="page-footer"><span>面镜 InterviewMirror</span><span>练习为成长服务，评价仅作自我复盘参考</span></footer>
        </template>

        <template v-else-if="page === 'practice' && interviewStage === 'setup'">
          <div class="page-intro"><div><span class="section-kicker">PRACTICE ROOM</span><h1>{{ pageHeading[0] }}</h1><p>{{ pageHeading[1] }}</p></div><span class="intro-illustration">✦</span></div>
          <div v-if="showReturnToCompletedResult && lastCompletedInterviewId" class="return-completed-banner"><span>◷</span><div><strong>本场面试结果已保存</strong><small>可以返回查看本场问答记录和报告生成状态。</small></div><button class="return-result-button" @click="returnToLastCompletedInterview(lastCompletedInterviewId)">返回面试结果 →</button></div>
          <div class="mode-switch" role="tablist" aria-label="面试模式">
            <button :class="{ selected: mode === 'COMPREHENSIVE' }" @click="selectMode('COMPREHENSIVE')"><span class="switch-icon">◉</span><span><strong>综合面试</strong><small>结合简历和岗位目标</small></span></button>
            <button :class="{ selected: mode === 'QUESTION_BANK' }" @click="selectMode('QUESTION_BANK')"><span class="switch-icon switch-peach">▤</span><span><strong>专项面试</strong><small>使用自定义题库练习</small></span></button>
          </div>

          <div class="setup-layout">
            <section class="panel setup-form">
              <div class="panel-heading"><div><span class="section-kicker">INTERVIEW SETUP</span><h2>{{ mode === 'COMPREHENSIVE' ? '设置综合面试' : '设置专项面试' }}</h2></div><span class="step-indicator">1 <i>/</i> 2</span></div>
              <template v-if="mode === 'COMPREHENSIVE'">
                <label class="field-label">选择简历 <span class="required-star">*</span></label>
                <div class="resume-select-list">
                  <label v-for="resume in resumes.filter((item) => item.ready)" :key="resume.id" class="select-option" :class="{ chosen: selectedResumeId === resume.id }">
                    <input v-model="selectedResumeId" type="radio" :value="resume.id" />
                    <span class="document-icon"><svg viewBox="0 0 24 24"><path d="M7 3h8l4 4v14H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z"/><path d="M14 3v5h5M9 12h6m-6 4h5"/></svg></span>
                    <span class="option-copy"><strong>{{ resume.name }}</strong><small>{{ resume.updated }} 更新 · {{ resume.projects }} 段项目经历</small></span>
                    <span v-if="resume.default" class="default-tag">默认</span><span class="radio-custom"></span>
                  </label>
                  <button class="add-inline" @click="openResumePicker">＋ 上传新简历</button>
                  <p v-if="!resumes.some((item) => item.ready)" class="inline-hint error-hint">请先在“我的简历”上传并确认一份简历。</p>
                </div>
                <label class="field-label jd-label" for="jd-input">目标岗位 JD <span class="optional-tag">选填</span></label>
                <textarea id="jd-input" v-model="jdText" class="text-field jd-field" maxlength="1500" placeholder="粘贴岗位职责与任职要求…\n\n填写 JD 后，面试问题会参考目标岗位要求。"></textarea>
                <div class="field-footnote"><span>最多 1500 字</span><span>{{ jdText.length }} / 1500</span></div>
              </template>
              <template v-else>
                <label class="field-label">选择自定义题库 <span class="required-star">*</span></label>
                <div class="bank-select-list">
                  <label v-for="bank in banks.filter((item) => item.ready)" :key="bank.id" class="select-option bank-option" :class="{ chosen: selectedBankId === bank.id }">
                    <input v-model="selectedBankId" type="radio" :value="bank.id" />
                    <span class="document-icon bank-document"><svg viewBox="0 0 24 24"><path d="m3 9 9-6 9 6M5 10v9m5-9v9m4-9v9m5-9v9M3 21h18M2 9h20"/></svg></span>
                    <span class="option-copy"><strong>{{ bank.name }}</strong><small>{{ bank.questions }} 道题 · {{ bank.format }} · {{ bank.updated }} 更新</small></span>
                    <span class="radio-custom"></span>
                  </label>
                  <button class="add-inline" @click="navigate('banks')">＋ 管理我的题库</button>
                  <p v-if="!banks.some((item) => item.ready)" class="inline-hint error-hint">请先上传并确认一份题库。</p>
                </div>
                <div class="bank-mode-note"><span class="note-icon">✦</span><div><strong>专注练习，不依赖简历</strong><p>AI 会围绕题库原题继续追问。结束后可查看本场问题与回答记录；评分报告尚未接入。</p></div></div>
              </template>

              <label class="model-consent"><input v-model="modelDataConsent" type="checkbox" /><span>我同意将本场所选简历或题库内容、回答发送至配置的模型服务，以生成问题和追问。面试回答会保存在本地应用中。</span></label>
              <div class="privacy-note"><span>◇</span><span>模型服务未配置时，面试无法启动。题库专项不会读取简历，综合面试仅使用已确认简历和可选 JD。</span></div>

              <div class="form-actions"><button class="subtle-button" @click="navigate('home')">返回</button><button class="primary-button" :disabled="isThinking" @click="beginInterview">{{ isThinking ? '正在准备问题…' : '开始面试' }} <span>→</span></button></div>
            </section>
            <aside class="setup-aside">
              <div class="panel expectation-card"><span class="aside-spark">✦</span><span class="section-kicker">WHAT TO EXPECT</span><h3>这场练习会这样进行</h3>
                <div class="expect-step"><span>01</span><div><strong>逐题作答</strong><small>像真实面试一样，一次回答一个问题。</small></div></div>
                <div class="expect-step"><span>02</span><div><strong>根据回答追问</strong><small>回答不够具体时，面试官会继续深入。</small></div></div>
                <div class="expect-step"><span>03</span><div><strong>结束后回看记录</strong><small>查看本场已保存的问题与回答；AI 评分报告和改进建议尚未接入。</small></div></div>
                <div class="duration-chip"><span>◷</span> 建议预留 20–30 分钟</div>
              </div>
              <div class="aside-tip"><span class="tip-star">✦</span><p>不知道怎么回答也没关系。先说出你的思路，结束后可以回看本场问答记录。</p></div>
            </aside>
          </div>
        </template>

        <template v-else-if="page === 'practice' && interviewStage === 'active'">
          <section class="interview-topline"><button class="back-link" :disabled="isThinking || activeInterview?.status === 'COMPLETING'" @click="finishInterview">← 结束面试</button><span class="live-label"><span></span>{{ interviewLiveLabel }}</span><span class="mode-chip">{{ mode === 'COMPREHENSIVE' ? '综合面试' : '题库专项' }}</span></section>
          <section class="interview-layout">
            <div class="interview-main panel">
              <div class="interview-header"><div><span class="section-kicker">AI INTERVIEWER</span><h1>{{ reportTitleDraft }}</h1><p>{{ mode === 'COMPREHENSIVE' ? '面试官会根据你的回答继续追问。' : `围绕「${banks.find((bank) => bank.id === selectedBankId)?.name ?? '自定义题库'}」进行练习。` }}</p></div><div class="progress-ring"><span>{{ interviewProgress.current }}</span><small>/ {{ interviewProgress.target }}</small></div></div>
              <div class="question-progress"><span :style="{ width: `${Math.max(8, (interviewProgress.current / interviewProgress.target) * 100)}%` }"></span></div>
              <div ref="chatTimeline" class="chat-timeline">
                <div class="timeline-date">今天 · 面试开始</div>
                 <div v-for="(message, index) in messages" :key="index" class="chat-message" :class="message.role" :data-active-turn="message.role === 'assistant' && message.turnId === activeTurn?.id ? 'true' : null">
                  <span class="chat-avatar" :class="message.role === 'assistant' ? 'ai-avatar' : 'user-avatar'">{{ message.role === 'assistant' ? '镜' : '林' }}</span>
                  <div class="message-body"><div class="message-meta"><strong>{{ message.role === 'assistant' ? 'AI 面试官' : '我' }}</strong><small>{{ message.time }}</small></div><div class="message-bubble">{{ message.content }}</div></div>
                </div>
                <div v-if="isThinking || activeInterview?.transitionPending" class="chat-message assistant"><span class="chat-avatar ai-avatar">镜</span><div class="message-body"><div class="message-meta"><strong>AI 面试官</strong><small>正在思考</small></div><div class="typing-bubble"><i></i><i></i><i></i></div></div></div>
              </div>
              <div class="answer-box"><textarea v-model="answerText" :disabled="isThinking || activeInterview?.status !== 'RUNNING' || activeInterview?.transitionPending || activeTurn?.status !== 'ASKED'" placeholder="输入你的回答…（Enter 发送，Shift + Enter 换行）" @keydown.enter.exact.prevent="submitAnswer"></textarea><div class="answer-controls"><span>{{ pendingAnswerRequest ? '回答请求已保留，可安全重试' : '尽量结合具体经历和结果回答' }}</span><button class="send-button" :disabled="!answerText.trim() || isThinking || activeInterview?.status !== 'RUNNING' || activeInterview?.transitionPending || activeTurn?.status !== 'ASKED'" @click="submitAnswer">{{ pendingAnswerRequest ? '重试提交' : '发送回答' }} <span>↑</span></button></div><button v-if="activeTurn?.type === 'MAIN' && activeTurn?.status === 'ASKED' && activeInterview?.replacementAvailable" class="subtle-button replace-question" :disabled="isThinking || activeInterview?.status !== 'RUNNING'" @click="replaceCurrentQuestion">{{ pendingReplaceRequest ? '重试换题' : '换一道题' }}</button></div>
            </div>
            <aside class="interview-aside"><div class="panel session-card"><span class="section-kicker">SESSION GUIDE</span><h3>保持你的节奏</h3><div class="session-stat"><span>当前进度</span><strong>问题 {{ interviewProgress.current }} <small>/ {{ interviewProgress.target }}</small></strong></div><div class="session-stat"><span>追问方式</span><strong>根据回答动态深入</strong></div><div class="session-separator"></div><p><span>✦</span> 不需要追求完美答案。先讲清你的判断和经历。</p><button class="end-session" :disabled="isThinking || activeInterview?.status === 'COMPLETING'" @click="finishInterview">{{ activeInterview?.status === 'COMPLETING' ? '正在安全结束' : '结束本次面试' }}</button></div>
              <div class="panel interview-context"><span class="section-kicker">本场资料</span><div v-if="mode === 'COMPREHENSIVE'" class="context-file"><span class="document-icon"><svg viewBox="0 0 24 24"><path d="M7 3h8l4 4v14H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z"/><path d="M14 3v5h5"/></svg></span><div><strong>{{ resumes.find((resume) => resume.id === selectedResumeId)?.name }}</strong><small>简历已确认</small></div></div><div v-if="jdText.trim()" class="context-jd"><strong>目标 JD</strong><p>{{ jdText.slice(0, 120) }}{{ jdText.length > 120 ? '…' : '' }}</p></div><div v-if="mode === 'QUESTION_BANK'" class="context-file"><span class="document-icon bank-document"><svg viewBox="0 0 24 24"><path d="m3 9 9-6 9 6M5 10v9m5-9v9m4-9v9m5-9v9M3 21h18M2 9h20"/></svg></span><div><strong>{{ banks.find((bank) => bank.id === selectedBankId)?.name }}</strong><small>{{ banks.find((bank) => bank.id === selectedBankId)?.questions }} 道已确认题目</small></div></div></div></aside>
          </section>
        </template>

        <template v-else-if="page === 'practice' && interviewStage === 'complete'">
          <div class="page-intro"><div><span class="section-kicker">INTERVIEW COMPLETE</span><h1>本场面试已结束</h1><p>{{ reportTitleDraft }} · {{ mode === 'COMPREHENSIVE' ? '综合面试' : '题库专项' }}</p></div><span class="intro-illustration">✓</span></div>
          <section class="panel completion-panel"><h2>本场面试已保存</h2><p>历史报告会为本场单独保留；生成完成后才能打开查看。</p><div class="report-generation-status" role="status"><strong>{{ reportStatusLabel }}</strong><span v-if="reportPollingError" class="report-status-error">{{ reportPollingError }}</span><span v-else-if="reportStatus?.reportTask?.status === 'FAILED'" class="report-status-error">{{ reportStatus.reportTask.errorMessage || '报告生成失败，可以重试。' }}</span><span v-else-if="reportStatus?.reportTask?.status === 'SUCCESS'">报告已保存到历史报告。</span><span v-else-if="reportStatus?.reportTask?.status === 'PROCESSING'">模型正在分析本场回答；耗时可能因模型服务响应而变化。</span><span v-else>报告任务等待后台 Worker 处理；你可以留在此页等待，或之后到历史报告查看进度。</span><i v-if="['PENDING', 'PROCESSING'].includes(reportStatus?.reportTask?.status)" class="report-progress-track completion-progress" role="progressbar" aria-label="报告生成进度"><b :class="reportStatus.reportTask.status.toLowerCase()"></b></i><div class="form-actions"><button v-if="reportPollingError" class="subtle-button" @click="startReportPolling(lastCompletedInterviewId)">重新检查</button><button v-if="reportStatus?.reportTask?.status === 'FAILED'" class="subtle-button" @click="retryCompletedReport">重试生成</button><button v-if="reportStatus?.reportId && reportStatus?.reportTask?.status === 'SUCCESS'" class="primary-button" @click="openCompletedReport">查看报告 →</button></div></div><div class="completion-turns"><article v-for="(turn, index) in turns" :key="turn.id" class="completion-turn"><strong>{{ index + 1 }}. {{ turn.type === 'FOLLOW_UP' ? '追问' : '问题' }}</strong><p>{{ turn.question }}</p><blockquote v-if="turn.answer">{{ turn.answer }}</blockquote><small v-else>本题未作答</small></article></div><div class="form-actions"><button class="subtle-button" @click="returnToInterviewSetup">返回面试设置</button><button class="primary-button" @click="navigate('home')">返回工作台</button></div></section>
        </template>

        <template v-else-if="page === 'reports'">
          <div class="page-intro"><div><span class="section-kicker">YOUR JOURNEY</span><h1>历史报告</h1><p>所有内容来自已完成面试及其保存的资料快照。</p></div><button class="primary-button" @click="navigate('practice')">＋ 新建面试</button></div>
          <div class="report-summary-strip"><div><span class="summary-icon">◷</span><span><small>已生成报告</small><strong>{{ generatedReportCount }} <small>份</small></strong></span></div><div><span class="summary-icon peach-summary">✦</span><span><small>平均表现</small><strong>{{ averageScore }} <small>分</small></strong></span></div></div>
          <section class="panel report-list-panel"><div class="list-panel-heading"><div><span class="section-kicker">ALL SESSIONS</span><h2>历史面试 <span>{{ reports.length }}</span></h2></div><button class="subtle-button" @click="loadReportHistory">刷新</button></div>
            <div v-if="reports.length" class="report-table">
              <div class="table-head"><span>面试与岗位</span><span>类型</span><span>时间</span><span>报告进度</span><span>综合评分</span><span>操作</span></div>
              <div v-for="report in reports" :key="report.interviewId" class="report-row">
                <button class="report-open-button report-name-cell" :disabled="!report.id || ['PENDING', 'PROCESSING'].includes(report.reportTaskStatus)" @click="openReport(report)"><span class="recent-file"><svg viewBox="0 0 24 24"><path d="M7 3h8l4 4v14H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z"/><path d="M14 3v5h5M9 12h6m-6 4h6"/></svg></span><span><strong>{{ report.title }}</strong><small>{{ report.questionCount }} 条回答 · {{ ['PENDING', 'PROCESSING'].includes(report.reportTaskStatus) ? '报告重新生成中' : report.reportStatus === 'PARTIAL' ? '部分覆盖' : report.id ? '报告已生成' : '报告生成中' }}</small></span></button>
                <span><i class="type-pill" :class="report.modeCode === 'COMPREHENSIVE' ? 'type-comprehensive' : 'type-special'">{{ report.mode }}</i></span>
                <span class="table-date">{{ report.date }}</span>
                <span class="report-progress-cell"><strong>{{ reportTaskLabel(report.reportTaskStatus, Boolean(report.id)) }}</strong><small v-if="report.reportTaskStatus === 'FAILED'">{{ report.reportErrorMessage || '可重试生成' }}</small><i v-if="['PENDING', 'PROCESSING'].includes(report.reportTaskStatus)" class="report-progress-track"><b :class="report.reportTaskStatus.toLowerCase()"></b></i></span>
                <span><strong v-if="report.overallScore != null" class="table-score">{{ report.overallScore }}<small>分</small></strong><small v-else class="na-label">{{ report.id ? '未评估' : '—' }}</small></span>
                <div class="report-row-actions"><button v-if="report.reportTaskStatus === 'FAILED'" class="report-retry-button" @click="retryHistoryReport(report)">重试</button><button v-else-if="report.reportTaskStatus === 'SUCCESS' && (report.overallReviewEvidenceStatus === 'UNVERIFIED' || report.hasUnassessedDimensions)" class="report-retry-button" @click="retryIncompleteAssessment(report)">重新评估</button><span v-else-if="report.id" class="row-arrow" aria-hidden="true">→</span><span v-else class="report-wait-mark" aria-hidden="true">···</span><button class="report-delete-button" :disabled="deletingReportInterviews.has(report.interviewId)" @click="deleteReportHistory(report)">{{ deletingReportInterviews.has(report.interviewId) ? '删除中' : '删除' }}</button></div>
              </div>
            </div>
            <div v-else class="empty-state"><span>✦</span><strong>完成一场练习后，报告会出现在这里</strong><button class="primary-button" @click="navigate('practice')">开始模拟面试</button></div>
          </section>
        </template>

        <template v-else-if="page === 'reportDetail' && currentReport">
          <div class="report-detail-top"><button class="back-link" @click="navigate('reports')">← 返回历史报告</button><div class="report-actions"><button class="report-delete-button" :disabled="deletingReportInterviews.has(currentReport.interviewId)" @click="deleteReportHistory(currentReport)">删除报告</button><button class="subtle-button" @click="restartReport(currentReport)">↻ 重新面试</button><button class="primary-button" @click="exportPdf">导出 PDF</button></div></div>
          <div v-if="reportBusy" class="panel"><p>正在加载已保存的报告和证据…</p></div>
          <template v-else>
            <section class="report-cover"><div><span class="section-kicker">INTERVIEW REPORT</span><div class="report-title-line"><h1>{{ currentReport.title }}</h1><span class="type-pill" :class="currentReport.modeCode === 'COMPREHENSIVE' ? 'type-comprehensive' : 'type-special'">{{ currentReport.mode }}</span></div><div class="report-meta"><span>◷ {{ currentReport.date }}</span><i>·</i><span>{{ currentReport.questionCount }} 条回答</span><i>·</i><span>{{ currentReport.reportStatus === 'PARTIAL' ? '部分覆盖' : '已完成' }}</span></div></div><div class="cover-score"><span>综合表现</span><strong v-if="currentReport.overallScore != null">{{ currentReport.overallScore }}</strong><strong v-else>—</strong><small v-if="currentReport.overallScore != null">/ 100</small><small v-else>未评估</small><div class="score-meter"><span :style="{ width: `${currentReport.overallScore ?? 0}%` }"></span></div><small class="score-caption">{{ currentReport.raw?.summary?.overallScoreStatus || 'UNASSESSED' }}</small></div></section>
            <section class="report-section report-evaluation"><div class="report-section-heading"><span class="section-number">01</span><div><span class="section-kicker">OVERALL REVIEW</span><h2>总体评价</h2></div></div><div v-if="currentReport.overallReviewEvidenceStatus === 'UNVERIFIED' || currentReport.hasUnassessedDimensions" class="report-verification-notice"><span>!</span><div class="report-verification-copy"><p v-if="currentReport.overallReviewEvidenceStatus === 'UNVERIFIED'">总体评价未通过自动证据核验，系统已隐藏未核实的总结。你可以重新评估报告。</p><p v-if="currentReport.hasUnassessedDimensions">部分适用能力维度尚未评估。重新评估会重新检查本场回答；只有存在直接相关回答证据时才会评分，证据不足的维度仍会保留为未评估。</p></div><button class="report-retry-button" :disabled="['PENDING', 'PROCESSING'].includes(currentReport.reportTaskStatus)" @click="retryIncompleteAssessment()">{{ ['PENDING', 'PROCESSING'].includes(currentReport.reportTaskStatus) ? '正在重新评估' : '重新评估报告' }}</button></div><div class="overall-note"><span class="quote-mark">“</span><p>{{ currentReport.overall }}</p></div><details v-if="currentReport.overallEvidence.length" class="report-evidence"><summary>查看总体评价证据</summary><blockquote v-for="(evidence, index) in currentReport.overallEvidence" :key="index">{{ evidence.quote }}<small>{{ evidence.sourceType }} · {{ evidence.sourceLocation }}</small></blockquote></details><div class="score-grid"><div v-for="item in currentReport.scores" :key="item.key" class="score-card"><div class="score-card-top"><span>{{ item.label }}</span><strong v-if="item.score != null">{{ item.score }}<small>/5</small></strong><strong v-else class="score-na">未评估</strong></div><div class="score-bar"><span :style="{ width: item.score == null ? '0%' : `${item.score * 20}%` }"></span></div><p>{{ item.note }}</p></div></div>
              <div class="report-radar-wrap"><div><h3>{{ currentReport.modeCode === 'QUESTION_BANK' ? '专项能力雷达图' : '六维能力雷达图' }}</h3><p>未评估维度保留为空，不按 0 分绘制。</p></div><svg viewBox="0 0 240 190" class="radar-chart" role="img" :aria-label="currentReport.modeCode === 'QUESTION_BANK' ? '专项面试能力雷达图' : '六维面试能力雷达图'"><polygon v-for="(grid, index) in reportRadar.gridPolygons" :key="`grid-${index}`" :points="grid" class="radar-grid"/><line v-for="point in reportRadar.points" :key="point.key" x1="120" y1="92" :x2="point.axisX" :y2="point.axisY" class="radar-axis"/><polygon v-if="reportRadar.polygon" :points="reportRadar.polygon" class="radar-area answer-area"/><circle v-for="point in reportRadar.points.filter((item) => item.score != null)" :key="`score-${point.key}`" :cx="point.x" :cy="point.y" r="3.5" class="radar-point"/><text v-for="point in reportRadar.points" :key="`label-${point.key}`" :x="point.labelX" :y="point.labelY" text-anchor="middle">{{ point.label }}</text></svg></div></section>

            <section class="report-section"><div class="report-section-heading"><span class="section-number">02</span><div><span class="section-kicker">QUESTION BY QUESTION</span><h2>问答逐题回顾</h2></div><span class="heading-side-note">{{ currentReport.turns.length }} 条回答</span></div><div class="turn-list"><article v-for="(turn, index) in currentReport.turns" :key="turn.id" class="turn-card"><div class="turn-heading"><span class="turn-number">Q{{ String(index + 1).padStart(2, '0') }}</span><span class="turn-topic">{{ turn.kind === 'FOLLOW_UP' ? '追问' : '主问题' }}</span></div><h3>{{ turn.question }}</h3><div class="answer-quote"><span>你的回答</span><p>“{{ turn.answer }}</p></div><div class="turn-feedback"><span>✦</span><p>{{ turn.note }}</p></div><div v-if="turn.strengths.length" class="evidence-list"><strong>回答亮点</strong><span v-for="item in turn.strengths" :key="item">{{ item }}</span></div><div v-if="turn.improvements.length" class="evidence-list"><strong>可改进</strong><span v-for="item in turn.improvements" :key="item">{{ item }}</span></div><details v-if="turn.evidence.length" class="report-evidence"><summary>查看证据引用</summary><blockquote v-for="(evidence, itemIndex) in turn.evidence" :key="itemIndex">{{ evidence.quote }}<small>{{ evidence.sourceType }} · {{ evidence.sourceLocation }}</small></blockquote></details></article><div v-if="!currentReport.turns.length" class="empty-state"><strong>本场没有已提交回答，能力维度均未评估。</strong></div></div></section>

            <section class="report-section strengths-grid-section"><div class="report-section-heading"><span class="section-number">03</span><div><span class="section-kicker">YOUR SIGNALS</span><h2>亮点与待提升</h2></div></div><div class="strengths-grid"><div class="strength-panel"><div class="strength-title"><span class="strength-icon">✦</span><h3>亮点与优势</h3></div><ul><li v-for="(item, index) in currentReport.strengths" :key="index">{{ item }}</li><li v-if="!currentReport.strengths.length">暂无可确认的优势结论。</li></ul></div><div class="weakness-panel"><div class="strength-title"><span class="weak-icon">↗</span><h3>待提升风险</h3></div><ul><li v-for="(item, index) in currentReport.weaknesses" :key="index">{{ item }}</li><li v-if="!currentReport.weaknesses.length">本场没有可确认的不足结论。</li></ul></div></div></section>
            <section class="report-section"><div class="report-section-heading"><span class="section-number">04</span><div><span class="section-kicker">NEXT PRACTICE</span><h2>改进建议与学习路径</h2></div></div><div class="suggestion-list"><div v-for="(item, index) in currentReport.suggestions" :key="index" class="suggestion-item"><span>{{ String(index + 1).padStart(2, '0') }}</span><p>{{ item }}</p><i>行动建议</i></div></div><div class="learning-path"><div class="learning-heading"><span>✦</span><div><strong>你的下一段学习路径</strong><small>依据本场回答中的证据生成</small></div></div><div class="learning-steps"><div v-for="(item, index) in currentReport.learning" :key="index"><span>{{ String(index + 1).padStart(2, '0') }}</span><p>{{ item }}</p></div></div></div></section>
            <section class="next-step-card"><div><span class="section-kicker">KEEP THE MOMENTUM</span><h2>下一次，会更清楚。</h2><p>报告仅反映本场实际覆盖的能力。</p></div><div class="next-step-actions"><button class="subtle-button" @click="navigate('home')">返回主页</button><button class="primary-button" @click="restartReport(currentReport)">重新面试 →</button></div></section>
          </template>
        </template>

        <template v-else-if="page === 'banks'">
          <div class="page-intro"><div><span class="section-kicker">YOUR QUESTION LIBRARY</span><h1>自定义题库</h1><p>{{ pageHeading[1] }}</p></div><button class="primary-button" @click="openBankPicker">＋ 上传题库</button></div>
          <div class="library-tip"><span>✦</span><p><strong>先检查解析结果并确认题库。</strong>支持 PDF、DOCX、TXT 和 Markdown；解析通过本地 MinerU 异步完成，确认后才能作为专项面试资料。</p><button @click="createBlankBank">手动创建</button></div>
          <div class="library-toolbar"><div class="library-tabs"><button class="active">全部题库 <span>{{ banks.length }}</span></button><button @click="showToast('当前演示仅包含本地题库')">最近使用</button></div><div class="sort-select">最近更新 <span>⌄</span></div></div>
          <div v-if="banks.length" class="bank-grid">
            <article v-for="(bank, index) in banks" :key="bank.id" class="bank-card-item">
              <div class="bank-card-art" :class="`art-${index % 3}`"><span class="bank-art-label">{{ bank.format }}</span><span class="bank-art-mark">{{ index % 2 === 0 ? 'Q.' : '问' }}</span><span class="bank-art-line"></span><span class="bank-art-line short"></span><span class="bank-art-spark">✦</span></div>
              <div class="bank-card-content"><div class="bank-card-title"><span class="document-icon bank-document"><svg viewBox="0 0 24 24"><path d="m3 9 9-6 9 6M5 10v9m5-9v9m4-9v9m5-9v9M3 21h18M2 9h20"/></svg></span><div><strong>{{ bank.name }}</strong><small>{{ bank.questions }} 道题 · {{ bank.format }}</small></div><button class="more-button" @click="deleteBank(bank)" title="删除题库">···</button></div><div class="bank-card-footer"><span>最近更新 {{ bank.updated }}</span><span class="document-status" :class="bank.status.toLowerCase()"><i></i>{{ bank.statusLabel }}</span></div><button v-if="bank.status === 'FAILED'" class="text-button" @click="retryParsing('QUESTION_BANK', bank)">重新解析</button><button v-if="bank.status === 'PARSED' || bank.status === 'CONFIRMED'" class="text-button" @click="openDocument('QUESTION_BANK', bank)">预览 / 编辑</button><button v-if="bank.fileId" class="text-button" @click="downloadOwnedFile(bank.fileId, bank.fileName || bank.name)">下载原文件</button><button class="bank-practice-button" :disabled="!bank.ready" @click="useBankForPractice(bank)">{{ bank.ready ? '用此题库开始练习' : '确认后开始练习' }} <span>→</span></button></div>
            </article>
            <button class="add-bank-card" @click="openBankPicker"><span>＋</span><strong>添加新题库</strong><small>上传资料，整理你的练习内容</small></button>
          </div>
          <div v-else class="empty-state library-empty"><span>▤</span><strong>还没有自定义题库</strong><p>上传自己的面试题目，开始一场专项练习。</p><button class="primary-button" @click="openBankPicker">上传题库</button></div>
        </template>

        <template v-else-if="page === 'resumes'">
          <div class="page-intro"><div><span class="section-kicker">YOUR CAREER STORY</span><h1>我的简历</h1><p>{{ pageHeading[1] }}</p></div><button class="primary-button" @click="openResumePicker">＋ 上传简历</button></div>
          <div class="resume-private-note"><span>◇</span><p><strong>本地私有资料</strong> 简历会在本机解析。解析完成后可查看结构化内容；确认后才能用于综合面试。</p></div>
          <div class="library-toolbar resume-toolbar"><div class="library-tabs"><button class="active">全部简历 <span>{{ resumes.length }}</span></button></div><span class="sort-select">最近更新 <b>⌄</b></span></div>
          <div v-if="resumes.length" class="resume-list">
            <article v-for="resume in resumes" :key="resume.id" class="resume-card" :class="{ 'resume-default': resume.id === selectedResumeId && resume.ready }">
              <div class="resume-thumb-wrap">
                <div v-if="resume.status === 'PARSED' || resume.status === 'CONFIRMED'" class="resume-thumb">
                  <div class="resume-thumb-person"><strong>{{ resumeViewModel(resume.content, resume.name).name }}</strong><small>{{ resumeViewModel(resume.content, resume.name).contact.slice(0, 2).join('　') || '个人简历' }}</small></div>
                  <section v-for="section in resumeViewModel(resume.content, resume.name).previewSections" :key="section.title" class="resume-thumb-section"><h4>{{ section.title }}</h4><p v-for="line in section.lines" :key="line">{{ line }}</p></section>
                </div>
                <div v-else class="resume-thumb-state" :class="resume.status.toLowerCase()"><span>{{ resume.status === 'FAILED' ? '!' : '⋯' }}</span><strong>{{ resume.statusLabel }}</strong><small>{{ resume.status === 'FAILED' ? (resume.parseTask?.errorMessage || '解析未完成，请重试') : '解析完成后可查看简历内容' }}</small></div>
                <span class="pdf-ribbon">{{ resume.format === 'DOCX' ? 'DOC' : resume.format }}</span>
              </div>
              <div class="resume-card-body">
                <div class="resume-title-row"><div><h3>{{ resume.name }}</h3><span class="document-status" :class="resume.status.toLowerCase()"><i></i>{{ resume.statusLabel }}</span><span v-if="resume.id === selectedResumeId && resume.ready" class="default-tag">本次已选</span></div><button class="more-button" @click="deleteResume(resume)" title="删除简历">···</button></div>
                <div class="resume-meta-line"><span>{{ resume.updated }}</span><i>·</i><span>{{ resume.projects }} 段项目经历</span></div>
                <div class="resume-card-actions">
                  <button v-if="resume.status === 'PARSED' || resume.status === 'CONFIRMED'" class="resume-view-button" @click="openResumeDetails(resume)">查看简历 <span>→</span></button>
                  <button v-if="resume.status === 'FAILED'" class="subtle-button" @click="retryParsing('RESUME', resume)">重新解析</button>
                  <button v-if="resume.fileId" class="text-button" @click="downloadOwnedFile(resume.fileId, resume.fileName || resume.name)">原文件</button>
                  <button v-if="resume.ready && resume.id !== selectedResumeId" class="text-button" @click="setDefaultResume(resume)">用于面试</button>
                </div>
              </div>
            </article>
            <button class="resume-add-card" @click="openResumePicker"><span>＋</span><strong>上传另一份简历</strong><small>支持 PDF 或 DOCX，最大 20MB</small></button>
          </div>
          <div v-else class="empty-state library-empty"><span>▤</span><strong>还没有简历</strong><p>上传 PDF 或 DOCX，解析后即可查看结构化内容。</p><button class="primary-button" @click="openResumePicker">上传简历</button></div>
        </template>

        <template v-else-if="page === 'resumeDetail'">
          <div v-if="activeResume && activeResumeView" class="resume-detail-page">
            <div class="resume-detail-toolbar"><button class="text-button" @click="page = 'resumes'">← 返回我的简历</button><div class="resume-detail-toolbar-actions"><span class="document-status" :class="activeResume.status.toLowerCase()"><i></i>{{ activeResume.statusLabel }}</span><button v-if="activeResume.fileId" class="subtle-button" @click="downloadOwnedFile(activeResume.fileId, activeResume.fileName || activeResume.name)">下载原文件</button><button v-if="activeResume.ready && activeResume.id !== selectedResumeId" class="subtle-button" @click="setDefaultResume(activeResume)">用于面试</button><button v-if="activeResume.status === 'PARSED'" class="primary-button" @click="confirmResume(activeResume)">确认并用于面试 <span>→</span></button></div></div>
            <div v-if="activeResume.status === 'PARSED'" class="resume-confirm-hint"><span>◇</span> 以下为系统解析内容。无需编辑；确认后可用于综合面试。</div>
            <article class="resume-document">
              <header class="resume-document-heading"><h1>{{ activeResumeView.name }}</h1><div v-if="activeResumeView.role" class="resume-document-role">{{ activeResumeView.role }}</div><div v-if="activeResumeView.contact.length" class="resume-document-contact"><span v-for="item in activeResumeView.contact" :key="item">{{ item }}</span></div></header>
              <section v-for="section in activeResumeView.sections" :key="section.title" class="resume-document-section">
                <h2>{{ section.title }}</h2>
                <article v-for="(item, index) in section.items" :key="`${section.title}-${index}`" class="resume-document-entry" :class="{ 'resume-project-entry': section.title === '项目经历', 'resume-skill-entry': section.title === '专业技能' }">
                  <div v-if="item.title || item.subtitle" class="resume-entry-heading"><strong>{{ item.title }}</strong><span v-if="item.subtitle">{{ item.subtitle }}</span></div>
                  <p v-for="(line, lineIndex) in item.body" :key="`body-${lineIndex}`">{{ line }}</p>
                  <div v-if="section.title === '项目经历' && item.highlights?.length" class="resume-project-highlights">
                    <strong>项目亮点</strong>
                    <ul><li v-for="(line, lineIndex) in item.highlights" :key="`highlight-${lineIndex}`">{{ line }}</li></ul>
                  </div>
                </article>
              </section>
              <div v-if="!activeResumeView.sections.length" class="resume-no-content"><strong>暂未提取到结构化简历内容</strong><p>可以重新解析原文件，或确认前先下载原文件核对。</p></div>
              <footer class="resume-document-footer">由 InterviewMirror 本地解析 · {{ activeResume.updated }}</footer>
            </article>
          </div>
          <div v-else class="empty-state"><strong>没有找到这份简历</strong><button class="subtle-button" @click="page = 'resumes'">返回我的简历</button></div>
        </template>
      </div>
    </main>

    <input ref="resumeInput" class="visually-hidden" type="file" accept=".pdf,.docx" @change="onResumeSelected" />
    <input ref="bankInput" class="visually-hidden" type="file" accept=".pdf,.docx,.txt,.md,.markdown" @change="onBankSelected" />

    <div v-if="toast" class="toast-message"><span>✓</span>{{ toast }}</div>

    <div v-if="reviewDialog && pendingReview" class="dialog-scrim" @click.self="cancelReview">
      <section class="review-dialog" role="dialog" aria-modal="true" aria-label="确认题库解析结果">
        <div class="dialog-top"><div><span class="section-kicker">REVIEW BEFORE USE</span><h2>检查并编辑题库</h2><p>{{ pendingReviewCopy.description }}</p></div><button class="dialog-close" aria-label="稍后检查" @click="cancelReview">×</button></div>
        <label class="field-label">题库名称</label><input v-model="pendingReview.name" class="text-field" />
        <div class="question-review-heading"><span class="field-label">{{ pendingReviewCopy.listTitle }}</span><button class="text-button" @click="addQuestion">＋ 添加问题</button></div>
        <div class="question-review-list"><div v-for="(question, index) in pendingReview.questions" :key="index" class="question-edit-row"><span>{{ String(index + 1).padStart(2, '0') }}</span><div><textarea v-model="question.stem" class="text-field" placeholder="题目"></textarea><textarea v-model="question.answer" class="text-field question-answer-field" rows="5" placeholder="参考答案（可选，支持多行）"></textarea><input v-model="question.category" class="text-field" placeholder="分类（可选）" /></div><button @click="removeQuestion(index)" title="删除问题">×</button></div></div>
        <div class="dialog-footnote"><span>◇</span> 原始文件和解析结果仅对当前账号可见；未确认的资料不会被面试来源接口接受。</div>
        <div class="dialog-actions"><button class="subtle-button" @click="cancelReview">稍后检查</button><button class="subtle-button" @click="saveReviewedDocument(false)">保存修改</button><button class="primary-button" @click="confirmReview">保存并确认 <span>→</span></button></div>
      </section>
    </div>
  </div>
</template>
