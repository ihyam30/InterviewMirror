import assert from 'node:assert/strict'
import { test } from 'node:test'
import { reportTaskLabel, visibleReportScores } from './report-display.js'

test('report score display omits collaboration score and keeps mode-specific dimensions', () => {
  const scores = {
    TECHNICAL_DEPTH: {}, PROJECT_EXPERIENCE: {}, JOB_MATCH: {}, COMMUNICATION: {},
    LOGICAL_STRUCTURE: {}, PROBLEM_SOLVING: {}, CULTURE_MATCH: {},
  }
  assert.deepEqual(visibleReportScores(scores, 'QUESTION_BANK').map(([key]) => key), [
    'TECHNICAL_DEPTH', 'COMMUNICATION', 'LOGICAL_STRUCTURE', 'PROBLEM_SOLVING',
  ])
  assert.deepEqual(visibleReportScores(scores, 'COMPREHENSIVE').map(([key]) => key), [
    'TECHNICAL_DEPTH', 'PROJECT_EXPERIENCE', 'JOB_MATCH', 'COMMUNICATION', 'LOGICAL_STRUCTURE', 'PROBLEM_SOLVING',
  ])
})

test('report task statuses have clear history labels', () => {
  assert.equal(reportTaskLabel('PENDING'), '报告排队中')
  assert.equal(reportTaskLabel('PROCESSING'), '报告生成中')
  assert.equal(reportTaskLabel('FAILED'), '报告生成失败')
  assert.equal(reportTaskLabel('SUCCESS'), '报告已生成')
  assert.equal(reportTaskLabel(null, true), '报告已生成')
})
