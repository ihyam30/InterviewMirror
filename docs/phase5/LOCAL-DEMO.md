# 阶段 5 本地演示环境

Phase 5 的验收环境使用独立 Compose 项目 `interviewmirror-phase5` 和独立宿主机端口，数据库与 MinIO 卷不会复用或删除常规 `interviewmirror-local` 项目中的数据。所有脚本固定拒绝其他 Compose 项目名称。

## 前置条件

- Windows 11、PowerShell 7、Docker Desktop / Compose v2、Node.js 22、k6。
- 仓库根目录 `.env`；演示账号仅用于本机。
- 要演示文档解析时，还需要阶段 0 锁定的本地 MinerU 4.0.10 运行时、模型文件和本机 Worker。
- 要演示面试与报告生成，需要配置兼容模型服务。脚本不会输出任何 API Key、Worker token 或数据库密码。

### 启动本地 MinerU Worker

上传 PDF/DOCX 简历或题库前，先在仓库根目录 `.env` 配置随机 `MINERU_WORKER_TOKEN`，再单独打开 PowerShell 窗口运行：

```powershell
pwsh -ExecutionPolicy Bypass -File .\scripts\phase2\start-mineru-worker.ps1
```

Worker 应报告 MinerU `4.0.10` 并只监听 `127.0.0.1:8765`；健康地址为 `http://127.0.0.1:8765/healthz`。上传解析依赖该 Worker。只使用预置合成简历和题库进行面试演示时不需要 Worker。

## 启动与预置

```powershell
pwsh -File scripts/phase5/preflight.ps1
pwsh -File scripts/phase5/start-demo.ps1
pwsh -File scripts/phase5/seed-demo.ps1
pwsh -File scripts/phase5/check-demo.ps1
```

Phase 5 端口如下：前端 `http://127.0.0.1:15173`、后端 `http://127.0.0.1:18080`、MinIO Console `http://127.0.0.1:19001`、PostgreSQL `127.0.0.1:25432`。容器间仍通过 Compose 网络通信。

`seed-demo.ps1` 可重复执行；它只为 `demo1` 创建一份已确认的合成简历和一份含 8 道题的已确认题库。名字与内容明确标注为演示数据，不代表真实候选人经历。种子资料不走 MinerU，因为它们用于不依赖解析服务的面试演示。

演示账号沿用根目录 `.env` 的 `DEMO1_PASSWORD` / `DEMO2_PASSWORD`，未设置时才使用 `.env.example` 的本地默认值。不要将演示账号暴露到公网或用于真实个人资料。

## 清理

只清理独立 Phase 5 项目时运行：

```powershell
pwsh -File scripts/phase5/reset-demo.ps1 -ProjectName interviewmirror-phase5 -Confirmation DELETE_INTERVIEWMIRROR_PHASE5_LOCAL_DATA
```

此命令的实现将项目名固定为 `interviewmirror-phase5`，执行 `down --volumes --remove-orphans` 仅作用于带该项目标签的容器和卷；不会执行 `docker system prune`，也不会碰 `interviewmirror-local` 或其他项目。需要保留演示数据时不要运行。

## 检查与证据

- `start-demo.ps1` 等待 PostgreSQL、MinIO、Backend、Frontend 全部 healthy，并将服务启动耗时写入 `data/phase5/results/startup.json`。该结果会标注是否为新卷、是否复用本机 Docker 构建缓存。
- `check-demo.ps1` 做真实 HTTP 健康、前端可访问、CSRF 登录、`/me`、已确认简历及题库检查。
- 完成三个浏览器演示面试后，`record-demo-runs.ps1` 从数据库读取创建和完成时间，并验证各自是否在 10 分钟内。
- 报告 worker 完成至少三个合成面试后，`record-report-performance.ps1` 从生产 `report_tasks.duration_ms` 读取真实模型调用加数据库持久化耗时；失败尝试计入失败数，不会静默剔除。
- API 读接口基准：`pwsh -File scripts/phase5/run-api-performance.ps1`。
- 真模型 SSE 首 token：`pwsh -File scripts/phase5/run-model-performance.ps1 -RunModelCalls -Samples 20`。此项会对模型供应商产生少量可计费请求，提示词仅使用合成文本。
- 合成数据/本机授权资料之外的真实候选人数据不纳入 Phase 5 测试。

## 当前适用边界

本阶段不把热缓存启动等同冷环境安装，不把直接供应商 SSE TTFT 等同当前 Vue 面试 API 首 token（当前接口返回完整结构化结果），也不把阶段 0/2 的合成评测说成真实简历泛化证明。最终阶段报告必须根据实际生成的结果文件填写；没有重测的项目标记未验证。
