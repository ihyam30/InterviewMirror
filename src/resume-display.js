function asText(value) {
  if (typeof value === 'string' || typeof value === 'number') return String(value).trim()
  if (!value || typeof value !== 'object' || Array.isArray(value)) return ''
  for (const key of ['details', 'description', 'name', 'title', 'text', 'value']) {
    if (typeof value[key] === 'string' && value[key].trim()) return value[key].trim()
  }
  return ''
}

function asList(value) {
  if (Array.isArray(value)) return value.map(asText).filter(Boolean)
  if (typeof value === 'string') return value.split(/[\r\n]+/).map((item) => item.trim()).filter(Boolean)
  return []
}

function joinValues(...values) {
  return values.map(asText).filter(Boolean).join(' · ')
}

function comparisonKey(value) {
  return asText(value).toLocaleLowerCase().replace(/[^\p{L}\p{N}]/gu, '')
}

function uniqueLines(values) {
  const seen = new Set()
  return values.filter((value) => {
    const key = comparisonKey(value)
    if (!key || seen.has(key)) return false
    seen.add(key)
    return true
  })
}

const PROJECT_FIELD_TITLES = new Set([
  '项目描述', '项目介绍', '项目职责', '项目亮点', '核心亮点', '项目成果', '技术栈', '使用技术',
  'description', 'highlights', 'technologies', 'techstack',
].map(comparisonKey))

export function resumeViewModel(content = {}, fallbackName = '我的简历') {
  const info = content?.personalInfo && typeof content.personalInfo === 'object' ? content.personalInfo : {}
  const education = (Array.isArray(content?.education) ? content.education : []).map((item) => {
    const title = asText(item?.institution || item?.details) || asText(item)
    const subtitle = joinValues(item?.degree, item?.major, item?.period || item?.date)
    const body = asList(item?.description || item?.courses)
    return { title, subtitle, body }
  }).filter((item) => item.title || item.subtitle || item.body.length)
  const experiences = (Array.isArray(content?.experiences) ? content.experiences : []).map((item) => ({
    title: joinValues(item?.company, item?.organization, item?.employer, item?.position, item?.role) || asText(item),
    subtitle: joinValues(item?.period || item?.date, item?.location),
    body: asList(item?.description || item?.details || item?.responsibilities),
  })).filter((item) => item.title || item.subtitle || item.body.length)
  const projects = (Array.isArray(content?.projects) ? content.projects : [])
    .filter((item) => !PROJECT_FIELD_TITLES.has(comparisonKey(item?.name || item?.title)))
    .map((item) => {
    const named = asText(item?.name || item?.title)
    const technologies = asList(item?.technologies || item?.techStack)
    const description = named ? asList(item?.description || item?.details) : []
    const outcomes = asList(item?.outcomes)
    const highlights = outcomes.length ? outcomes : asList(item?.highlights)
    const body = uniqueLines(description)
      .filter((line) => comparisonKey(line) !== comparisonKey(technologies.join('')))
    const bodyKeys = new Set(body.map(comparisonKey))
    const projectHighlights = uniqueLines(highlights)
      .filter((line) => !bodyKeys.has(comparisonKey(line)))
      .filter((line) => comparisonKey(line) !== comparisonKey(technologies.join('')))
    return {
      title: named || asText(item?.description || item?.details) || asText(item),
      subtitle: technologies.join(' · '),
      // Keep highlights nested within project experience; global metrics remain hidden.
      body,
      highlights: projectHighlights,
    }
  }).filter((item) => item.title || item.subtitle || item.body.length)
  const skills = asList(content?.skills)
  const awards = asList(content?.awards)
  const sections = [
    { title: '教育经历', items: education },
    { title: '专业技能', items: skills.map((skill) => ({ title: '', subtitle: '', body: [skill] })) },
    { title: '项目经历', items: projects },
    { title: '工作 / 实习经历', items: experiences },
    { title: '荣誉奖项', items: awards.map((title) => ({ title, subtitle: '', body: [] })) },
  ].filter((section) => section.items.length)

  return {
    name: asText(info.name) || fallbackName || '我的简历',
    contact: [info.phone, info.email, info.location].map(asText).filter(Boolean),
    role: asText(info.targetRole || info.role || info.position),
    sections,
    previewSections: sections.slice(0, 4).map((section) => ({
      title: section.title,
      lines: section.items.slice(0, 2).map((item) => item.title || item.subtitle || item.body[0]).filter(Boolean),
    })),
    projectCount: projects.length,
  }
}
