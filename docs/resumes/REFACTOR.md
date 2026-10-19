# 简历模块

简历使用独立的 `resumes` / `resume_analyses` 表和 `/api/v1/resumes` 接口。
题库继续使用 `managed_documents`、MinerU 和原有确认流程。

## 启动与开发数据

V7 为新简历模型建立约束，不迁移旧 `managed_documents` 简历。
已有旧简历关联的开发库需要先重置。本次代码修改不自动删除正在使用的数据库、MinIO 数据或 Docker 卷。
使用新的 Compose 项目名可直接得到隔离的新数据库、MinIO 和 Redis 卷；为 PostgreSQL、MinIO、前端、后端设置空闲端口后执行：

```powershell
docker compose -p interviewmirror-resumes up -d --build
```

只清理这个开发项目时使用 `docker compose -p interviewmirror-resumes down --volumes`。
该命令会删除此项目的全部开发数据，不要对其他项目名执行。

后端镜像安装 Tesseract、`chi_sim`、`eng` 和中文 PDF 字体。简历解析不依赖 MinerU；题库仍需要它。
本地直接运行 Java 时，需要自行安装 Tesseract 和相应语言包；`TESSERACT_PATH` 指向可执行文件所在目录。
PDF 要求 OCR 环境就绪，避免混合 PDF 中的扫描页被静默跳过。

Redis 默认使用 `localhost:6379`，可通过 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` 配置。
Compose 使用私有网络中的 Redis 和 AOF 持久化，不发布 Redis 端口。
同步解析预算默认两分钟，Nginx 接口超时为 150 秒。

## 配置与接口

配置集中于 `interviewmirror.resume` 和 `ResumeProperties`：

| 环境变量 | 默认值 |
|---|---|
| `RESUME_ALLOWED_EXTENSIONS` | `pdf,doc,docx,txt` |
| `RESUME_PARSE_TIMEOUT` | `PT2M` |
| `RESUME_PARSE_CONCURRENCY` | `2` |
| `RESUME_TEXT_LIMIT` | `100000` |
| `RESUME_PAGE_LIMIT` | `50` |
| `RESUME_OCR_LANGUAGES` | `chi_sim+eng` |
| `RESUME_MODEL_TIMEOUT` | `PT180S` |
| `RESUME_PENDING_TIMEOUT` | `PT10M` |
| `RESUME_PROCESSING_TIMEOUT` | `PT15M` |
| `RESUME_UPLOAD_GLOBAL_RATE` | `60` / 分钟 |
| `RESUME_UPLOAD_IP_RATE` | `5` / 分钟 |
| `RESUME_UPLOAD_USER_RATE` | `5` / 分钟 |
| `RESUME_WORKER_ENABLED` | `true` |

文件最大 10 MiB。实际 MIME 和扩展名均需匹配。空文本、损坏、加密、超限或超时直接拒绝，解析失败不会保存待分析简历。
解析在受控 JVM 子进程执行；超时终止其 OCR 子进程并清理临时目录。普通文本由 Tika 提取，PDF 使用自动 OCR。
子 JVM 通过 `-Djava.io.tmpdir` 指向本次解析的 job 目录，并继承 `TMPDIR`/`TEMP`/`TMP`；关闭 `UsePerfData`，避免 Windows
在隔离目录生成权限不兼容的 `hsperfdata`。因此 Tika 与 Tesseract 的中间文件
在解析成功、失败或强杀超时三种情况下都不会留在系统临时目录；父进程先等主进程退出、再强杀进程树，最后递归清理 job 目录。
`ResumeParseIsolationTest` 以解析前后快照取差集断言这一点（宿主机无 Tesseract 时跳过其中两条 OCR 用例），
`smoke.py` 另外断言容器 `/tmp` 内不得残留解析中间目录。

上传为 `POST /api/v1/resumes/upload`，multipart 字段为 `file`。
列表为 `GET /api/v1/resumes?page=0&size=20&usableOnly=false`，返回 `{items,total,page,size}`，size 最大 100。
详情为 `GET /api/v1/resumes/{id}/detail?analysisPage=0&interviewPage=0&size=20`，返回 `{resume,analyses,interviews}`。
预览、原件、最新成功评分 PDF 分别为 `/{id}/preview`、`/{id}/file`、`/{id}/export`。
重新评分为 `POST /{id}/reanalyze`，等待/处理中返回 409；删除为 `DELETE /{id}`，返回 202 和删除状态。

全部接口通过登录会话取得用户 ID；不存在或非本人记录返回 404。
文件按 `(user_id,file_hash)` 去重；重复上传只增加上传计数，不自动重试失败评分。列表和详情不增加计数。
解析成功即可面试，评分状态不作为可用性门槛；面试快照保存完整正文。

## 评分与证据

Prompt 版本为 `resume.technical-audit.v1`。项目 40、技能 20、内容 15、结构 15、表达 10，后端计算总分。
没有 AI 摘要字段，不在数据库、API、前端或 PDF 保存/展示摘要。

后端按正文顺序生成本次分析的完整证据目录，不沿用面试报告的 250 条上限。
模型仅选择 `evidenceIds`，后端回填 `evidenceId/sourceType/sourceId/sourceLocation/quote/sha256`。
位置格式为 `resume_text:start:end`，偏移采用 Java UTF-16 字符索引、结束位置不包含在引用内。
每条优点和 ISSUE 类型建议必须有已知证据 ID；SUPPLEMENT 建议可无引用，并明确为需要补充的信息。
引用原文、位置和哈希始终由后端生成。引用存在不代表推断一定正确，仍要求 Prompt 保守判断并向用户展示原文。

评分历史保存回填后的证据快照；重新分析不改旧历史。异常不生成 0 分成功记录。
结构化输出校验失败最多重新生成一次；传输失败由任务层处理，初次执行后最多重试三次。

## 任务与删除

简历和投递记录同事务提交；Redis Stream 消息只有 `resumeId` / `retryCount`。
消息允许重复，数据库原子领取、执行代次及分析代次唯一约束阻止过期/重复写入。
内部字段 `analyze_retry_count` 保存本轮任务重试次数，必须与消息次数匹配才能领取，防止旧消息绕过重试上限。
保存结果和完成状态同事务提交；领取、状态变化和心跳更新最后进展时间。
每分钟扫描超时任务，自动恢复最多三次；手动重新评分清零恢复次数。

删除状态独立于评分状态。开始删除后禁止新面试及后台结果写入，登记原件和报告 PDF 清理任务。
MinIO 全部清理成功后删除关联面试、回答、事件、报告、分析和简历，并释放图检查点。
图检查点写入与面试删除共享数据库行锁，防止过期图任务恢复已删状态。
对象清理失败保留元数据并自动重试；再次点击删除可立即安排重试。
上传/报告 PDF 写入前登记十分钟延迟补偿任务，覆盖部分写入和进程中断；成功事务取消补偿。

日志不输出完整简历。Micrometer 指标包括 `resume.outbox.pending`、`resume.cleanup.failed`、`resume.recovery.count`。
轮询失败按阶段区分：`stage=outbox` 表示数据库侧，`stage=stream` 表示 Redis 侧，并记录连续失败次数；
连续失败时按次数退避（最多 30 秒），不会每 2 秒空转。线程池拒绝的消息留给后续轮询，不静默丢弃。

## 验收

```powershell
cd backend
mvn test
cd ../frontend
npm test
npm run build
```

隔离基础设施验收使用固定模型响应，不发送真实简历到外部服务：

```powershell
docker build -t interviewmirror/resume-validation:local backend
python scripts/resumes/smoke.py
```

样本生成需要 Pillow 和 reportlab。脚本创建随机命名的 PostgreSQL、Redis、MinIO、后端及 Docker 网络，退出时只删除自己创建的资源。覆盖打包后的解析 JVM、扫描 PDF OCR、消息消费、用户隔离、证据回填、评分失败重试、PDF、删除及限流。
失败诊断日志位于 `backend/target/resume-smoke-backend.log`。
