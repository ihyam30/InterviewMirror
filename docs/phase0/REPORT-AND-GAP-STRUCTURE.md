# 报告与岗位差异分析结构

> 历史基线：`interviewmirror.report-contract.v1.0.0`。本文件记录阶段 0 的初始契约；当前以 [阶段 4 报告 Schema 1.4.0](../phase4/schemas/report.v1.4.schema.json) 为准：保留总体评价，不单独生成一句话结论；协作方式匹配和岗位差异分析均已从当前报告中移除。较早 Schema 仅用于读取历史报告。Schema 版本与 Prompt、模型版本分别管理；破坏性字段变更升主版本。

## 1. 面试报告

### 根字段

| 字段 | 类型 | 必填 | 规则 |
|---|---|---:|---|
| `schemaVersion` | string | 是 | 固定 `1.0.0` |
| `reportId`, `interviewId`, `ownerId` | string | 是 | 服务端生成、资源归属检查 |
| `mode` | enum | 是 | `COMPREHENSIVE` / `QUESTION_BANK` |
| `status` | enum | 是 | `READY` / `REPORT_RETRYABLE` / `PARTIAL` |
| `createdAt`, `completedAt` | ISO-8601 | 是 | UTC 存储，UI 可本地化 |
| `sourceSnapshot` | object | 是 | 已确认简历/题库/JD 快照版本；专项模式只填题库 |
| `summary` | object | 是 | 0–100 总评、总体评价、一句话结论及证据 |
| `scores` | object | 是 | 七维，每维有 `status/value/evidence` |
| `turns` | array | 是 | 按时间顺序的主问题、追问、回答和逐题反馈 |
| `strengths`, `risks` | array | 是 | 每项有描述和来源引用；空数组合法 |
| `recommendations`, `learningPath` | array | 是 | 可执行行动、预计投入、关联差距和依据 |
| `gapAnalysis` | object | 是 | `GENERATED` 或 `NOT_APPLICABLE`；非适用时不得有差距列表 |
| `nextActions` | array | 是 | `RESTART`, `EXPORT_PDF`, `HOME` 三项 UI 操作 |
| `generationMeta` | object | 是 | 模型 ID、Prompt/Schema 版本、Token、耗时与成本；密钥绝不保存 |

### 七维字段

枚举：`TECHNICAL_DEPTH`, `PROJECT_EXPERIENCE`, `JOB_MATCH`, `COMMUNICATION`, `LOGICAL_STRUCTURE`, `PROBLEM_SOLVING`, `CULTURE_MATCH`。每维：

```json
{
  "status": "ASSESSED | UNASSESSED | NOT_APPLICABLE",
  "value": 1,
  "rationale": "观察到的具体行为和边界",
  "evidence": [{"sourceType":"TURN","turnId":"turn-01","quote":"候选人回答中的短引文","locator":{"start":0,"end":28}}]
}
```

`ASSESSED` 時 `value` 為 1–5 且至少一条证据；`UNASSESSED`/`NOT_APPLICABLE` 時 `value=null`、证据可为空。岗位匹配无 JD 为 `NOT_APPLICABLE`；文化匹配只在 JD 有明确要求且答案覆盖时 `ASSESSED`。不会基于年龄、性别、籍贯、人格、价值观臆测或公司文化猜测评分。

### 逐题反馈

每条 `turns[]` 包括：`turnId`、`questionId`、`parentTurnId`、`kind=MAIN|FOLLOW_UP`、`question`、`answer`、`dimensionScores[]`、`feedback`、`strengths[]`、`improvements[]`、`evidence[]`、`createdAt`、`modelMeta`。追问指向前一主问题；同一主问题的 `FOLLOW_UP` 数不超过 2。报告引用短摘录和来源定位，不复制整份简历/JD。

## 2. 差异分析触发与摘要

生成条件唯一：`mode=COMPREHENSIVE` 且规范化后的 `jdText` 非空。摘要字段：

- `status=GENERATED`、`gapAnalysisId`、`matchScore`（0–100，可空）、`coverage`（已评估/要求总数）。
- `topGaps[]` 最多 3 条；证据不足可以少于三条，不能为填满 Top 3 虚构差距。
- 雷达轴 `SKILLS`, `PROJECT_EXPERIENCE`, `PROBLEM_SOLVING`, `ROLE_RESPONSIBILITIES`；各轴含 JD 要求、简历证据、面试表现的 0–5 或 `null`。
- `oneLineConclusion`、`fullAnalysisPath`（报告 ID 到完整分析页的站内路由）。
- 不适用原因 `NO_JD` 或 `QUESTION_BANK_MODE`。未评估值为 `null` 并带原因，不写为 0。

## 3. 完整差异分析页

1. **简历 vs JD：**JD 要求列表及优先级、简历证据状态 `MATCH/PARTIAL/NO_EVIDENCE/UNASSESSED`、原文引用和页码/段落定位。
2. **实际表现 vs 岗位要求：**要求与回答轮次一对多映射；状态 `DEMONSTRATED/PARTIAL/NOT_DEMONSTRATED/UNASSESSED`。没有被问到的要求标未评估。
3. **差距归因：**`KNOWLEDGE_GAP`、`EXPERIENCE_GAP`、`EXPRESSION_GAP`；一项可以有主要归因和次要归因，但需分别引用证据。
4. **证据引用：**`sourceType=JD|RESUME|TURN`、`sourceId`、`snapshotId`、页码/段落/题目轮次 locator、精确摘录、SHA-256 和可信度。报告服务端校验引用在原始快照中存在；禁止模型编造 locator。
5. **差距优先级：**按要求重要度、差距幅度、证据可信度排序；优先级 `HIGH/MEDIUM/LOW`，证据不足的项目不排序而标未评估。

## 4. 版本与接口规则

- Schema 使用 JSON Schema Draft 2020-12；对外含 `schemaVersion`，领域实体保存 `contractVersion`。
- `POST /api/v1/interviews` 使用 `interview-request.v1`；`GET /api/v1/reports/{id}` 使用 `report.v1`；`GET /api/v1/reports/{id}/gap-analysis` 使用 `gap-analysis.v1`。
- Schema 只负责形状，跨字段语义由服务校验：专项模式不得有差异；综合无 JD 不得有差异；已评分项必须有来源；ID 必须属于当前用户；报告评分和引用不可超出 1–5/原始来源。
- 模型输出 DTO 与持久化 API 响应可分开版本；模型不能直接写入最终报告。后端归一化、校验证据和适用状态后才保存。

## 5. 与 `DEVELOPMENT_PLAN.md` 的差异

计划现行报告列出五维：技术深度、项目经验、问题解决、岗位匹配、表达逻辑。本 Schema 按本次需求提供七维，将“表达逻辑”拆为 `COMMUNICATION` 与 `LOGICAL_STRUCTURE`，并加入 `CULTURE_MATCH`（严格受 JD 约束）。保留计算兼容映射：`EXPRESSIVE_LOGIC = mean(COMMUNICATION, LOGICAL_STRUCTURE)`，文化匹配是附加维度，不纳入旧五维平均值。此为提案，主计划尚未修订，M0a 七维之外的分数不能宣称已验收。
