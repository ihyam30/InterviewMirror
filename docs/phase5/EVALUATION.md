# 阶段 5 实测评估

测量时间：2026-10-06 UTC。工作流仅使用 Phase 5 的合成演示资料和 `demo1`。原始机器可读结果位于 `data/phase5/results/`。

## 环境与限制

- Host：Windows 10 Pro 22H2（10.0.19045），Intel N95，4 logical processors，31.8 GiB RAM。
- Docker Desktop Server：29.6.1，Linux containers；Java 测试使用 Java 21 / Maven 3.9.11 Docker 环境。
- API 基准使用 host k6；报告与面试走本地 Compose Backend/PostgreSQL/MinIO，模型为 `qwen-plus-2025-12-01`。
- Phase 5 Compose 重置后使用新的独立卷，但 Docker/BuildKit 和依赖缓存是热的。最终复测启动至四个服务 healthy 为 57.923 秒；启动至 check-demo 登录完成为 84.939 秒。没有清除共享缓存，因此“首次获取项目、完全冷缓存到登录 <=15 分钟”标为 **NOT VERIFIED**。
- GitHub 托管 Runner 本轮未运行；本机测试不代替托管 CI 结果。

## Demo 和服务

四个 Compose 服务 PostgreSQL、MinIO、Backend、Frontend 均 healthy；首次初始化成功应用 Flyway V1–V6。`check-demo.ps1` 验证了前端、后端健康、demo1 登录、当前用户和一份已确认合成简历/题库。

三场真实浏览器面试：2 场综合、1 场专项；均生成报告。时长分别 2.98、2.60、1.27 分钟，平均 2.28 分钟，最大 2.98 分钟，全部低于 10 分钟。

## 普通认证 API

对简历列表、题库列表、面试列表、报告列表、`/me` 各执行 100 次顺序认证 GET（k6 1 VU，并发 1），另外有 CSRF 与登录建立会话。记录包含 p50/p95/p99/max、总请求与错误率。每组 p95 门槛 `<300 ms`；这不是高并发容量测试。

最终 API 结果共 503 请求、0 错误；各组 p95 为简历 16.83 ms、题库 15.31 ms、面试 17.80 ms、报告 22.79 ms、当前用户 11.20 ms，p99 均低于 49 ms。具体数据见 `data/phase5/results/api-performance.json`。

## 模型延迟和报告耗时

- 真实供应商 SSE 探针：20/20 成功，TTFT p50 340.18 ms、p95 530.95 ms、max 915.51 ms；完整响应 p95 1043.75 ms。该 TTFT 来自直接 SSE 探针，不代表当前 UI 的结构化调用路径。
- 修复后的完整报告任务：3/3 成功、0 失败；p50 29,395 ms、p95 41,650 ms、max 43,012 ms，低于 60 秒。测量只包含修复后 09:32:41 UTC 以后创建的任务。修复前 p95 115,114 ms 的基线被保留但不混入新门槛计算。
- 面试模型调用和报告调用来自真实 Qwen；没有用 mock 替代本次性能结果。少量结构化输出校验重试可能额外增加用量。

## 成本

3 场成功面试及 3 份成功报告共记录 17 次模型 usage 调用、输入 21,971 tokens、输出 7,286 tokens，无缺失 usage、失败调用或报告失败。按公开的中国北京、输入不超过 128K、非思考模式价格假设（输入 ¥0.8、输出 ¥2 / 百万 tokens），实测总成本约 ¥0.032149，平均 ¥0.010716 / 场；按 100 场/月线性估算约 ¥1.07 / 月，低于 ¥300 预算。官方牌价已核对，账户实际区域、优惠与账单未核对；最终实际账单价格为 **NOT VERIFIED**。

## PDF 视觉检查

Poppler 将 3 份合成报告的所有页面渲染成 PNG 并逐页目视检查；PyMuPDF 文本提取确认有中文且未包含证据引用、岗位差异分析或“下一步”章节。普通综合报告 2 页、长综合报告 4 页、专项报告 2 页；三份均有 A4 页面、中文文字和可见雷达图，未见方框乱码、重叠、内容裁剪或页面外溢。结果见 `data/phase5/results/pdf-visual-review.json`。

## 自动化回归和未验证项

- Backend Maven：63 项，60 passed、3 skipped、0 failure/error。跳过的是显式启用的模型评测和 PostgreSQL/JVM 恢复类环境测试；`LocalApplicationIntegrationTest` 15/15 通过。
- Frontend：`npm test` 14/14，`npm run build` 通过。
- Python：阶段 3 数据集 40/40；其单测 2/2；阶段 4 报告数据集 10 场景、8 个适用差异分析场景，单测 4/4。
- Compose 配置、Phase 5 seed/check、reset/restart 检查和 `git diff --check` 结果以最终阶段报告及证据 JSON 为准。
- 阶段 2 的 30 份解析评测是合成语料，不说明真实简历泛化；Phase 5 上传解析本轮不以 MinerU 性能替代面试链路评估。
