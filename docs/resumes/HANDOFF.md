# 简历重构交接（2026-10-15 更新，接续开发已完成）

## 当前状态

**核心代码、前端、最终镜像与端到端 smoke 验收全部完成。** 上一轮交接时“最终端到端验收尚未完成”
的状态已经结束：本轮补齐了解析临时目录隔离、重建了最终镜像，并让 `scripts/resumes/smoke.py`
五个阶段全部通过。

所有修改仍保留在当前工作区，**未提交或推送 Git**。不要覆盖用户原有的未跟踪目录 `docs/interview/`。

先读本文和同目录 `REFACTOR.md`。本轮另有一份 `BLOCKER-windows-write-access.md`，
记录会话一度被 Windows 文件权限挡住的现象、根因和解决方式（已解决）。

## 用户已确定的要求

- MinIO + PostgreSQL；复用现有用户认证，新增表使用 `user_id`，同用户内 `(user_id,file_hash)` 去重。
- 简历用 Tika + TesseractOCRParser；不再用 MinerU。题库保留原流程。
- 同步提取正文，异步 Redis Stream 评分；解析成功即可面试，取消简历确认。
- 保留规则生成的结构化卡片和完整正文预览。
- **不要 AI 摘要：数据库、模型输出、API、页面、PDF 均不添加 summary。** HTML 的 `<summary>` 是证据展开控件，与 AI 摘要无关。
- 模型只返回证据 ID，后端按本次完整正文目录回填原文、位置、哈希，随评分历史保存。
- 参考项目 Prompt 权重：项目40、技能20、内容15、结构15、表达10；总分由后端计算。
- 重复上传失败记录只返回失败，手动重新评分；删除连带清理面试、回答、报告及对象文件。
- 沿用 `/api/v1`；旧开发数据允许重置，不迁移旧简历。

## 已实现

### 后端

- `backend/.../resumes/` 独立模块：Controller、Service、Repository、解析子进程、评分、证据目录、Redis 消费/恢复/心跳/清理、配置、限流和指标。
- `V7__create_resume_module.sql`：主表、评分历史、可靠投递记录、对象清理任务；面试归属约束及删除标记。旧库有关联旧简历时需先重置或使用新 Compose 项目，不能直接升级保留旧 ID。
- 新增内部 `analyze_retry_count`，消息重试次数必须匹配数据库，防止旧消息绕过重试上限；结果与完成状态同事务提交，执行代次唯一。
- 模型异常进入任务重试，不保存错误 0 分。格式修复一次，SDK重试显式0，任务重试最多3次。
- SHA-256 用户内去重、10MiB限制、实际MIME检测、Tika/OCR子JVM、两分钟预算、文本/页数限制、有界解析并发。
- 从原文切分完整证据目录，不使用旧250条上限；strengths/suggestions存后端回填证据，无摘要。
- 分页、批量最新分析/面试数量查询；详情历史分别分页；中文分析PDF和原文件下载。
- 面试保存完整正文快照，适配报告证据目录。
- 两阶段删除：停止结果写入，持久化文件清理，最后删除数据库关联与检查点。报告PDF上传也登记补偿，MinIO调用总超时2分钟。
- `ResumeAwarePostgresSaver` 用面试行锁限制检查点写入；屏蔽库内部commit/rollback，由Spring事务管理；直接删除LG4JThread及级联检查点，不能仅release（release会保留数据）。
- 对旧正文回填后重新读取记录时校验原执行代次，防止借用恢复后的新代次；心跳活动表按attemptId索引。

### 本轮新增（临时目录隔离与可诊断性）

- **解析子 JVM 临时目录隔离**：`ResumeParser` 给子 JVM 加 `-Djava.io.tmpdir=<job目录>/tmp`，
  并给子进程设置 `TMPDIR`/`TEMP`/`TMP`；同时关闭 JVM perf-data，避免 Windows 在隔离目录里创建父进程无权删除的 `hsperfdata`。
  强杀超时子进程不再把 Tika/OCR 中间文件留在系统临时目录，新增了不依赖 OCR 的强杀清理用例。
- **进程树回收顺序**：清理前先等待主进程退出，再用新的 `kill(...)` 统一强杀进程树并轮询到全部不再是存活状态
  （最多3秒），避免 OCR 子进程仍持有 job 目录句柄导致递归删除失败。Windows 强杀回归还发现子 JVM 会创建 ACL 不兼容的 `hsperfdata` 目录，现通过 `-XX:-UsePerfData` 禁止创建，并已验证没有新临时目录残留。
- **worker 可诊断性**：`ResumeWorker.poll` 拆分为 outbox 与 stream 两段，日志区分 `stage=outbox|stream`
  并记录连续失败次数；连续失败时退避（最多30秒），不再每2秒空转；`submit` 捕获
  `RejectedExecutionException`，被拒绝的消息留给后续轮询而不是静默丢弃。
- `.env.example` 已补充 `RESUME_PENDING_TIMEOUT` / `RESUME_PROCESSING_TIMEOUT`，并修复了 outbox 与 Redis 失败计数互相清零的问题。
- 面试选择器现在有独立的可用简历分页，超过首屏的简历也可选；当前选择缓存与删除状态同步。
- 只有扩展名的文件名会保留原文件名作为标题；`DELETE_FAILED` 状态在简历卡片显示明确的“重试删除”按钮。

### 前端和部署

- Vue页面改用新接口，取消确认入口；列表分页、原文/卡片、评分历史、引用展开、面试历史、重评、下载和删除进度。
- 新增 `frontend/src/ResumeAnalysis.vue`，分页/详情适配在 `api.js`。
- Compose增加Redis/AOF及可选密码；后端镜像增加Tesseract中英文语言包；Nginx接口超时150秒。
- `.env.example`、`REFACTOR.md`包含配置与启动说明。

## 已有验证结果

### 本轮实测（2026-10-15，全部为真实运行结果）

- **后端完整回归：16 个测试类 / 77 tests / 0 failures / 0 errors / 5 skipped。**
  5 个 skip = 3 个原有条件跳过 + 2 个新增 OCR 隔离用例（宿主机未安装 Tesseract 时跳过，
  在镜像内由 smoke 覆盖）。
- **前端 `npm test` 17 tests 全通过；`npm run build` 生产构建通过；`docker compose config --quiet` 通过。**
- **`docker build -t interviewmirror/resume-validation:local backend` 构建成功**
  （含本轮 worker 改动，为当前最终镜像）。
- **`python scripts/resumes/smoke.py` 五个阶段全部 PASS**（脚本自身清理容器/网络/卷，退出码 0）：
  1. 同步提取、评分与后端证据回填；
  2. 扫描 PDF OCR、Word97 提取，**并新增断言：容器 `/tmp` 内不得残留
     `interviewmirror-resume-*` / `apache-tika-*`**（验证临时目录隔离在真实 OCR 环境成立）；
  3. 评分失败保留历史并到达重试上限；
  4. 面试完整正文快照与图检查点围栏；
  5. 打包后 Tika/OCR、PostgreSQL、Redis、MinIO、用户隔离、PDF、删除与限流。
- `git diff --check` 通过，仅有 Windows 换行提示。

### 新增测试

- `ResumeParseIsolationTest`：成功解析、强制超时终止、真实 OCR 三条路径都不得在系统临时目录
  留下新的 `interviewmirror-resume-*` / `apache-tika-*`。判定方式为解析前后快照取差集，
  因此不会把历史残留误报成本次泄漏。真正需要 Tesseract 的两条用例在宿主机上按条件跳过。
- 测试夹具 `backend/src/test/resources/resumes/scanned-resume.pdf`：本仓库生成的无文本层单页扫描件，
  说明见同目录 `README.md`。

### 此前已有（仍然有效）

- 已真实运行宿主机 Tika 子进程测试：TXT中英文、DOC（Apache POI官方测试文件）、DOCX、文本超限、极短超时。
- 隔离真 PostgreSQL 已成功执行 V1–V7；Redis、MinIO、后端健康启动。

## 独立代码审查结论（本轮，只读）

对本轮代码做了一次独立审查（逐条对照 REFACTOR.md 要求，并用 `javap` 核对 jar 内真实行为），结论：

- **没有任何会让 smoke 失败或损坏数据的确凿缺陷。**
- 已核对正确：claim/complete/fail/recover/beginDelete/finishDelete 的事务边界、
  `analyze_retry_count` 匹配规则、`attempt_id` 执行代次围栏、证据 UTF-16 偏移与高位代理保护、
  两处证据目录都已去掉 250 上限、图检查点先擦除后删行、补偿任务先登记后写入。
  `ResumeAwarePostgresSaver` 与 langgraph4j-postgres-saver 1.8.27 反汇编比对一致。
- 要求项逐条确认满足：无 AI 摘要、模型只回 ID 且后端回填、40/20/15/15/10 且总分由后端计算
  （V7 还有 CHECK 兜底）、重复上传不自动重评、删除连带清理、用户内去重、非本人记录 404、日志不含完整正文。
- 审查一度报告的“分页 total 未应用 usableOnly”是审查方自己的误读，已由审查方撤回；
  `ResumeService.java:62` 与 `ResumeRepository.java:57-59` 是一致的。
  `ResumeRepository.java:182` 的 `cleanup_kind='DELETE'` 过滤实际已存在，该条也作废。

## 接续工作优先级

1. **UI 实际浏览器验收仍未做。** 本轮补齐了静态核查：证据展开用
   `<details><summary>`、重评按钮、删除状态文案（`删除待重试`）、列表与详情三处分页控件都在；
   所有 `确认` 文案都只属于题库/资料流程，简历确认入口确实已取消。但本机没有 Playwright/Puppeteer
   等浏览器自动化工具，未做真人点击验证。建议后续用真实浏览器过一遍：历史分页、证据展开、
   重评、删除失败重试。
2. **不要未经用户指示推送。**

## 联调经历与诊断

- 本轮首次跑 `ResumeParseIsolationTest` 时失败，原因是**上一轮崩溃遗留**在系统临时目录的
  `interviewmirror-resume-*`（内含 `input` 与 `process.log`）——这正是旧代码清理失败的现场证据。
  清理该遗留并把断言改为“前后快照取差集”后全部通过。
- 此前该用例耗时 60 秒以上，是因为清理重试循环反复重试一个仍被占用的目录；修正进程树回收顺序后
  降到 7 秒级。
- 会话一度因 Windows 完整性标签问题完全无法写入工作区子目录，详见
  `BLOCKER-windows-write-access.md`（已由用户切到完全权限解决）。
- 更早的联调经历：第一次smoke未启用local profile导致401（已修）；第二次构建镜像时后端初始化超过150秒
  （已把启动等待改为360秒、JVM 768MiB，并采集线程诊断）；Ubuntu HTTP软件源EOF（Dockerfile已改HTTPS+重试）。

## 命令与路径

当前目录 `D:\ai-project\InterviewMirror`，PowerShell。
Maven不在PATH，使用 `D:/maven/apache-maven-3.9.11/bin/mvn.cmd`；缓存 `D:/maven/m2-repository`。

```powershell
cd backend
& 'D:/maven/apache-maven-3.9.11/bin/mvn.cmd' -q test
cd ../frontend
npm test
npm run build
cd ..
docker compose config --quiet
docker build -t interviewmirror/resume-validation:local backend
python scripts/resumes/smoke.py
```

需要参考原项目时只读 `D:\project\interview-guide-master`。用户已经明确了方向，不必重新询问OCR、摘要、证据、确认环节或删除范围。
