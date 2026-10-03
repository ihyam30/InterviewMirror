export function textLines(values, mapItem = (item) => {
  if (typeof item === 'string') return item
  return item?.details || item?.name || item?.description || ''
}) {
  return (Array.isArray(values) ? values : []).map(mapItem).filter(Boolean).join('\n')
}

export function projectRowsForReview(value) {
  return (Array.isArray(value) ? value : []).map((item) => {
    const original = item && typeof item === 'object' && !Array.isArray(item) ? structuredClone(item) : { name: String(item || '') }
    return {
      original,
      name: original.name || original.description || '',
      technologiesText: textLines(original.technologies),
      outcomesText: textLines(original.outcomes),
    }
  })
}

export function buildResumeContent(originalContent, draft) {
  const content = { ...structuredClone(originalContent || {}), schemaVersion: 'interviewmirror.resume-content.v1' }
  content.personalInfo = Object.fromEntries(
    Object.entries(draft.personalInfo).map(([key, value]) => [key, value.trim()]).filter(([, value]) => value),
  )
  content.education = draft.educationText.split(/\r?\n/).map((details) => details.trim()).filter(Boolean).map((details) => ({ details }))
  content.experiences = draft.experienceText.split(/\r?\n/).map((description) => description.trim()).filter(Boolean).map((description) => ({ description }))
  content.projects = draft.projects.map((project) => ({
    ...project.original,
    name: project.name.trim(),
    technologies: project.technologiesText.split(/[\r\n,，、;；]+/).map((item) => item.trim()).filter(Boolean),
    outcomes: project.outcomesText.split(/\r?\n/).map((item) => item.trim()).filter(Boolean),
  })).filter((project) => project.name || project.technologies.length || project.outcomes.length)
  content.skills = draft.skillsText.split(/[\r\n,，、;；]+/).map((skill) => skill.trim()).filter(Boolean)
  content.awards = draft.awardsText.split(/\r?\n/).map((name) => name.trim()).filter(Boolean).map((name) => ({ name }))
  content.metrics = draft.metricsText.split(/\r?\n/).map((metric) => metric.trim()).filter(Boolean)
  return content
}
