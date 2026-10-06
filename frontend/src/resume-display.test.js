import assert from 'node:assert/strict'
import { test } from 'node:test'
import { resumeViewModel } from './resume-display.js'

test('resume view model turns parsed resume fields into readable sections and card excerpts', () => {
  const view = resumeViewModel({
    personalInfo: { name: '林同学', phone: '13800000000', email: 'lin@example.test' },
    education: [{ institution: '广东财经大学', degree: '本科', major: '软件工程', period: '2024-09 ~ 2028-06' }],
    skills: ['Java', 'Spring Boot'],
    projects: [{ name: 'RAG 问答平台', technologies: ['Spring AI', 'PostgreSQL'], outcomes: ['Recall@5 提升至 0.92'] }],
    awards: [{ name: '蓝桥杯二等奖' }],
  }, 'resume.pdf')

  assert.equal(view.name, '林同学')
  assert.deepEqual(view.contact, ['13800000000', 'lin@example.test'])
  assert.equal(view.projectCount, 1)
  assert.equal(view.sections.find((section) => section.title === '教育经历').items[0].title, '广东财经大学')
  assert.deepEqual(view.previewSections.map((section) => section.title), ['教育经历', '专业技能', '项目经历', '荣誉奖项'])
  assert.equal(view.previewSections[2].lines[0], 'RAG 问答平台')
  assert.deepEqual(view.sections.find((section) => section.title === '项目经历').items[0].highlights, ['Recall@5 提升至 0.92'])
})

test('resume view model tolerates sparse, string-based, and missing parser fields', () => {
  const view = resumeViewModel({
    education: ['本科 · 计算机科学'],
    experiences: [{ description: '负责后端接口开发' }],
    projects: [{ description: '文档问答项目', techStack: ['Java'], highlights: ['支持流式响应'] }],
    skills: 'Java\nVue 3',
  }, 'candidate.docx')

  assert.equal(view.name, 'candidate.docx')
  assert.deepEqual(view.sections.map((section) => section.title), ['教育经历', '专业技能', '项目经历', '工作 / 实习经历'])
  assert.equal(view.sections[2].items[0].title, '文档问答项目')
  assert.deepEqual(view.sections[2].items[0].body, [])
  assert.deepEqual(view.sections[2].items[0].highlights, ['支持流式响应'])
})

test('resume view model keeps project details together, removes duplicates, and hides global metrics', () => {
  const view = resumeViewModel({
    skills: ['熟悉 Java 基础，了解集合、多线程、JVM 基础知识。', '熟悉 Spring Boot、MyBatis-Plus 开发。'],
    projects: [
      {
        name: '项目甲',
        technologies: ['Java', 'SpringBoot', 'MyBatis-Plus'],
        description: '甲项目描述。\nJava SpringBoot MyBatis-Plus\n甲项目描述 ·',
        outcomes: ['甲项目亮点。'],
      },
      { name: '项目乙', technologies: ['Java', 'PostgreSQL'], description: '乙项目描述。', outcomes: ['乙项目亮点。'] },
      { name: '项目描述:', description: '旧版错误切分出来的字段段落', outcomes: [] },
    ],
    metrics: ['旧版汇总成果'],
  })

  const skills = view.sections.find((section) => section.title === '专业技能').items
  const projects = view.sections.find((section) => section.title === '项目经历').items

  assert.equal(skills.length, 2)
  assert.equal(skills[0].body[0], '熟悉 Java 基础，了解集合、多线程、JVM 基础知识。')
  assert.equal(view.sections.some((section) => section.title === '项目成果'), false)
  assert.equal(projects.length, 2)
  assert.equal(projects[0].title, '项目甲')
  assert.equal(projects[1].title, '项目乙')
  assert.deepEqual(projects[0].body, ['甲项目描述。'])
  assert.deepEqual(projects[1].body, ['乙项目描述。'])
  assert.deepEqual(projects[0].highlights, ['甲项目亮点。'])
  assert.deepEqual(projects[1].highlights, ['乙项目亮点。'])
  assert.equal(JSON.stringify(projects).includes('旧版错误切分出来的字段段落'), false)
  assert.equal(JSON.stringify(projects).includes('旧版汇总成果'), false)
})
