# 面镜 InterviewMirror

面向 AI 应用 / AI 全栈实习求职者的本地面试陪练应用。当前已具备本地登录、简历与自定义题库管理、综合/专项文字面试、PostgreSQL 持久化问答、异步面试报告和 PDF 导出。综合面试中的 JD 仅作为问题生成上下文；当前版本不提供岗位差异分析。模型调用默认关闭；启用后需在本地 `.env` 配置兼容模型服务，个人资料与回答会按界面提示发送给所配置的模型服务。项目只面向本地演示，不提供公网部署。

## 本地启动

运行 Web 应用只需要 Docker Desktop（含 Docker Compose v2），不需要本机安装 Java、Maven、Node 或数据库。首次构建需要网络拉取容器镜像和 Maven/npm 依赖。文档解析另需本机已安装阶段 0 锁定的 MinerU Python 运行环境和模型文件；本地 Worker 仅绑定回环/容器宿主网络，不使用托管解析服务。

```powershell
Copy-Item .env.example .env
docker compose up --build -d
docker compose ps
```

首次 Maven 构建可能需要几分钟。后端 Flyway 自动创建数据库结构，首次启动时自动创建私有对象桶和两个本地演示账号。应用健康后访问：

| 服务 | 地址 |
|---|---|
| Vue 前端 | http://127.0.0.1:5173 |
| 后端健康状态 | http://127.0.0.1:8080/actuator/health |
| PostgreSQL | `127.0.0.1:15432`（`.env` 中可调整） |
| MinIO Console | http://127.0.0.1:9001 |

演示账号（仅供本地开发）：

| 用户名 | 示例密码 |
|---|---|
| `demo1` 或 `demo1@local.interviewmirror` | `MirrorDemo1!` |
| `demo2` 或 `demo2@local.interviewmirror` | `MirrorDemo2!` |

可在本地 `.env` 覆盖演示密码及服务端口。`.env` 不纳入 Git。不要将默认示例凭证用于共享或公网环境。所有 Compose 宿主机端口都绑定到 `127.0.0.1`。开始文档解析前，在 `.env` 设置至少 32 字符的随机 `MINERU_WORKER_TOKEN`，并在独立 PowerShell 窗口运行：

```powershell
pwsh -File .\scripts\phase2\start-mineru-worker.ps1
```

Worker 启动时验证锁定的 MinerU 运行时及本地模型。文本题库直接读取 UTF-8 文本；PDF/DOCX 走本机 MinerU 4.0.10。Worker 绑定 `127.0.0.1`，由 Docker host gateway 转发供 Compose 后端访问，并要求独立随机令牌；Compose 不发布此端口。不要把端口改成公网监听或复用其他 API Key 作为访问令牌。没有运行 Worker 时，持久化任务会显示失败状态；启动 Worker 后在资料页点“重新解析”即可重试。

MinIO 管理台凭证为 `.env` 中的 `MINIO_ROOT_USER` 和 `MINIO_ROOT_PASSWORD`。产品文件桶为私有桶；前端不会获得 S3 凭证或永久/预签名对象 URL。Compose 使用官方归档源码固定 tag 构建 MinIO，不依赖已下架的 Community 镜像仓库或发布二进制。官方 MinIO Community 仓库已归档，源码构建产物不受上游支持，因此此配置仅适用于绑定到 loopback 的个人本地演示；不得复用于共享或公网服务。未来扩展使用前应切换到有持续维护的发行版或兼容对象存储，并重新执行对象访问权限与兼容性验收。

## 当前能力和边界

前端工程位于 `frontend/`：Vue 页面与 API 客户端在 `frontend/src/`，npm 清单、Vite 配置、Nginx 配置和前端镜像 Dockerfile 也都在该目录。Compose 从 `frontend/` 独立构建前端镜像。

- 用户名或邮箱登录、当前用户、退出登录；服务端 Session Cookie（HttpOnly、SameSite=Strict）与 CSRF 校验。
- `/api/v1/resources` 提供用户私有资源 CRUD；数据库查询、更新和删除均把认证用户 ID 放进 SQL 条件，跨账号不存在性统一返回 404。
- `/api/v1/files` 提供用户私有文件上传、metadata、下载和删除；服务端仅接受 PDF、DOCX、TXT 和 Markdown，并校验扩展名、MIME 类型及 PDF/DOCX 文件签名；文件大小上限 20 MiB；API 不返回对象 key，私有桶不能匿名直连。
- 简历和题库保存到 PostgreSQL，原始文件保存到私有 MinIO。状态包括 `PENDING`、`PROCESSING`、`PARSED`、`FAILED`、`CONFIRMED`、`DELETING` 和 `DELETE_FAILED`；页面支持解析预览、字段 / 项目编辑、题目增删、失败重试和删除。保存编辑后的资料会失效原确认，需要重新确认。
- 解析任务持久化在 PostgreSQL；Worker 领取任务使用租约和 attempt fencing，进程重启后回收过期任务，迟到的旧 Worker 结果不会覆盖新尝试。解析和对象存储不在单一事务内；MinIO 删除失败会保留 `DELETE_FAILED` 记录供用户重试。
- `/api/v1/interview-sources/resumes/{id}` 与 `/api/v1/interview-sources/question-banks/{id}` 在后端强制校验当前用户归属和 `CONFIRMED` 状态；资料列表也支持 `?usableOnly=true`。前端禁用状态仅用于交互，不能代替后端门禁。
- 面试结束后由数据库任务 worker 异步生成持久化报告；任务失败可重试，worker 过期租约可恢复。
- 报告保留总体评价和逐题回顾，按六个能力维度评分；不再单独展示一句话结论或“协作方式匹配”。证据 ID 由服务端对本场快照校验。缺少回答证据的评分为 `UNASSESSED`，提前结束会明确提示覆盖不完整。
- 综合面试可填写 JD，供问题生成参考；岗位差异分析已停用。
- 历史报告、报告详情、雷达图、重新面试和 PDF 导出均使用当前登录用户的 owner-scoped API。PDF 中文多页生成后存入私有 MinIO，并在下载时复验归属。
- 仅本地演示；不提供公网部署、注册、邮件验证或生产级账号管理。

主要 API：

```text
GET    /api/v1/auth/csrf
POST   /api/v1/auth/login
GET    /api/v1/auth/me
POST   /api/v1/auth/logout
GET    /api/v1/resources?type=RESUME
POST   /api/v1/resources
GET    /api/v1/resources/{uuid}
PUT    /api/v1/resources/{uuid}
DELETE /api/v1/resources/{uuid}
GET    /api/v1/files
POST   /api/v1/files                 multipart field: file
GET    /api/v1/files/{uuid}
GET    /api/v1/files/{uuid}/content
DELETE /api/v1/files/{uuid}
GET    /api/v1/resumes?usableOnly=false
POST   /api/v1/resumes                 multipart field: file
GET    /api/v1/resumes/{uuid}
PUT    /api/v1/resumes/{uuid}          contentVersion 乐观锁
POST   /api/v1/resumes/{uuid}/confirm
POST   /api/v1/resumes/{uuid}/retry
DELETE /api/v1/resumes/{uuid}
GET    /api/v1/question-banks?usableOnly=false
POST   /api/v1/question-banks          multipart field: file
POST   /api/v1/question-banks/manual
GET    /api/v1/question-banks/{uuid}
PUT    /api/v1/question-banks/{uuid}
POST   /api/v1/question-banks/{uuid}/confirm
POST   /api/v1/question-banks/{uuid}/retry
DELETE /api/v1/question-banks/{uuid}
GET    /api/v1/parse-tasks/{uuid}
GET    /api/v1/interview-sources/resumes/{uuid}
GET    /api/v1/interview-sources/question-banks/{uuid}
GET    /api/v1/interviews/{uuid}/report-status
POST   /api/v1/interviews/{uuid}/reports
POST   /api/v1/interviews/{uuid}/reports/retry
DELETE /api/v1/interviews/{uuid}/report
GET    /api/v1/reports
GET    /api/v1/reports/{uuid}
GET    /api/v1/reports/{uuid}/pdf
```

旧版差异分析 API 已停用并返回 `410 Gone`；新的报告任务不会创建差异分析任务，PDF 也不再包含差异分析内容。

报告 API、证据契约、状态机和失败重试说明见 [`docs/phase4/PHASE4-SUMMARY.md`](docs/phase4/PHASE4-SUMMARY.md)、[`docs/phase4/REPORT-PIPELINE.md`](docs/phase4/REPORT-PIPELINE.md)。真实模型质量评测只使用合成数据，执行方法见 [`docs/phase4/EVALUATION.md`](docs/phase4/EVALUATION.md)；基础 Maven 测试用确定性替身，不代表真实模型效果。

## 停止与清理

```powershell
docker compose down
```

清除数据库、账号、资料和对象文件并回到首次启动状态（**会删除本机应用数据**）：

```powershell
docker compose down --volumes --remove-orphans
```

## 开发与测试

本机开发需要 Node.js 22、npm、Java 21、Maven 3.9+、Docker Desktop。

```powershell
npm ci --prefix frontend
npm test --prefix frontend
npm run build --prefix frontend
mvn -B -f backend/pom.xml test
docker compose config --quiet
```

本地启动整个应用仍使用 `docker compose up --build -d`。后端集成测试使用 H2 和内存对象存储替身验证两用户的资源、文件访问隔离；本机 Compose 验收还应验证真实 PostgreSQL、MinIO 和各 HTTP API。CI 在干净 GitHub Actions runner 上执行前端测试/构建、后端测试和 Compose 配置校验。

阶段 2 MinerU 全量评测要求已安装并启动本地 Worker、Compose 服务健康、`.env` 中 `DEMO2_PASSWORD` 与服务端一致：

```powershell
pwsh -File .\scripts\phase2\run-evaluation.ps1 -PerDocumentTimeout 600
scripts\poc\.mineru\Scripts\python.exe scripts\phase2\export_evaluation_snapshot.py
```

评测脚本验证锁定 Python / 数据集 SHA，对 30 份合成样例逐份走真实上传、任务、解析与结构化保存，原始结果写入被 Git 忽略的 `data/poc/results/phase2/live-evaluation.json`，可审阅快照保存在 `docs/phase2/results/live-evaluation.json`；脚本只删除本轮创建的资料，并会报告清理失败。当前数据集为受控合成样例，不含真实个人信息；尚未由独立第二位评审者复核全部标注，真实简历分布上的泛化能力仍需后续授权样本验证。详见 [`docs/phase2/EVALUATION.md`](docs/phase2/EVALUATION.md) 和 [`docs/phase2/PARSING-PIPELINE.md`](docs/phase2/PARSING-PIPELINE.md)。

Compose 健康后可执行真实服务 smoke test（脚本会创建并在末尾删除一条合成资源和文件）：

```powershell
pwsh -File .\scripts\phase1-smoke.ps1
```

架构和本地操作说明见 [`docs/phase1/LOCAL-DEVELOPMENT.md`](docs/phase1/LOCAL-DEVELOPMENT.md)。

## 阶段 5 本地演示与验收

阶段 5 使用独立 Compose project `interviewmirror-phase5`，映射前端 `15173`、后端 `18080`、PostgreSQL `25432`、MinIO `19000/19001`，不会复用或清理默认 `interviewmirror-local` 的数据卷。演示流程见 [`docs/phase5/LOCAL-DEMO.md`](docs/phase5/LOCAL-DEMO.md)，评审演示步骤见 [`docs/phase5/DEMO-SCRIPT.md`](docs/phase5/DEMO-SCRIPT.md)，常见故障见 [`docs/phase5/TROUBLESHOOTING.md`](docs/phase5/TROUBLESHOOTING.md)，真实执行命令和数据集见 [`docs/phase5/EVALUATION.md`](docs/phase5/EVALUATION.md)，本轮实测数据和边界见 [`docs/phase5/PHASE5-SUMMARY.md`](docs/phase5/PHASE5-SUMMARY.md)。

```powershell
pwsh -File scripts/phase5/preflight.ps1
pwsh -File scripts/phase5/start-demo.ps1
pwsh -File scripts/phase5/seed-demo.ps1
pwsh -File scripts/phase5/check-demo.ps1
```

Phase 5 的脚本会输出真实服务状态、三次面试时长、普通 API p95/p99、模型 SSE TTFT、报告 worker 时长和 usage 成本结果。真实模型脚本使用合成提示词，默认需显式 `-RunModelCalls` 以确认可能产生的费用。冷环境 15 分钟验收必须在无 Docker/依赖缓存的新开发环境计时；当前机器不执行全局 Docker 缓存清理。性能、价格假设和 PDF 视觉验收见 [`docs/phase5/PERFORMANCE-AND-COST.md`](docs/phase5/PERFORMANCE-AND-COST.md) 和 [`docs/phase5/PDF-VISUAL-REVIEW.md`](docs/phase5/PDF-VISUAL-REVIEW.md)。Phase 5 演示账号仍使用 `.env` 中的本地 `demo1` / `demo2`。
