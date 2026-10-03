# 阶段 2：简历与题库管理总结

## 交付能力

- 简历和自定义题库上传到用户私有 MinIO，PostgreSQL 持久化资料、结构化解析内容、内容版本和解析任务。
- MinerU 4.0.10 通过本机 Python Worker 异步处理 PDF/DOCX；题库 TXT/Markdown 直接读取。前端轮询状态，支持预览、编辑、补题、确认、失败重试和删除。
- 状态迁移由后端执行。资料编辑使用乐观版本检查，修改已确认内容后会撤销确认。面试来源 API 再次检查资料 owner、确认状态、确认版本和内容完整性。
- Worker 使用 PostgreSQL 租约领取任务；应用重启可回收过期任务，重复 Retry 不会创建重复任务，迟到 Worker 不能覆盖更新 attempt。MinIO 删除失败留下可重试的 `DELETE_FAILED` 资料。
- 用户资源、任务和文件读取均绑定认证主体。阶段 1 的文件扩展名、MIME 与文件签名校验保持有效。

## 评测

2026-10-03 在 Windows 11 x64 本地机器用真实 MinerU 4.0.10 跑了 30 份合成标注文档：15 份简历（8 PDF/7 DOCX）、15 份题库（4 PDF/2 DOCX/5 Markdown/4 TXT）。loopback-only Worker 最终复测 30/30 解析成功。简历字段 Precision、Recall、F1 均为 100%；题库 45/45 题匹配，Recall 100%，预测 49 题、Precision 91.84%。端到端解析 p50 5.758 秒、p95 16.039 秒、max 19.389 秒。

Q05 阶段 0 双栏题库本轮 3/3 命中。Q11 表格以 HTML 输出，新增 HTML 表格提取逻辑后 3/3 命中。完整指标和输入/任务摘要写在本机忽略文件 `data/poc/results/phase2/live-evaluation.json`；同时提供去除随机任务 ID 的可审阅快照 [`results/live-evaluation.json`](results/live-evaluation.json)。

**评测证据边界：**30 份数据均为合成数据并附 ground truth；阶段 0 的 20 份样例被复用，其余 10 份由锁定脚本生成。标注是和样本一同定义的受控预期值，本次未安排独立第二位人工评审逐份盲标，也未使用授权真实简历。因而量化门槛在受控基准上通过，但不能据此宣称真实简历的代表性或泛化已验证。独立标注复核应在对外或真实求职者资料试用前完成。

## 测试与工程验收

- Backend Docker/Maven：7/7 测试通过，涵盖登录与 Stage1 资源/文件隔离、文件类型校验、简历与题库生命周期、确认门禁、编辑失效、Retry/recovery/stale worker fencing 和对象删除失败补偿。
- Frontend：`npm test` 7/7 通过；`npm run build` 通过。新增测试验证简历项目技术栈、成果及原始来源字段在编辑保存时保留。
- Stage0 Python scorer regression：待最终复跑记录；已修复的最大基数题目匹配与来源校验测试保留。
- `docker compose config --quiet` 通过；后端、前端、PostgreSQL、MinIO 为 healthy。V2 Flyway migration 已在保留既有本地卷的情况下应用。
- `scripts/phase1-smoke.ps1` 通过：两账号登录、资源/文件读写隔离、非法类型拒绝、匿名对象读取拒绝和 finally 清理验证均通过。
- CI 配置新增语料验证 job；本地检查可重复运行。此工作轮次没有观察 GitHub 托管 Runner 的实际结果。

## 阶段门槛状态

| 门槛 | 结果 |
|---|---|
| ≥30 份带 ground truth 的样本 | PASS（30；合成，不含真实个人信息） |
| 简历字段 F1 ≥90% | PASS（100%） |
| 题库 Recall ≥90% | PASS（100%） |
| 解析 p95 ≤60 秒 | PASS（16.039 秒） |
| 失败后 Retry / 应用重启恢复 | PASS（后端集成测试） |
| 未确认资料不可作为面试来源 | PASS（后端 API 和集成测试） |
| 双账号所有权、文件访问隔离 | PASS（后端集成测试 + 本机 Stage1 smoke） |

工程功能验收门槛通过；数据独立人工复核和托管 CI 运行证据仍未取得，结论需保留这两项限制。该结果只代表本地演示工程，不是生产上线批准。

## 主要命令

```powershell
docker compose config --quiet
docker compose up --build -d
npm test
npm run build
docker run --rm -v interviewmirror-stage2-maven-cache:/stage2m2 -v "${PWD}/backend:/workspace" -w /workspace interviewmirror-stage2-build sh -lc 'mvn -Dmaven.repo.local=/stage2m2 -o -B test'
scripts\poc\.mineru\Scripts\python.exe scripts\phase2\verify_eval_dataset.py
pwsh -File .\scripts\phase2\run-evaluation.ps1 -PerDocumentTimeout 600
scripts\poc\.mineru\Scripts\python.exe scripts\phase2\export_evaluation_snapshot.py
pwsh -File .\scripts\phase1-smoke.ps1
```

完整处理、状态、接口与补偿规则见 [`PARSING-PIPELINE.md`](PARSING-PIPELINE.md)；指标方法和逐样本复现信息见 [`EVALUATION.md`](EVALUATION.md)。
