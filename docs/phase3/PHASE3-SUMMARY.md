# 阶段 3：面试运行时（本地实现记录）

## 当前范围

本阶段把模拟面试从前端固定脚本接入 Spring Boot API、PostgreSQL 持久化、LangGraph4j checkpoint、Spring AI OpenAI-compatible 模型适配器和持久化 SSE。当前不生成评分报告、岗位差异分析或 PDF；完成页明确显示本场问答记录已保存、报告能力尚未接入。

## 已实现接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/interviews` | 校验模式、授权、模式来源与模型数据处理同意，创建幂等场次和来源快照 |
| GET | `/api/v1/interviews` | 当前用户的场次列表 |
| GET | `/api/v1/interviews/{id}` | 当前用户的会话状态与未完成 turn |
| POST | `/api/v1/interviews/{id}/start` | 创建/恢复 LangGraph checkpoint 并生成首题 |
| GET | `/api/v1/interviews/{id}/turns` | 从业务表读取完整逐轮记录 |
| POST | `/api/v1/interviews/{id}/answers` | 先持久化幂等回答，再由恢复 worker 推进图 |
| POST | `/api/v1/interviews/{id}/replace-question` | 换掉尚未作答的主问题 |
| POST | `/api/v1/interviews/{id}/end` | 提前结束并保留回答 |
| GET | `/api/v1/interviews/{id}/events` | SSE；支持 `Last-Event-ID`，事件存 PostgreSQL |

所有接口依赖已有 Session 认证；所有查询以 `owner_id` 限定。Checkpoint `threadId` 固定使用 interview UUID，且恢复前必须经业务 API 做 owner 校验。

## 模式矩阵

- `COMPREHENSIVE`：必须提供当前用户已确认的 `resumeId`；可缺省 JD（最多 1500 字）；拒绝 `questionBankId`。
- `QUESTION_BANK`：必须提供当前用户已确认且至少有 6 道有效题目的 `questionBankId`；拒绝 `resumeId` 和任何 `jdText` 属性（包括显式 `null`）。
- 两种模式都必须 `modelDataConsent=true`。未配置模型时创建请求返回 `503 INTERVIEW_MODEL_NOT_CONFIGURED`，不写入 session。
- 创建请求严格遵守版本化 Schema：未知字段拒绝；综合模式未提供 JD 时应省略 `jdText`，显式 `null` 拒绝。
- 来源内容在创建时形成会话快照；专项题库模式快照不读取简历。

规则口径已统一：当前专项面试固定 6 道主问题，题库必须至少有 6 道有效题目才能创建专项面试；少于 6 道的已确认题库仍可管理和编辑，但不可用作面试来源。`docs/phase0/MODE-RULES.md`、`docs/phase0/USER-FLOW.md` 与后端 `QUESTION_BANK_NOT_ENOUGH_QUESTIONS` 校验现已对齐。

## 状态和可靠性

业务状态：`CREATED → PREPARING → RUNNING → COMPLETE`，准备失败进入 `START_FAILED`，可再次启动；回答提交先将 turn 标记 `ANSWERED` 并将 transition 写成 `PENDING`，事务提交后异步调度器领取并推进 LangGraph。过期 `PROCESSING` transition 在恢复间隔后回收，保留原回答。

会话响应中的 `replacementAvailable` 表示当前未作答主问题能否换题：综合模式在换题次数未达上限时可由模型生成替代题；专项模式只有存在未使用的备用题时才为 `true`。前端据此显示换题入口，后端仍会独立执行状态和备用题校验。

每题最多追问两次，固定 6 个主问题；回答最多 10,000 字；问题计划在后端校验数量、空题、重复题和长度。模型追问结构错误或请求失败时跳过追问继续下一主问题，并记录 `interview.error` 事件。

SSE 是通知，不是业务事实来源；重连时使用事件 ID 补发，页面恢复同时以 `GET interview` + `GET turns` 重建 UI。每条 turn 单独保存问题、答案、类型、序号、来源 ID 和时间。

## 本地启用模型

模型调用默认关闭。用户需在 `.env` 中显式设置 `INTERVIEW_MODEL_ENABLED=true`、供应商的兼容 API base URL、`INTERVIEW_MODEL_API_KEY` 和模型 ID，并勾选界面上的资料处理同意。Qwen 示例见 `.env.example`，DashScope OpenAI-compatible base URL 使用 `https://dashscope.aliyuncs.com/compatible-mode/v1`；GLM 可以使用阶段 0 已验证的 BigModel OpenAI-compatible URL 和模型 ID。不要把真实密钥写入仓库、日志或评测文件。

## 结构版本

- 创建请求：`schemaVersion: 1.1.0`，定义见 [interview-create.v1.1.schema.json](schemas/interview-create.v1.1.schema.json)。
- 回答请求：`interview-answer.v1.0`，定义见 [interview-answer.v1.0.schema.json](schemas/interview-answer.v1.0.schema.json)。
- 会话响应、turn 响应、SSE envelope 分别携带独立 `schemaVersion`。
- SQL 结构以 Flyway `V3__create_interview_runtime.sql` 版本化；prompt 版本为 `phase3.interview-plan.v1`、`phase3.followup.v1`。

## 当前验收边界

自动化测试覆盖 API 闭环、回答幂等、来源门禁、用户隔离、恢复 worker 推进、11 组模式来源组合、严格创建 Schema、SSE 问题/追问/完成事件及 Last-Event-ID 补发。`InterviewPostgresCheckpointRecoveryTest` 在隔离 PostgreSQL 16 上运行 Flyway V1–V4 与阶段 3 LangGraph，由多个独立 JVM 验证 checkpoint 和已消费回答 turn ID 可恢复，并验证第三次追问回答转入下一主问题；命令见 `scripts/phase3/run-postgres-checkpoint-smoke.ps1`。新增 `InterviewBackendRestartRecoveryTest` 通过真实登录及面试 API，在一个 Spring Boot JVM 保存回答后硬退出，由第二个独立 Spring Boot JVM 恢复 PostgreSQL 业务状态和 graph checkpoint；2026-10-04 本地隔离 smoke 通过，复现命令见 `scripts/phase3/run-backend-restart-smoke.ps1`。`scripts/phase3/run-compose-smoke.ps1` 也已在随机隔离环境通过完整 Docker Compose 构建、四服务健康、Flyway V1–V4 和演示登录验收。上述恢复/Compose smoke 使用本地确定性或关闭模型设置，不代表真实模型质量。真实模型盲评仍需新 prompt 的固定 40 场景输出和两位盲评人；阶段 0 的旧 prompt 评分不能冒充新 prompt 结果。
