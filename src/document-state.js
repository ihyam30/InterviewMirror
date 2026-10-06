const labels = {
  PENDING: '等待解析',
  PROCESSING: '正在解析',
  PARSED: '解析完成 · 待确认',
  FAILED: '解析失败',
  CONFIRMED: '已确认，可用于面试',
  DELETING: '正在删除',
  DELETE_FAILED: '删除失败 · 可重试',
}

export function documentStatusLabel(status) {
  return labels[status] || '状态未知'
}

export function canReviewDocument(document) {
  return document?.status === 'PARSED' || document?.status === 'CONFIRMED'
}

export function canRetryDocument(document) {
  return document?.status === 'FAILED'
}

export function canUseDocument(document) {
  return document?.status === 'CONFIRMED' && document?.usableForInterview === true
}

export function questionBankReviewCopy(document) {
  if (document?.fileId) {
    return {
      description: '来源文件已完成本地解析。保存修改后需再次确认，才能用于面试。',
      listTitle: '解析出的题目',
    }
  }
  return {
    description: '这是手工创建的题库。保存修改后需确认，才能用于面试。',
    listTitle: '题目列表',
  }
}
