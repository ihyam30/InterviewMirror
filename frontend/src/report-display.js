const specializedScoreKeys = new Set([
  'TECHNICAL_DEPTH',
  'COMMUNICATION',
  'LOGICAL_STRUCTURE',
  'PROBLEM_SOLVING',
])
const comprehensiveScoreKeys = new Set([
  'TECHNICAL_DEPTH',
  'PROJECT_EXPERIENCE',
  'JOB_MATCH',
  'COMMUNICATION',
  'LOGICAL_STRUCTURE',
  'PROBLEM_SOLVING',
])

export function visibleReportScores(scores, modeCode) {
  const entries = Object.entries(scores || {})
  return modeCode === 'QUESTION_BANK'
    ? entries.filter(([key]) => specializedScoreKeys.has(key))
    : entries.filter(([key]) => comprehensiveScoreKeys.has(key))
}

export function reportTaskLabel(status, hasReport = false) {
  if (status === 'PENDING') return '报告排队中'
  if (status === 'PROCESSING') return '报告生成中'
  if (status === 'FAILED') return '报告生成失败'
  if (status === 'SUCCESS' || hasReport) return '报告已生成'
  return '等待报告任务'
}
