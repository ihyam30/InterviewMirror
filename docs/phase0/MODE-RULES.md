# 面试模式规则

> 版本：`interviewmirror.mode-rules.v1.0.0`  
> API 合同：`schemas/interview-request.v1.schema.json`

## 1. 两种模式

| 规则 | 综合面试 `COMPREHENSIVE` | 专项面试 `QUESTION_BANK` |
|---|---|---|
| 必需输入 | 已确认且属于当前用户的 `resumeId` | 已确认且属于当前用户的 `questionBankId` |
| 可选输入 | `jdText`，允许缺省/空串 | 无 |
| 禁止输入 | `questionBankId` | `resumeId`、`jdText` |
| 问题来源 | 简历项目 + 可选 JD | 已确认题库中的题目 |
| 差异分析 | 仅 `jdText` 有非空有效文本时生成 | 永不生成 |
| 岗位匹配分 | JD 存在时评估；否则不适用 | 不适用 |
| 重新面试 | 新场次，复用原简历/JD 快照 | 新场次，复用原题库快照 |

## 2. 输入契约与校验

通用字段：`schemaVersion`、`mode`、`clientRequestId`、`locale`。所有 ID 必须属于当前账号；按 `INTERVIEW-REQUEST` JSON Schema 做结构校验，服务端再进行资源状态、所有权和跨字段校验。

### 综合模式

1. `mode` 必须为 `COMPREHENSIVE`。
2. `resumeId` 必须存在、属于当前用户且状态 `READY`；否则拒绝 `409 RESUME_NOT_READY` 或 `404 RESOURCE_NOT_FOUND`，不调用模型。
3. 不允许提供 `questionBankId`；若提供，返回 `400 MODE_INPUT_CONFLICT`。
4. `jdText` 缺省/空白合法；若非空则去除首尾空白、规范化换行，并按冻结版本限制 1,500 字（`length > 1500` 时 `400 JD_TOO_LONG`）。
5. 上传文件限制、可编辑解析状态及确认规则见 `USER-FLOW.md`；只将已确认简历快照和用户粘贴的 JD 放进场次上下文。

### 专项模式

1. `mode` 必须为 `QUESTION_BANK`。
2. `questionBankId` 必须存在、属于当前用户且状态 `READY`、题目数 ≥1；否则拒绝 `409 QUESTION_BANK_NOT_READY` 或 `422 EMPTY_QUESTION_BANK`。
3. 明确拒绝缺题库；不得回退为综合面试。
4. 请求中不得携带 `resumeId` 或 `jdText`；返回 `400 MODE_INPUT_CONFLICT`，不读取相关资源。
5. 从用户确认的题目快照抽题并保存 question IDs；题库变更不改变已创建场次。

## 3. 流程分支和限制

```text
create → validate mode-specific inputs → load confirmed snapshot → select 5–8 main questions
 → ask → save answer → score/evidence → [follow-up <=2 per main question | next main question]
 → [user ends | question limit] → report → [gap analysis iff comprehensive && jd non-empty]
```

- 所有模型内容必须经 DTO/JSON Schema 校验，再应用域校验（分数范围、证据定位、问题 ID、适用模式）。最多做 1 次受限格式修复；再失败进入 `REPORT_RETRYABLE`，已写入回答不回滚。
- 将每次回答与用户 ID、场次 ID、题目 ID 和幂等 `clientRequestId` 绑定。重复提交幂等返回原结果，不重复计费。
- 图最大步数建议 80（8 个主问题 + 最多 16 次追问及准备/报告节点留有余量）；模型节点单次墙钟超时 30 秒，报告汇总 60 秒，超过即保存当前状态并返回可重试错误。最终数值在 M0 PoC 后锁定。
- `FINISH_EARLY` 跳至报告生成；空场次不允许生成评分报告，可生成“未完成”草稿或确认放弃。
- 条件边不可根据模型自由调用工具。资料与回答一律是不可信上下文，任何文档内指令不改变工作流。

## 4. 报告差异

| 报告字段 | 综合 + JD | 综合无 JD | 专项 |
|---|---|---|---|
| 基础信息/总体评价/逐题反馈 | 有 | 有 | 有 |
| 技术深度、项目经验、沟通、逻辑、问题解决 | 按证据评分 | 按证据评分 | 按证据评分 |
| 岗位匹配 | 1–5 或未评估 | `NOT_APPLICABLE` | `NOT_APPLICABLE` |
| 文化匹配 | 只在 JD 明确列出协作/工作要求且回答有证据时评分 | `NOT_APPLICABLE` | `NOT_APPLICABLE` |
| 差异摘要/独立页 | 生成；依据不足时部分要求 `UNASSESSED` | 不生成，显示原因 | 不生成，显示原因 |
| 下步动作 | 新场次/导出 PDF/返回主页 | 同左 | 同左 |

跨字段域规则：

- `hasGapAnalysis == true` 当且仅当 `mode == COMPREHENSIVE && trim(jdText).length > 0`。
- `QUESTION_BANK` 报告中的 `resumeId`/`jdText`/`gapAnalysis` 不能含用户资料值；字段可为 `null`/`NOT_APPLICABLE`。
- 无 JD、未问到或没有有效回答证据的要求必须标 `UNASSESSED`，不能记为零分或写成能力差距。
- 所有输出保存 `contractVersion`、`promptVersion`、`modelId`、资料快照 ID 和评测 `gitHead`。

## 5. 与计划的冲突与处置

根目录计划明确的评分维度是五维；本任务上下文提出七维。为兼容计划，五维作为 Stage 0 阶段闸门基准；七维产品草案在报告 Schema 与评分量表中显式版本化，并将计划“表达逻辑”映射到“沟通表达 + 逻辑结构”的子维度。新增的“文化匹配”仅为严格受限的可空扩展，不能改变 M0a 门槛或用于人格/价值判断。此差异需在进入 M0b/M1 前由产品负责人批准写回主计划。
