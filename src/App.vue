<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { currentUser as fetchCurrentUser, confirmDocument, createDocument, createQuestionBank, deleteDocument, downloadFile, getDocument, listDocuments, login as apiLogin, logout as apiLogout, retryDocument, updateDocument, validateInterviewSource } from './api.js'
import { canReviewDocument, canRetryDocument, documentStatusLabel } from './document-state.js'
import { buildResumeContent, projectRowsForReview, textLines } from './document-content.js'

const navItems = [
  { id: 'home', label: '工作台', icon: 'home' },
  { id: 'practice', label: '模拟面试', icon: 'mic' },
  { id: 'reports', label: '历史报告', icon: 'report' },
  { id: 'banks', label: '自定义题库', icon: 'bank' },
  { id: 'resumes', label: '我的简历', icon: 'resume' },
]

const seedReports = [
  {
    id: 'report-0930', title: 'AI 应用工程师实习', mode: '综合面试', date: '2026年9月30日 · 14:20', duration: '28 分钟', questionCount: 7,
    resumeId: 'resume-1', jdText: '负责企业级知识库与智能问答应用建设；持续评估并优化大模型应用效果；熟悉服务部署、监控及异常处理。',
    overallScore: 82, overall: '基础扎实，能结合项目解释 RAG 检索链路。可以进一步补充评估指标和线上效果，让回答更有说服力。',
    hasJD: true, matchScore: 78, coverage: '8 / 10 项已评估', oneLine: '项目经历与岗位方向较匹配，线上评估与稳定性证据仍需补强。',
    scores: [
      { label: '技术深度', score: 4.1, note: 'RAG 检索链路解释清楚，重排策略还可展开。' },
      { label: '项目经验', score: 4.3, note: '能讲清个人负责内容，建议量化业务结果。' },
      { label: '问题解决', score: 3.8, note: '排查思路完整，缺少对方案取舍的说明。' },
      { label: '岗位匹配', score: 4.0, note: '核心技能覆盖较好，评估和监控证据不足。' },
      { label: '表达逻辑', score: 3.7, note: '结论明确，部分回答背景铺垫偏长。' },
    ],
    gaps: [
      { title: '缺少线上效果评估闭环', group: '实际表现差距', priority: '高', requirement: '建立离线评估集并跟踪线上反馈', evidence: 'JD · 任职要求第 3 条', detail: '你提到了召回率优化，但尚未说明如何构造评估集、设定指标或持续监控效果。' },
      { title: '项目结果缺少量化指标', group: '简历证据差距', priority: '高', requirement: '用数据说明项目效果和个人贡献', evidence: '简历 · 项目经历 1', detail: '简历描述了检索链路实现，但没有展示准确率、延迟或使用效果的变化。' },
      { title: '缺少模型服务稳定性方案', group: '实际表现差距', priority: '中', requirement: '具备超时、重试与降级处理经验', evidence: '回答 · 第 6 题', detail: '回答中提及重试，但没有说明超时边界、幂等策略和降级方案。' },
    ],
    turns: [
      { question: '请介绍一下你简历中的知识库问答项目，你负责了哪些部分？', answer: '我主要负责 RAG 检索链路，从文档切分、向量化到召回和重排都做了实现。项目里我还加了一个基于规则的查询改写。', note: '项目职责说明清楚。可以补充项目规模、评估方法和最终效果。', score: 4 },
      { question: '为什么在向量检索之后还要做重排？', answer: '向量召回更关注语义相似度，可能会把主题相关但不能回答问题的片段排前面。重排模型会结合问题和候选片段重新计算相关性。', note: '概念解释准确。建议举一个实际误召回案例。', score: 4 },
      { question: '你如何验证一次检索策略调整确实让回答更好？', answer: '我会先准备一批问题，再看召回的文档对不对。如果有错误，我会调整切分长度和召回数量。', note: '有评估意识，但需要定义标注集、指标和迭代前后的对比方式。', score: 3 },
    ],
    strengths: ['RAG 核心链路理解完整', '能清楚区分个人职责与团队成果', '回答有技术细节，能说明方案选择'],
    weaknesses: ['项目结果缺少量化证据', '评估集与线上监控方法不够具体', '部分回答的背景铺垫偏长'],
    suggestions: ['为项目补充 20–30 条固定评估问题，记录 Recall@K 与答案引用正确率。', '梳理一次线上故障或误召回案例，用“现象—定位—取舍—结果”复盘。', '练习先用一句话给结论，再用项目事实展开。'],
    learning: ['第 1 周：补全 RAG 离线评测集与指标', '第 2 周：实践模型服务超时、重试和降级', '第 3 周：围绕 JD 做一次完整项目复盘演练'],
  },
  {
    id: 'report-0928', title: 'AI 全栈开发实习', mode: '题库专项', date: '2026年9月28日 · 19:05', duration: '21 分钟', questionCount: 6,
    bankId: 'bank-2',
    overallScore: 76, overall: '后端与模型接入基础不错。建议强化前后端协作、接口边界和项目交付中的工程化表达。',
    hasJD: false, matchScore: null, coverage: null, oneLine: null,
    scores: [
      { label: '技术深度', score: 3.8, note: '基础概念准确，可增加设计取舍。' },
      { label: '项目经验', score: 3.9, note: '经历相关，缺少交付结果量化。' },
      { label: '问题解决', score: 3.6, note: '能说明排查步骤，可补充边界条件。' },
      { label: '岗位匹配', score: null, note: '专项面试不评估岗位匹配。' },
      { label: '表达逻辑', score: 3.7, note: '表达自然，建议减少重复信息。' },
    ],
    gaps: [],
    turns: [
      { question: '前端如何处理一个长时间运行的 AI 生成请求？', answer: '我会用 SSE 把增量结果推到页面，后端保存生成状态，页面显示当前进度。', note: '回答切中重点，可以补充断线后的恢复策略。', score: 4 },
      { question: '你会怎样设计模型 API 的超时与重试？', answer: '根据模型服务设置超时，失败后可以重试。如果多次失败，可以提示用户再试。', note: '基础方向正确，需补充幂等、退避和费用控制。', score: 3 },
    ],
    strengths: ['具备端到端产品实现意识', '熟悉 SSE 等交互方式'],
    weaknesses: ['异常和重试策略描述较笼统', '项目交付效果缺少数据'],
    suggestions: ['准备一次从 Vue 页面到 Java API 再到模型服务的请求链路图。', '补充模型调用的超时、重试、限流和成本统计方案。'],
    learning: ['练习完整描述一条 AI 请求的端到端数据流。', '为现有项目补充接口错误与重试策略。'],
  },
]

const resumes = ref([])
const banks = ref([])
const reports = ref([...seedReports])
const page = ref('home')
const selectedNav = computed(() => (['reportDetail', 'gapDetail'].includes(page.value) ? 'reports' : page.value))
const selectedReportId = ref(reports.value[0]?.id ?? null)
const currentReport = computed(() => reports.value.find((report) => report.id === selectedReportId.value) ?? reports.value[0])
const mode = ref('COMPREHENSIVE')
const selectedResumeId = ref(resumes.value.find((resume) => resume.default)?.id ?? resumes.value[0]?.id ?? '')
const selectedBankId = ref(banks.value[0]?.id ?? '')
const jdText = ref('')
const interviewStage = ref('setup')
const questionIndex = ref(0)
const followupCount = ref(0)
const currentQuestion = ref('')
const answerText = ref('')
const isThinking = ref(false)
const messages = ref([])
const turns = ref([])
const reportTitleDraft = ref('')
const toast = ref('')
const reviewDialog = ref(false)
const pendingReview = ref(null)
const resumeInput = ref(null)
const bankInput = ref(null)
const startedAt = ref(null)
let toastTimer
const parseTimers = new Map()
const authReady = ref(false)
const currentUserInfo = ref(null)
const loginForm = ref({ identifier: 'demo1', password: '' })
const loginBusy = ref(false)
const loginError = ref('')

const interviewScript = [
  {
    question: '请结合你的项目经历，介绍一个你最熟悉的 AI 应用项目。你负责了什么，解决了什么问题？',
    followup: '你刚才提到负责了检索链路。能具体说说你做过的一个关键技术取舍，以及它带来的结果吗？',
  },
  {
    question: '如果要判断一个 RAG 应用的回答质量，你会怎样设计一套可持续运行的评估方案？',
    followup: '你会如何处理评估集更新后，新旧版本之间的结果可比性？',
  },
  {
    question: '模型服务出现间歇性超时，但用户仍需要完成面试练习，你会如何设计这条链路？',
    followup: '如果重试会增加费用，你会怎样设置重试边界和用户提示？',
  },
  {
    question: '回到你最熟悉的项目，如果再给你两周时间，你会优先完善什么？为什么？',
    followup: '你会用什么指标验证这两周的改动确实有效？',
  },
]
const activeScript = ref(interviewScript)

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
    currentUserInfo.value = await fetchCurrentUser()
    await loadOwnedResources()
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
    await loadOwnedResources()
  } catch (error) {
    loginError.value = error.message
  } finally {
    loginBusy.value = false
  }
}

async function signOut() {
  try { await apiLogout() } catch { /* clear local view even if the server is unreachable */ }
  currentUserInfo.value = null
  resumes.value = []
  banks.value = []
}

function handleExpiredSession() {
  currentUserInfo.value = null
  resumes.value = []
  banks.value = []
  loginError.value = '登录状态已失效，请重新登录。'
}

window.addEventListener('interviewmirror-auth-expired', handleExpiredSession)
onUnmounted(() => window.removeEventListener('interviewmirror-auth-expired', handleExpiredSession))
onUnmounted(() => parseTimers.forEach((timer) => clearTimeout(timer)))

const pageHeading = computed(() => ({
  home: ['工作台', '为下一场面试，先练一次。'],
  practice: ['模拟面试', '选择练习方式，开始一场专注的模拟面试。'],
  reports: ['历史报告', '回看每一次练习，找到持续进步的证据。'],
  reportDetail: ['面试复盘', '把表现拆解清楚，让下一次准备更有方向。'],
  gapDetail: ['岗位差异分析', '从岗位要求、简历证据和面试表现中定位差距。'],
  banks: ['自定义题库', '整理你的题目，在专项面试中逐题练习。'],
  resumes: ['我的简历', '确认简历信息后，AI 才会用它生成个性化问题。'],
}[page.value] ?? ['工作台', '']))

const comprehensiveCount = computed(() => reports.value.filter((report) => report.mode === '综合面试').length)
const averageScore = computed(() => {
  if (!reports.value.length) return '—'
  return Math.round(reports.value.reduce((total, report) => total + report.overallScore, 0) / reports.value.length)
})

function showToast(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 2600)
}

function navigate(destination) {
  page.value = destination
  if (destination === 'practice') interviewStage.value = 'setup'
}

function openReport(report) {
  selectedReportId.value = report.id
  page.value = 'reportDetail'
}

function openGapAnalysis() {
  if (!currentReport.value?.hasJD) return
  page.value = 'gapDetail'
}

function restartReport(report) {
  mode.value = report.mode === '综合面试' ? 'COMPREHENSIVE' : 'QUESTION_BANK'
  selectedResumeId.value = report.resumeId ?? resumes.value.find((item) => item.default)?.id ?? resumes.value[0]?.id ?? ''
  selectedBankId.value = report.bankId ?? banks.value[0]?.id ?? ''
  jdText.value = report.mode === '综合面试' ? report.jdText ?? '' : ''
  navigate('practice')
}

function selectMode(nextMode) {
  mode.value = nextMode
}

async function beginInterview() {
  if (mode.value === 'COMPREHENSIVE') {
    const resume = resumes.value.find((item) => item.id === selectedResumeId.value && item.ready)
    if (!resume) return showToast('请先选择一份已确认的简历')
    if (jdText.value.trim().length > 1500) return showToast('JD 请控制在 1500 字以内')
    try { await validateInterviewSource('RESUME', resume.id) } catch (error) { return showToast(error.message) }
    reportTitleDraft.value = jdText.value.trim().slice(0, 26) || 'AI 应用 / AI 全栈实习'
  } else {
    const bank = banks.value.find((item) => item.id === selectedBankId.value && item.ready)
    if (!bank) return showToast('请先选择一份已确认的题库')
    try { await validateInterviewSource('QUESTION_BANK', bank.id) } catch (error) { return showToast(error.message) }
    reportTitleDraft.value = bank.name
    const questions = bank.questionItems?.length ? bank.questionItems : [
      '请介绍一个你最有代表性的项目，以及你负责的部分。',
      '你在项目中遇到的最大技术挑战是什么？',
      '如果重新实现这个项目，你会优先改进什么？',
    ]
    activeScript.value = questions.map((question) => {
      const stem = typeof question === 'string' ? question : question.stem
      return {
        question: stem,
        followup: `关于“${stem.slice(0, 18)}”，能结合一次具体经历说明你的判断和结果吗？`,
      }
    })
  }

  if (mode.value === 'COMPREHENSIVE') activeScript.value = interviewScript

  interviewStage.value = 'active'
  page.value = 'practice'
  questionIndex.value = 0
  followupCount.value = 0
  turns.value = []
  messages.value = []
  answerText.value = ''
  startedAt.value = Date.now()
  askQuestion(activeScript.value[0].question)
}

function askQuestion(question) {
  currentQuestion.value = question
  isThinking.value = true
  setTimeout(() => {
    messages.value.push({ role: 'assistant', content: question, time: '刚刚' })
    isThinking.value = false
  }, 480)
}

function submitAnswer() {
  const answer = answerText.value.trim()
  if (!answer || isThinking.value) return
  turns.value.push({ question: currentQuestion.value, answer, note: '回答已记录，完整表现将在面试结束后统一复盘。', score: null })
  messages.value.push({ role: 'user', content: answer, time: '刚刚' })
  answerText.value = ''
  const current = activeScript.value[questionIndex.value]

  if (answer.length < 58 && followupCount.value === 0 && current.followup) {
    followupCount.value = 1
    askQuestion(current.followup)
    return
  }

  questionIndex.value += 1
  followupCount.value = 0
  if (questionIndex.value >= activeScript.value.length) {
    finishInterview()
  } else {
    askQuestion(activeScript.value[questionIndex.value].question)
  }
}

function finishInterview() {
  interviewStage.value = 'setup'
  isThinking.value = false
  const elapsed = startedAt.value ? Math.max(1, Math.round((Date.now() - startedAt.value) / 60000)) : 18
  const hasJD = mode.value === 'COMPREHENSIVE' && Boolean(jdText.value.trim())
  const report = {
    ...seedReports[0],
    id: `report-${Date.now()}`,
    title: mode.value === 'COMPREHENSIVE' ? reportTitleDraft.value : reportTitleDraft.value,
    mode: mode.value === 'COMPREHENSIVE' ? '综合面试' : '题库专项',
    resumeId: mode.value === 'COMPREHENSIVE' ? selectedResumeId.value : null,
    bankId: mode.value === 'QUESTION_BANK' ? selectedBankId.value : null,
    jdText: mode.value === 'COMPREHENSIVE' ? jdText.value.trim() : '',
    date: new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date()),
    duration: `${elapsed} 分钟`,
    questionCount: Math.max(1, turns.value.length),
    overallScore: 78 + Math.floor(Math.random() * 12),
    hasJD,
    matchScore: hasJD ? 72 + Math.floor(Math.random() * 20) : null,
    coverage: hasJD ? '7 / 9 项已评估' : null,
    oneLine: hasJD ? '当前经历与岗位方向较匹配；进一步量化项目结果，补强评估与稳定性证据。' : null,
    turns: turns.value.length ? [...turns.value] : seedReports[0].turns.slice(0, 2),
    gaps: hasJD ? seedReports[0].gaps : [],
    scores: seedReports[0].scores.map((item) => ({ ...item })),
  }
  reports.value.unshift(report)
  selectedReportId.value = report.id
  page.value = 'reportDetail'
  showToast('面试已结束，复盘报告已生成')
}

function exportPdf() {
  showToast('打开打印窗口后，可选择“另存为 PDF”')
  window.setTimeout(() => window.print(), 250)
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
        showToast('解析完成，请检查并确认资料')
        openDocumentReview(type, document)
      } else if (document.status === 'FAILED') {
        showToast(document.parseTask?.errorMessage || '解析失败，可重试或手动编辑')
      }
    } catch (error) {
      parseTimers.delete(id)
      showToast(error.message)
    }
  }
  parseTimers.set(id, setTimeout(poll, 1200))
}

function openDocumentReview(type, document) {
  if (!canReviewDocument(document)) return showToast(documentStatusLabel(document.status))
  const documentMode = type === 'RESUME' || type === 'resume' ? 'resume' : 'bank'
  const content = structuredClone(document.content || {})
  const info = content.personalInfo || {}
  pendingReview.value = {
    id: document.id, type: documentMode, name: document.title, fileId: document.fileId,
    contentVersion: document.contentVersion, content,
    personalInfo: { name: info.name || '', email: info.email || '', phone: info.phone || '', location: info.location || '' },
    educationText: textLines(content.education), experienceText: textLines(content.experiences),
    projects: projectRowsForReview(content.projects),
    skillsText: textLines(content.skills),
    awardsText: textLines(content.awards), metricsText: textLines(content.metrics),
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

function addProject() {
  pendingReview.value?.projects.push({ original: {}, name: '', technologiesText: '', outcomesText: '' })
}

function removeProject(index) {
  pendingReview.value?.projects.splice(index, 1)
}

function buildReviewedContent(draft) {
  if (draft.type === 'bank') {
    const questions = draft.questions.map((item, index) => ({
      position: index + 1, stem: item.stem.trim(), answer: item.answer.trim(),
      ...(item.category.trim() ? { category: item.category.trim() } : {}),
    })).filter((item) => item.stem)
    return { schemaVersion: 'interviewmirror.question-bank-content.v1', questions }
  }
  return buildResumeContent(draft.content, draft)
}

async function saveReviewedDocument(alsoConfirm = false) {
  const draft = pendingReview.value
  if (!draft) return
  const type = draft.type === 'resume' ? 'RESUME' : 'QUESTION_BANK'
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
  if (!window.confirm('重置只会恢复当前页面里的只读示例报告，不会删除账号资料。继续吗？')) return
  resumes.value = [...resumes.value]
  banks.value = [...banks.value]
  reports.value = [...seedReports]
  selectedResumeId.value = resumes.value[0]?.id ?? ''
  selectedBankId.value = banks.value[0]?.id ?? ''
  selectedReportId.value = reports.value[0]?.id ?? null
  page.value = 'home'
  showToast('示例报告已重置，账号资料保持不变')
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
                <span class="card-meta"><span>简历必选</span><span>JD 可选</span><span>岗位差异分析</span></span>
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
                <div class="stat-item"><strong>{{ comprehensiveCount }}</strong><span>综合面试</span><small>含岗位复盘</small></div>
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
              <button v-for="report in reports.slice(0, 2)" :key="report.id" class="recent-report" @click="openReport(report)">
                <span class="recent-file"><svg viewBox="0 0 24 24"><path d="M7 3h8l4 4v14H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z"/><path d="M14 3v5h5M9 12h6m-6 4h6"/></svg></span>
                <span class="recent-main"><strong>{{ report.title }}</strong><small>{{ report.mode }} <i>·</i> {{ report.date }}</small></span>
                <span v-if="report.hasJD" class="match-mini"><small>岗位匹配</small><strong>{{ report.matchScore }}<small>分</small></strong></span>
                <span class="score-mini"><small>综合表现</small><strong>{{ report.overallScore }}<small>分</small></strong></span>
                <span class="row-arrow">→</span>
              </button>
            </div>
            <div v-else class="empty-state"><span>✦</span><strong>还没有面试记录</strong><p>完成第一场练习后，报告会出现在这里。</p></div>
          </section>
          <footer class="page-footer"><span>面镜 InterviewMirror</span><span>练习为成长服务，评价仅作自我复盘参考</span></footer>
        </template>

        <template v-else-if="page === 'practice' && interviewStage === 'setup'">
          <div class="page-intro"><div><span class="section-kicker">PRACTICE ROOM</span><h1>{{ pageHeading[0] }}</h1><p>{{ pageHeading[1] }}</p></div><span class="intro-illustration">✦</span></div>
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
                <textarea id="jd-input" v-model="jdText" class="text-field jd-field" maxlength="1500" placeholder="粘贴岗位职责与任职要求…\n\n有 JD 时，系统会在报告中加入岗位差异化分析；没有 JD 也可以开始综合练习。"></textarea>
                <div class="field-footnote"><span>最多 1500 字</span><span>{{ jdText.length }} / 1500</span></div>
          <div class="privacy-note"><span>◇</span><span>本阶段已接入本地私有文件存储；资料仅用于工程演示，解析字段由你在确认页填写，暂不调用解析服务或模型。</span></div>
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
                <div class="bank-mode-note"><span class="note-icon">✦</span><div><strong>专注练习，不依赖简历</strong><p>AI 会围绕题库原题继续追问，并在报告中逐题复盘。本模式不会生成岗位差异分析。</p></div></div>
              </template>

              <div class="form-actions"><button class="subtle-button" @click="navigate('home')">返回</button><button class="primary-button" @click="beginInterview">开始面试 <span>→</span></button></div>
            </section>
            <aside class="setup-aside">
              <div class="panel expectation-card"><span class="aside-spark">✦</span><span class="section-kicker">WHAT TO EXPECT</span><h3>这场练习会这样进行</h3>
                <div class="expect-step"><span>01</span><div><strong>逐题作答</strong><small>像真实面试一样，一次回答一个问题。</small></div></div>
                <div class="expect-step"><span>02</span><div><strong>根据回答追问</strong><small>回答不够具体时，面试官会继续深入。</small></div></div>
                <div class="expect-step"><span>03</span><div><strong>结束后完整复盘</strong><small>查看逐题反馈、优势与下一步建议。</small></div></div>
                <div class="duration-chip"><span>◷</span> 建议预留 20–30 分钟</div>
              </div>
              <div class="aside-tip"><span class="tip-star">✦</span><p>不知道怎么回答也没关系。先说出你的思路，复盘时再一起拆解。</p></div>
            </aside>
          </div>
        </template>

        <template v-else-if="page === 'practice' && interviewStage === 'active'">
          <section class="interview-topline"><button class="back-link" @click="finishInterview">← 结束并查看复盘</button><span class="live-label"><span></span>练习进行中</span><span class="mode-chip">{{ mode === 'COMPREHENSIVE' ? '综合面试' : '题库专项' }}</span></section>
          <section class="interview-layout">
            <div class="interview-main panel">
              <div class="interview-header"><div><span class="section-kicker">AI INTERVIEWER</span><h1>{{ reportTitleDraft }}</h1><p>{{ mode === 'COMPREHENSIVE' ? '面试官会根据你的回答继续追问。' : `围绕「${banks.find((bank) => bank.id === selectedBankId)?.name ?? '自定义题库'}」进行练习。` }}</p></div><div class="progress-ring"><span>{{ Math.min(questionIndex + 1, activeScript.length) }}</span><small>/ {{ activeScript.length }}</small></div></div>
              <div class="question-progress"><span :style="{ width: `${Math.max(8, (questionIndex / activeScript.length) * 100)}%` }"></span></div>
              <div class="chat-timeline">
                <div class="timeline-date">今天 · 面试开始</div>
                <div v-for="(message, index) in messages" :key="index" class="chat-message" :class="message.role">
                  <span class="chat-avatar" :class="message.role === 'assistant' ? 'ai-avatar' : 'user-avatar'">{{ message.role === 'assistant' ? '镜' : '林' }}</span>
                  <div class="message-body"><div class="message-meta"><strong>{{ message.role === 'assistant' ? 'AI 面试官' : '我' }}</strong><small>{{ message.time }}</small></div><div class="message-bubble">{{ message.content }}</div></div>
                </div>
                <div v-if="isThinking" class="chat-message assistant"><span class="chat-avatar ai-avatar">镜</span><div class="message-body"><div class="message-meta"><strong>AI 面试官</strong><small>正在思考</small></div><div class="typing-bubble"><i></i><i></i><i></i></div></div></div>
              </div>
              <div class="answer-box"><textarea v-model="answerText" :disabled="isThinking" placeholder="输入你的回答…（Enter 发送，Shift + Enter 换行）" @keydown.enter.exact.prevent="submitAnswer"></textarea><div class="answer-controls"><span>尽量结合具体经历和结果回答</span><button class="send-button" :disabled="!answerText.trim() || isThinking" @click="submitAnswer">发送回答 <span>↑</span></button></div></div>
            </div>
            <aside class="interview-aside"><div class="panel session-card"><span class="section-kicker">SESSION GUIDE</span><h3>保持你的节奏</h3><div class="session-stat"><span>当前进度</span><strong>问题 {{ Math.min(questionIndex + 1, activeScript.length) }} <small>/ {{ activeScript.length }}</small></strong></div><div class="session-stat"><span>追问方式</span><strong>根据回答动态深入</strong></div><div class="session-separator"></div><p><span>✦</span> 不需要追求完美答案。先讲清你的判断和经历。</p><button class="end-session" @click="finishInterview">结束本次面试</button></div>
              <div class="panel interview-context"><span class="section-kicker">本场资料</span><div v-if="mode === 'COMPREHENSIVE'" class="context-file"><span class="document-icon"><svg viewBox="0 0 24 24"><path d="M7 3h8l4 4v14H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z"/><path d="M14 3v5h5"/></svg></span><div><strong>{{ resumes.find((resume) => resume.id === selectedResumeId)?.name }}</strong><small>简历已确认</small></div></div><div v-if="jdText.trim()" class="context-jd"><strong>目标 JD</strong><p>{{ jdText.slice(0, 120) }}{{ jdText.length > 120 ? '…' : '' }}</p></div><div v-if="mode === 'QUESTION_BANK'" class="context-file"><span class="document-icon bank-document"><svg viewBox="0 0 24 24"><path d="m3 9 9-6 9 6M5 10v9m5-9v9m4-9v9m5-9v9M3 21h18M2 9h20"/></svg></span><div><strong>{{ banks.find((bank) => bank.id === selectedBankId)?.name }}</strong><small>{{ banks.find((bank) => bank.id === selectedBankId)?.questions }} 道已确认题目</small></div></div></div></aside>
          </section>
        </template>

        <template v-else-if="page === 'reports'">
          <div class="page-intro"><div><span class="section-kicker">YOUR JOURNEY</span><h1>历史报告</h1><p>{{ pageHeading[1] }}</p></div><button class="primary-button" @click="navigate('practice')">＋ 新建面试</button></div>
          <div class="report-summary-strip"><div><span class="summary-icon">◷</span><span><small>累计练习</small><strong>{{ reports.length }} <small>场</small></strong></span></div><div><span class="summary-icon peach-summary">✦</span><span><small>平均表现</small><strong>{{ averageScore }} <small>分</small></strong></span></div><div><span class="summary-icon green-summary">↗</span><span><small>含岗位差异分析</small><strong>{{ reports.filter((r) => r.hasJD).length }} <small>份</small></strong></span></div></div>
          <div class="library-tip"><span>◇</span><p><strong>只读示例报告</strong> 当前报告用于展示报告和差异分析交互，不代表真实登录账号的练习历史；真实报告持久化会在后续业务阶段接入。</p></div>
          <section class="panel report-list-panel"><div class="list-panel-heading"><div><span class="section-kicker">ALL SESSIONS</span><h2>示例面试记录 <span>{{ reports.length }}</span></h2></div><div class="sort-select">最近练习 <span>⌄</span></div></div>
            <div v-if="reports.length" class="report-table">
              <div class="table-head"><span>面试与岗位</span><span>类型</span><span>时间</span><span>岗位匹配</span><span>综合评分</span><span></span></div>
              <button v-for="report in reports" :key="report.id" class="report-row" @click="openReport(report)">
                <span class="report-name-cell"><span class="recent-file"><svg viewBox="0 0 24 24"><path d="M7 3h8l4 4v14H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z"/><path d="M14 3v5h5M9 12h6m-6 4h6"/></svg></span><span><strong>{{ report.title }}</strong><small>{{ report.questionCount }} 道问题 · {{ report.duration }}</small></span></span>
                <span><i class="type-pill" :class="report.hasJD ? 'type-comprehensive' : 'type-special'">{{ report.mode }}</i></span>
                <span class="table-date">{{ report.date }}</span>
                <span><strong v-if="report.hasJD" class="table-match">{{ report.matchScore }}<small>分</small></strong><small v-else class="na-label">不适用</small></span>
                <span><strong class="table-score">{{ report.overallScore }}<small>分</small></strong></span><span class="row-arrow">→</span>
              </button>
            </div>
            <div v-else class="empty-state"><span>✦</span><strong>完成一场练习，开启你的复盘记录</strong><button class="primary-button" @click="navigate('practice')">开始模拟面试</button></div>
          </section>
        </template>

        <template v-else-if="page === 'reportDetail' && currentReport">
          <div class="report-detail-top"><button class="back-link" @click="navigate('reports')">← 返回历史报告</button><div class="report-actions"><button class="subtle-button" @click="restartReport(currentReport)">↻ 重新面试</button><button class="primary-button" @click="exportPdf"><svg viewBox="0 0 24 24"><path d="M12 3v12m0 0 4-4m-4 4-4-4M5 17v4h14v-4"/></svg> 导出 PDF</button></div></div>
          <section class="report-cover"><div><span class="section-kicker">INTERVIEW REPORT</span><div class="report-title-line"><h1>{{ currentReport.title }}</h1><span class="type-pill" :class="currentReport.hasJD ? 'type-comprehensive' : 'type-special'">{{ currentReport.mode }}</span></div><div class="report-meta"><span>◷ {{ currentReport.date }}</span><i>·</i><span>{{ currentReport.duration }}</span><i>·</i><span>{{ currentReport.questionCount }} 道问题</span></div></div><div class="cover-score"><span>综合表现</span><strong>{{ currentReport.overallScore }}</strong><small>/ 100</small><div class="score-meter"><span :style="{ width: `${currentReport.overallScore}%` }"></span></div><small class="score-caption">稳步成长中</small></div></section>

          <section class="report-section report-evaluation"><div class="report-section-heading"><span class="section-number">01</span><div><span class="section-kicker">OVERALL REVIEW</span><h2>总体评价</h2></div></div><div class="overall-note"><span class="quote-mark">“</span><p>{{ currentReport.overall }}</p></div><div class="score-grid"><div v-for="(item, index) in currentReport.scores" :key="item.label" class="score-card"><div class="score-card-top"><span>{{ item.label }}</span><strong v-if="item.score !== null">{{ item.score.toFixed(1) }}<small>/5</small></strong><strong v-else class="score-na">—</strong></div><div class="score-bar"><span :style="{ width: item.score === null ? '0%' : `${item.score * 20}%` }" :class="`bar-tone-${index}`"></span></div><p>{{ item.note }}</p></div></div></section>

          <section class="report-section"><div class="report-section-heading"><span class="section-number">02</span><div><span class="section-kicker">QUESTION BY QUESTION</span><h2>问答逐题回顾</h2></div><span class="heading-side-note">{{ currentReport.turns.length }} 条回答记录</span></div><div class="turn-list"><article v-for="(turn, index) in currentReport.turns" :key="index" class="turn-card"><div class="turn-heading"><span class="turn-number">Q{{ String(index + 1).padStart(2, '0') }}</span><span class="turn-topic">{{ index === 0 ? '项目经历' : index === 1 ? '技术理解' : '问题解决' }}</span><span v-if="turn.score" class="turn-score">表现 {{ turn.score }}/5</span></div><h3>{{ turn.question }}</h3><div class="answer-quote"><span>你的回答</span><p>“{{ turn.answer }}”</p></div><div class="turn-feedback"><span>✦</span><p>{{ turn.note }}</p></div></article></div></section>

          <section v-if="currentReport.hasJD" class="report-section diff-summary-section"><div class="report-section-heading"><span class="section-number">03</span><div><span class="section-kicker">ROLE GAP SUMMARY</span><h2>岗位差异化分析</h2></div><span class="evidence-badge"><span></span>基于简历、JD 与本场回答</span></div>
            <div class="diff-summary-card"><div class="match-score-panel"><span class="match-caption">岗位匹配度</span><div class="match-score"><strong>{{ currentReport.matchScore }}</strong><span>/ 100</span></div><div class="match-progress"><span :style="{ width: `${currentReport.matchScore}%` }"></span></div><small>{{ currentReport.coverage }} · 已排除未评估项</small></div>
              <div class="summary-gaps"><div class="summary-subhead"><strong>优先关注的差距</strong><small>TOP {{ Math.min(3, currentReport.gaps.length) }}</small></div><div v-for="(gap, index) in currentReport.gaps.slice(0, 3)" :key="gap.title" class="summary-gap"><span class="gap-rank">0{{ index + 1 }}</span><span>{{ gap.title }}</span><i :class="gap.priority === '高' ? 'priority-high' : 'priority-mid'">{{ gap.priority }}</i></div><p v-if="!currentReport.gaps.length" class="no-gap-note">当前没有足够证据生成差距项。</p></div>
              <div class="summary-radar"><div class="summary-subhead"><strong>岗位能力雷达</strong><small>JD / 简历 / 表现</small></div><svg class="radar-chart" viewBox="0 0 240 190" role="img" aria-label="岗位要求、简历证据和面试表现雷达图"><polygon points="120,22 190,67 164,147 76,147 50,67" class="radar-grid"/><polygon points="120,43 173,77 153,133 86,136 68,80" class="radar-grid"/><polygon points="120,64 155,86 142,119 97,124 85,90" class="radar-grid"/><path d="M120 22v125M50 67l140 0M76 147l88-80M164 147 76 67M120 22 76 147" class="radar-axis"/><polygon points="120,39 168,82 150,133 95,128 72,83" class="radar-area jd-area"/><polygon points="120,56 153,88 138,116 102,120 88,93" class="radar-area resume-area"/><polygon points="120,50 160,85 145,123 96,121 84,86" class="radar-area answer-area"/><text x="120" y="12" text-anchor="middle">技能</text><text x="202" y="67">项目经验</text><text x="170" y="164">问题解决</text><text x="31" y="164">岗位职责</text><text x="19" y="67">交付能力</text></svg><div class="radar-legend"><span><i class="legend-jd"></i>岗位要求</span><span><i class="legend-resume"></i>简历证据</span><span><i class="legend-answer"></i>面试表现</span></div></div>
              <div class="summary-conclusion"><span class="conclusion-icon">✦</span><div><small>一句话结论</small><p>{{ currentReport.oneLine }}</p></div><button class="text-button" @click="openGapAnalysis">查看完整分析 <span>→</span></button></div>
            </div>
          </section>
          <section v-else class="report-section"><div class="report-section-heading"><span class="section-number">03</span><div><span class="section-kicker">ROLE GAP SUMMARY</span><h2>岗位差异化分析</h2></div></div><div class="not-applicable-card"><span>◇</span><div><strong>本场不生成岗位差异分析</strong><p>{{ currentReport.mode === '题库专项' ? '题库专项面试不关联简历和 JD。' : '综合面试未提供 JD。' }}如需岗位匹配度和差距雷达图，请在新建综合面试时粘贴目标岗位 JD。</p></div><button class="subtle-button" @click="mode = 'COMPREHENSIVE'; navigate('practice')">新建综合面试</button></div></section>

          <section class="report-section strengths-grid-section"><div class="report-section-heading"><span class="section-number">04</span><div><span class="section-kicker">YOUR SIGNALS</span><h2>亮点与待提升</h2></div></div><div class="strengths-grid"><div class="strength-panel"><div class="strength-title"><span class="strength-icon">✦</span><h3>亮点与优势</h3></div><ul><li v-for="item in currentReport.strengths" :key="item">{{ item }}</li></ul></div><div class="weakness-panel"><div class="strength-title"><span class="weak-icon">↗</span><h3>薄弱点与不足</h3></div><ul><li v-for="item in currentReport.weaknesses" :key="item">{{ item }}</li></ul></div></div></section>
          <section class="report-section"><div class="report-section-heading"><span class="section-number">05</span><div><span class="section-kicker">NEXT PRACTICE</span><h2>改进建议与学习路径</h2></div></div><div class="suggestion-list"><div v-for="(item, index) in currentReport.suggestions" :key="item" class="suggestion-item"><span>0{{ index + 1 }}</span><p>{{ item }}</p><i>本周可行动</i></div></div><div class="learning-path"><div class="learning-heading"><span>✦</span><div><strong>你的下一段学习路径</strong><small>先补证据，再练表达，最后回到岗位场景</small></div></div><div class="learning-steps"><div v-for="(item, index) in currentReport.learning" :key="item"><span>{{ String(index + 1).padStart(2, '0') }}</span><p>{{ item }}</p></div></div></div></section>
          <section class="next-step-card"><div><span class="section-kicker">KEEP THE MOMENTUM</span><h2>下一次，会更清楚。</h2><p>再练一次，把这次复盘变成下一次更好的回答。</p></div><div class="next-step-actions"><button class="subtle-button" @click="navigate('home')">返回主页</button><button class="primary-button" @click="restartReport(currentReport)">重新面试 <span>→</span></button></div></section>
          <footer class="page-footer"><span>面镜 InterviewMirror</span><span>AI 生成内容用于自我练习与复盘</span></footer>
        </template>

        <template v-else-if="page === 'gapDetail' && currentReport">
          <div class="report-detail-top"><button class="back-link" @click="page = 'reportDetail'">← 返回面试报告</button><button class="subtle-button" @click="exportPdf">导出完整报告 PDF</button></div>
          <div class="gap-page-heading"><span class="section-kicker">FULL ROLE GAP ANALYSIS</span><h1>岗位差异分析</h1><p>{{ currentReport.title }} <i>·</i> {{ currentReport.date }}</p><div class="gap-score-pill"><span>岗位匹配度</span><strong>{{ currentReport.matchScore }}<small>/100</small></strong><span class="gap-score-track"><i :style="{ width: `${currentReport.matchScore}%` }"></i></span><small>{{ currentReport.coverage }}；未评估项不计为不匹配</small></div></div>
          <section class="analysis-section"><div class="report-section-heading"><span class="section-number">01</span><div><span class="section-kicker">RESUME VS. JD</span><h2>简历与岗位要求</h2></div></div><div class="comparison-grid"><article class="comparison-card"><div class="comparison-title"><span class="comparison-icon jd-icon">JD</span><div><strong>岗位核心要求</strong><small>从目标岗位描述中提取</small></div></div><div class="requirement-row"><span>01</span><p><strong>有 RAG 或知识库问答项目经验</strong><small>“负责企业级知识库与智能问答应用建设”</small></p><i class="requirement-high">核心</i></div><div class="requirement-row"><span>02</span><p><strong>具备评估与效果迭代意识</strong><small>“持续评估并优化大模型应用效果”</small></p><i class="requirement-high">核心</i></div><div class="requirement-row"><span>03</span><p><strong>了解服务部署与稳定性保障</strong><small>“熟悉服务部署、监控及异常处理”</small></p><i class="requirement-mid">重要</i></div></article>
              <article class="comparison-card"><div class="comparison-title"><span class="comparison-icon resume-icon">简</span><div><strong>简历中的相关证据</strong><small>来自已确认简历</small></div></div><div class="resume-evidence-row"><span class="evidence-check">✓</span><p><strong>实现文档切分、向量召回与重排</strong><small>简历 · 项目经历 1 · 第 2 段</small></p><i class="evidence-match">匹配</i></div><div class="resume-evidence-row"><span class="evidence-check partial">~</span><p><strong>提及检索优化，未给出评估指标</strong><small>简历 · 项目经历 1 · 第 4 段</small></p><i class="evidence-partial">部分</i></div><div class="resume-evidence-row"><span class="evidence-none">—</span><p><strong>暂未找到部署监控的直接证据</strong><small>该要求未在简历中体现</small></p><i class="evidence-unknown">待补证据</i></div></article></div></section>

          <section class="analysis-section"><div class="report-section-heading"><span class="section-number">02</span><div><span class="section-kicker">INTERVIEW VS. ROLE</span><h2>实际表现与岗位要求</h2></div></div><div class="performance-table-wrap"><table class="performance-table"><thead><tr><th>岗位要求</th><th>简历证据</th><th>面试表现</th><th>当前判断</th></tr></thead><tbody><tr><td><strong>RAG 项目经验</strong><small>核心要求</small></td><td><span class="table-status good">● 已体现</span></td><td><span class="table-status good">● 能解释链路</span><small>回答 Q1、Q2</small></td><td><span class="table-status good">匹配</span></td></tr><tr><td><strong>评估与效果迭代</strong><small>核心要求</small></td><td><span class="table-status partial">● 有相关描述</span></td><td><span class="table-status partial">● 指标和流程不完整</span><small>回答 Q3</small></td><td><span class="table-status partial">部分匹配</span></td></tr><tr><td><strong>部署与稳定性</strong><small>重要要求</small></td><td><span class="table-status unknown">— 未找到证据</span></td><td><span class="table-status partial">● 提及重试，边界不足</span><small>回答 Q6</small></td><td><span class="table-status partial">需要补强</span></td></tr></tbody></table></div></section>

          <section class="analysis-section"><div class="report-section-heading"><span class="section-number">03</span><div><span class="section-kicker">GAP ATTRIBUTION</span><h2>差距归因与证据</h2></div></div><div class="gap-detail-list"><article v-for="(gap, index) in currentReport.gaps" :key="gap.title" class="gap-detail-card"><div class="gap-detail-top"><span class="gap-rank">0{{ index + 1 }}</span><span class="gap-type-chip">{{ gap.group }}</span><span class="priority-chip" :class="gap.priority === '高' ? 'priority-high-bg' : 'priority-mid-bg'">{{ gap.priority }}优先级</span></div><h3>{{ gap.title }}</h3><p class="gap-description">{{ gap.detail }}</p><div class="evidence-quote-block"><span>证据来源</span><p>“{{ gap.evidence }}：{{ gap.requirement }}”</p><small>本场回答与已确认资料仅用于练习反馈</small></div></article><div v-if="!currentReport.gaps.length" class="empty-state"><strong>当前没有足够证据生成差距项</strong><p>可以补充简历或回答后再次练习。</p></div></div></section>
          <section class="gap-next-action"><div><span class="section-kicker">YOUR NEXT MOVE</span><h2>把最高优先级差距带进下一次练习</h2><p>针对一个具体要求补足证据，再用同一岗位方向检验表达效果。</p></div><button class="primary-button" @click="page = 'reportDetail'">回到报告 <span>→</span></button></section>
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
          <div class="resume-private-note"><span>◇</span><p><strong>本地私有资料</strong> 原始文件保存在账号私有对象空间；MinerU 在本机异步提取结构化简历信息，只有确认后的版本才能用于综合面试。</p></div>
          <div class="resume-layout"><section class="resume-list-column"><div class="library-toolbar resume-toolbar"><div class="library-tabs"><button class="active">全部简历 <span>{{ resumes.length }}</span></button></div><span class="sort-select">最近更新 <b>⌄</b></span></div>
              <div v-if="resumes.length" class="resume-list"> <article v-for="resume in resumes" :key="resume.id" class="resume-card" :class="{ 'resume-default': resume.id === selectedResumeId && resume.ready }"><div class="resume-file-preview"><span class="pdf-ribbon">{{ resume.format === 'DOCX' ? 'DOC' : resume.format }}</span><div class="preview-monogram">镜<br /><small>简历</small></div><i></i><i></i><i class="preview-short"></i><i></i></div><div class="resume-card-body"><div class="resume-title-row"><div><h3>{{ resume.name }}</h3><span class="document-status" :class="resume.status.toLowerCase()"><i></i>{{ resume.statusLabel }}</span><span v-if="resume.id === selectedResumeId && resume.ready" class="default-tag">本次已选</span></div><button class="more-button" @click="deleteResume(resume)" title="删除简历">···</button></div><div class="resume-meta-line"><span>{{ resume.size }}</span><i>·</i><span>{{ resume.projects }} 段项目经历</span><i>·</i><span>{{ resume.updated }} 更新</span></div><div class="resume-skills"><span v-for="skill in resume.skills.split('、').filter(Boolean).slice(0, 4)" :key="skill">{{ skill }}</span><small v-if="!resume.skills">尚未提取技能</small></div><div v-if="resume.project" class="resume-project"><small>项目摘要</small><p>{{ resume.project }}</p></div><div class="resume-card-actions"><button v-if="resume.status === 'PARSED' || resume.status === 'CONFIRMED'" class="subtle-button" @click="openDocument('RESUME', resume)">预览 / 编辑</button><button v-if="resume.status === 'FAILED'" class="subtle-button" @click="retryParsing('RESUME', resume)">重新解析</button><button v-if="resume.fileId" class="text-button" @click="downloadOwnedFile(resume.fileId, resume.fileName || resume.name)">下载原文件</button><button v-if="resume.ready" class="text-button" @click="setDefaultResume(resume)">选择用于面试</button></div></div></article></div>
              <div v-else class="empty-state"><span>▤</span><strong>上传一份简历，开始你的第一场综合面试</strong><button class="primary-button" @click="openResumePicker">上传简历</button></div>
            </section><aside class="resume-aside"><div class="panel resume-aside-card"><span class="section-kicker">A GOOD START</span><h3>让经历成为你的回答线索</h3><p>简历中的项目、技术选择和结果，会成为综合面试追问的起点。</p><div class="resume-aside-illustration"><span class="resume-paper"><i></i><i></i><i></i><b>✦</b></span><span class="resume-sun"></span></div><div class="aside-check"><span>✓</span> 上传后先检查解析结果</div><div class="aside-check"><span>✓</span> 修正错漏后再确认使用</div><div class="aside-check"><span>✓</span> 只在综合面试中关联简历</div></div></aside></div>
        </template>
      </div>
    </main>

    <input ref="resumeInput" class="visually-hidden" type="file" accept=".pdf,.docx" @change="onResumeSelected" />
    <input ref="bankInput" class="visually-hidden" type="file" accept=".pdf,.docx,.txt,.md,.markdown" @change="onBankSelected" />

    <div v-if="toast" class="toast-message"><span>✓</span>{{ toast }}</div>

    <div v-if="reviewDialog && pendingReview" class="dialog-scrim" @click.self="cancelReview">
      <section class="review-dialog" role="dialog" aria-modal="true" :aria-label="pendingReview.type === 'resume' ? '确认简历解析结果' : '确认题库解析结果'">
        <div class="dialog-top"><div><span class="section-kicker">REVIEW BEFORE USE</span><h2>{{ pendingReview.type === 'resume' ? '检查并编辑简历' : '检查并编辑题库' }}</h2><p>来源文件已完成本地解析。保存修改后需再次确认，才能用于面试。</p></div><button class="dialog-close" aria-label="稍后检查" @click="cancelReview">×</button></div>
        <template v-if="pendingReview.type === 'resume'">
          <label class="field-label">资料名称</label><input v-model="pendingReview.name" class="text-field" />
          <div class="resume-edit-grid"><label><span class="field-label">姓名</span><input v-model="pendingReview.personalInfo.name" class="text-field" /></label><label><span class="field-label">邮箱</span><input v-model="pendingReview.personalInfo.email" class="text-field" /></label><label><span class="field-label">电话</span><input v-model="pendingReview.personalInfo.phone" class="text-field" /></label><label><span class="field-label">所在地</span><input v-model="pendingReview.personalInfo.location" class="text-field" /></label></div>
          <label class="field-label dialog-field-label">教育经历（每行一项）</label><textarea v-model="pendingReview.educationText" class="text-field dialog-textarea"></textarea>
          <label class="field-label dialog-field-label">工作 / 实习经历（每行一项）</label><textarea v-model="pendingReview.experienceText" class="text-field dialog-textarea"></textarea>
          <div class="question-review-heading dialog-field-label"><span class="field-label">项目经历</span><button class="text-button" @click="addProject">＋ 添加项目</button></div>
          <div class="question-review-list"><div v-for="(project, index) in pendingReview.projects" :key="index" class="question-edit-row resume-project-row"><span>{{ String(index + 1).padStart(2, '0') }}</span><div><input v-model="project.name" class="text-field" placeholder="项目名称" /><textarea v-model="project.technologiesText" class="text-field" placeholder="技术栈（逗号分隔）"></textarea><textarea v-model="project.outcomesText" class="text-field" placeholder="项目成果（每行一项）"></textarea></div><button @click="removeProject(index)" title="删除项目">×</button></div></div>
          <label class="field-label dialog-field-label">技能关键词</label><textarea v-model="pendingReview.skillsText" class="text-field dialog-textarea"></textarea>
          <label class="field-label dialog-field-label">量化结果（每行一项）</label><textarea v-model="pendingReview.metricsText" class="text-field dialog-textarea"></textarea>
          <label class="field-label dialog-field-label">奖项 / 荣誉（每行一项）</label><textarea v-model="pendingReview.awardsText" class="text-field dialog-textarea"></textarea>
        </template>
        <template v-else>
          <label class="field-label">题库名称</label><input v-model="pendingReview.name" class="text-field" />
          <div class="question-review-heading"><span class="field-label">解析出的题目</span><button class="text-button" @click="addQuestion">＋ 添加问题</button></div>
          <div class="question-review-list"><div v-for="(question, index) in pendingReview.questions" :key="index" class="question-edit-row"><span>{{ String(index + 1).padStart(2, '0') }}</span><div><textarea v-model="question.stem" class="text-field" placeholder="题目"></textarea><input v-model="question.answer" class="text-field" placeholder="参考答案（可选）" /><input v-model="question.category" class="text-field" placeholder="分类（可选）" /></div><button @click="removeQuestion(index)" title="删除问题">×</button></div></div>
        </template>
        <div class="dialog-footnote"><span>◇</span> 原始文件和解析结果仅对当前账号可见；未确认的资料不会被面试来源接口接受。</div>
        <div class="dialog-actions"><button class="subtle-button" @click="cancelReview">稍后检查</button><button class="subtle-button" @click="saveReviewedDocument(false)">保存修改</button><button class="primary-button" @click="confirmReview">保存并确认 <span>→</span></button></div>
      </section>
    </div>
  </div>
</template>
