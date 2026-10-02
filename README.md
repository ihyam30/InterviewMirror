# 面镜 InterviewMirror

面向 AI 应用 / AI 全栈实习求职者的文字面试练习 Demo。当前阶段是**本地工程基础**：现有 Vue 交互 UI 保留；登录、简历/题库确认记录、用户私有文件已接入本地后端。正式 AI 面试流程、真实解析、动态报告和历史报告持久化仍在后续阶段。

## 本地启动

运行应用只需要 Docker Desktop（含 Docker Compose v2），不需要本机安装 Java、Maven、Node 或数据库。首次构建需要网络拉取容器镜像和 Maven/npm 依赖。

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

可在本地 `.env` 覆盖演示密码及服务端口。`.env` 不纳入 Git。不要将默认示例凭证用于共享或公网环境。所有宿主机端口都绑定到 `127.0.0.1`。

MinIO 管理台凭证为 `.env` 中的 `MINIO_ROOT_USER` 和 `MINIO_ROOT_PASSWORD`。产品文件桶为私有桶；前端不会获得 S3 凭证或永久/预签名对象 URL。Compose 使用官方归档源码固定 tag 构建 MinIO，不依赖已下架的 Community 镜像仓库或发布二进制。官方 MinIO Community 仓库已归档，源码构建产物不受上游支持，因此此配置仅适用于绑定到 loopback 的个人本地演示；不得复用于共享或公网服务。未来扩展使用前应切换到有持续维护的发行版或兼容对象存储，并重新执行对象访问权限与兼容性验收。

## 当前能力和边界

- 用户名或邮箱登录、当前用户、退出登录；服务端 Session Cookie（HttpOnly、SameSite=Strict）与 CSRF 校验。
- `/api/v1/resources` 提供用户私有资源 CRUD；数据库查询、更新和删除均把认证用户 ID 放进 SQL 条件，跨账号不存在性统一返回 404。
- `/api/v1/files` 提供用户私有文件上传、metadata、下载和删除；服务端仅接受 PDF、DOCX、TXT 和 Markdown，并校验扩展名、MIME 类型及 PDF/DOCX 文件签名；文件大小上限 20 MiB；API 不返回对象 key，私有桶不能匿名直连。
- 简历和题库确认信息保存到 PostgreSQL，原始文件保存到 MinIO；本阶段不解析 PDF/DOCX/TXT、不调用模型。
- 报告及完整差异分析仍为只读示例数据，不属于账号私有历史记录；页面有提示。
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
```

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
npm ci
npm test
npm run build
mvn -B -f backend/pom.xml test
docker compose config --quiet
```

本地启动整个应用仍使用 `docker compose up --build -d`。后端集成测试使用 H2 和内存对象存储替身验证两用户的资源、文件访问隔离；本机 Compose 验收还应验证真实 PostgreSQL、MinIO 和各 HTTP API。CI 在干净 GitHub Actions runner 上执行前端测试/构建、后端测试和 Compose 配置校验。

Compose 健康后可执行真实服务 smoke test（脚本会创建并在末尾删除一条合成资源和文件）：

```powershell
pwsh -File .\scripts\phase1-smoke.ps1
```

架构和本地操作说明见 [`docs/phase1/LOCAL-DEVELOPMENT.md`](docs/phase1/LOCAL-DEVELOPMENT.md)。
