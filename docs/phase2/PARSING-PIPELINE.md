# 阶段 2：资料解析流水线

## 范围与依赖

简历和题库共享资料生命周期，资料类型由服务端固定为 `RESUME` 或 `QUESTION_BANK`。简历允许 PDF/DOCX；题库允许 PDF/DOCX/TXT/Markdown。应用不调用云解析服务。Web 应用由 Compose 提供，MinerU Worker 在开发机运行并只接受带独立随机令牌的请求。

```text
浏览器上传
  → Spring Boot 校验扩展名、MIME、大小、文件签名并写入私有 MinIO
  → PostgreSQL 保存 managed_documents + document_parse_tasks (PENDING)
  → DB 轮询 Worker 领取任务并将资料标为 PROCESSING
  → Worker 按格式直接读取文本或运行本机 MinerU
  → Spring Boot 结构化提取、保存 parsed_content/content、记录耗时
  → PARSED 预览、编辑、修正
  → 用户确认后 CONFIRMED，可作为面试来源
```

HTTP 上传只创建文件、资料和持久化任务，不等待 MinerU。前端轮询资料详情；task 的 `queuedAt`、`startedAt`、`completedAt`、`durationMs`、`retryCount`、`attemptCount`、错误码与安全错误信息均可查询。文档响应提供版本化 JSON 内容，简历使用 `interviewmirror.resume-content.v1`，题库使用 `interviewmirror.question-bank-content.v1`。

## 持久化与状态

Flyway `V2__create_managed_documents_and_parse_tasks.sql` 创建 `managed_documents` 和 `document_parse_tasks`，并使用复合外键将 document/file/task 与相同 owner 绑定。所有按 ID 读取都同时使用当前登录用户 ID；跨用户请求返回统一 404。

资料状态：

| 状态 | 说明 | 可用于面试 |
|---|---|---|
| `PENDING` | 已入队、等待 worker | 否 |
| `PROCESSING` | worker 持有有效租约 | 否 |
| `PARSED` | 解析完成，待用户审核；手工空题库也使用此状态 | 否 |
| `FAILED` | 解析失败，可重试 | 否 |
| `CONFIRMED` | 用户确认的当前内容版本 | 是 |
| `DELETING` | 已开始删除文件和资料 | 否 |
| `DELETE_FAILED` | 对象删除失败，资料仍保留并可重试删除 | 否 |

解析任务状态为 `PENDING`、`PROCESSING`、`SUCCEEDED`、`FAILED`、`CANCELLED`。确认仅接受有效 `PARSED` 内容；保存编辑使用 `contentVersion` 乐观锁；编辑已确认资料会递增版本并清除确认标记。题库确认至少包含一道非空题，简历确认至少有姓名或一项教育、经历、项目、技能内容。题库“可确认”与“可用于专项面试”是不同门槛：当前专项面试固定 6 道主问题，创建面试时由后端再次要求至少 6 道非空有效题目；1–5 道题的确认题库仍可编辑补题，但不能进入面试。

唯一面试资料入口为：

```text
GET /api/v1/interview-sources/resumes/{id}
GET /api/v1/interview-sources/question-banks/{id}
```

Service 层验证 owner、`CONFIRMED` 状态、`confirmedVersion == contentVersion` 和内容完整性；不能通过前端自行构造状态绕过。资料选择列表支持 `?usableOnly=true`。

## Worker、并发与恢复

Spring Boot 每两秒扫描 PostgreSQL 持久化任务。领取、状态迁移和完成写入都在数据库事务内；处理租约默认为三分钟。任务领取增加 `attemptCount` 并记录 `workerId`。解析完成或失败时锁定并复核当前 worker/attempt；租约已过期的旧 Worker 结果只记录 `STALE_*_IGNORED`，不能覆盖新尝试。

进程重启后，调度器会回收已过期的 `PROCESSING` 租约为 `PENDING`，保留可读的 `WORKER_INTERRUPTED` 信息并可继续处理。重试仅接受 `FAILED`，在数据库条件更新内把原 task 重置为 `PENDING` 并增加 `retryCount`；重复点击不会创建第二条任务。对 `PROCESSING` 资料删除返回冲突，避免解析与删除竞争。

## MinerU Worker

锁定工具链：Python 3.12.14、MinerU 4.0.10，依赖和模型校验沿用 `scripts/poc/python-toolchain.lock.json` 与阶段 0 本地模型。Windows 本地启动：

```powershell
Copy-Item .env.example .env # 首次初始化时执行
# 在 .env 设置独立的 MINERU_WORKER_TOKEN（至少 32 个随机字符）
pwsh -File .\scripts\phase2\start-mineru-worker.ps1
```

Windows 启动脚本将 Worker 绑定 `127.0.0.1`；Docker host gateway 允许 Compose 后端访问主机回环地址，Compose 本身不发布 Worker 端口。它使用至少 32 字符的独立随机令牌和固定时间比较。文件上限 20 MiB、PDF 最多 50 页、DOCX 展开总量最多 128 MiB/2000 项、解析输出最多 5 MiB、同一 Worker 同时处理一个 MinerU 命令。文本直接 UTF-8 读取；PDF 用 `basic` tier；DOCX 用 `flash` tier。PDF 使用全页参数，DOCX 不传 PDF 页范围参数。Worker 不记录文件名、正文、令牌或 MinerU 诊断正文。

错误映射向用户提供有限原因（格式不支持、文件无效、PDF 加密/页数超限、解析超时、Worker 不可用、解析失败），不返回 stack trace。详细日志只记录 task/document/user ID、失败类型、状态和耗时。

## 删除与补偿

数据库和 MinIO 不组成一个 ACID 事务。上传时先存文件，再建资料和任务；数据库创建失败时尝试删除对象，补偿也失败则返回明确错误。删除时先条件迁移到 `DELETING`，然后删除私有 MinIO 对象；存储删除失败会保留资料并标记 `DELETE_FAILED`，用户可再次删除。文件 API 拒绝独立删除仍关联资料的文件。删除资料会级联解析结果和任务，并移除对应存储对象。

## API

| 能力 | API |
|---|---|
| 简历上传/列表/详情/编辑 | `POST/GET /api/v1/resumes`，`GET/PUT /api/v1/resumes/{id}` |
| 简历确认/重试/删除 | `POST /api/v1/resumes/{id}/confirm`、`/retry`，`DELETE /api/v1/resumes/{id}` |
| 题库上传/列表/详情/编辑 | `POST/GET /api/v1/question-banks`，`GET/PUT /api/v1/question-banks/{id}` |
| 手工新建题库 | `POST /api/v1/question-banks/manual` |
| 题库确认/重试/删除 | `POST /api/v1/question-banks/{id}/confirm`、`/retry`，`DELETE /api/v1/question-banks/{id}` |
| 查询解析任务 | `GET /api/v1/parse-tasks/{id}` |

编辑资料的 PUT 请求包含 `title`、`contentVersion`、版本化 `content`。题库问题以数组保存 `{position, stem, answer, category?}`，用户可新增、编辑、删除题目后再确认。
