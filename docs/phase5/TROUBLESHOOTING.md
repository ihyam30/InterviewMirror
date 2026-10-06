# 阶段 5 本地故障排查

先运行：

```powershell
pwsh -File scripts/phase5/preflight.ps1
docker compose -p interviewmirror-phase5 ps
```

Preflight 不打印 API key、Worker token 或数据库密码。

## Docker、端口与服务健康

- Docker Engine 不可达：启动 Docker Desktop，等待 Engine ready 后重跑 preflight。
- 端口冲突：Phase 5 默认使用 `15173`、`18080`、`25432`、`19000`、`19001`。先查明占用者，不要清理其他 Compose 项目或运行全局 prune。
- 服务未 healthy：运行 `docker compose -p interviewmirror-phase5 ps` 和 `docker compose -p interviewmirror-phase5 logs --tail 100 backend postgres minio frontend`。确认 `.env`、数据库健康检查和 MinIO 初始化日志。
- 页面可开但登录失败：运行 `scripts/phase5/check-demo.ps1` 检查 CSRF、账号和 `/me`；确认 seed 已执行且 `DEMO1_PASSWORD` 与本地账号初始化配置一致。

## 模型调用或报告没有完成

- 确认 `.env` 中模型已启用、API key 有效、OpenAI 兼容 Base URL 路径正确（通常以 `/v1` 结尾），并且账户可用额度足够。不要把 key 粘贴到日志或报告。
- 查看 `docker compose -p interviewmirror-phase5 logs --since 10m backend` 中的 `interview_llm`、`report_llm`、`model_usage` 和 `report_task` 事件。
- 报告处于排队/生成中时可从历史报告刷新状态。任务失败后只用页面重试；多次失败先修正原因，避免重复计费。
- 如果日志没有 `usageReported=true`，该批成本统计不完整，不能据部分 token 估算声称成本通过。

## MinerU 本地 Worker

预置资料已确认，可直接演示面试；只有上传并解析 PDF/DOCX/MD 等新文档时才需要 Worker。Worker 默认只监听回环地址 `127.0.0.1:8765`。

```powershell
pwsh -ExecutionPolicy Bypass -File .\scripts\phase2\start-mineru-worker.ps1
Invoke-RestMethod http://127.0.0.1:8765/healthz
```

健康端点应返回 `status: ok` 和 MinerU 版本。若启动脚本提示 runtime/model 缺失，按 [`docs/phase0/MINERU-POC.md`](../phase0/MINERU-POC.md) 检查固定 Python 运行时和本地模型；若提示 token 缺失，在 `.env` 配置至少 32 字符的本地 `MINERU_WORKER_TOKEN`，不要复用模型 API key。浏览器中的“本地解析服务不可用”通常表示 Worker 没启动、8765 端口被占用、token 不一致或 Worker 解析超时。

## PDF 和重置

- PDF 只能在报告 READY 后导出。若返回失败，检查 report owner 会话、MinIO health 和 Backend 日志；不要直接暴露 MinIO 对象 URL。
- 要恢复标准演示数据，使用 [`LOCAL-DEMO.md`](LOCAL-DEMO.md) 中带固定项目名与确认字符串的 reset 命令，然后重新 start、seed 和 check。
- Reset 会删除 Phase 5 专属数据库和 MinIO volume。它不会触碰默认 `interviewmirror-local` 或其他 Docker 项目，也不会执行系统级清理。
