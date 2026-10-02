# 阶段 0 交付与验收记录

**版本：** `interviewmirror.phase0-acceptance.v1.0.0`  
**验收日期：** 2026-10-02 更新（Asia/Shanghai；追问相关性双模型跑测与双人盲评完成）  
**代码基线：** `4d856e17ab03111ea28e9f2d621d37867f88c071`（工作区有未提交内容；本轮不提交）  
**当前判定：** **本次记录的阶段 0 / M0a 量化门槛通过，可进入 M0b/M1。** 新完成的 40 例追问相关性盲评中，匿名候选 A、B 的质量均分分别为 4.0125/5、4.875/5；两名评审共 80 组质量评分中 74 组相差不超过 1 分（92.5%）。根目录计划阶段表定义统一“阶段 0”，没有使用 M0a/M0b 命名；本任务补充关键内容召回 ≥95%、推荐模型质量均分 ≥4/5 等要求。此处只更新验收记录，不启动后续开发。

## 交付物清单

| 交付物 | 路径 | 状态 |
|---|---|---|
| 用户流程 | `docs/phase0/USER-FLOW.md` | 完成 |
| 模式规则 | `docs/phase0/MODE-RULES.md` | 完成 |
| 报告与差异契约 | `docs/phase0/REPORT-AND-GAP-STRUCTURE.md`、`schemas/` | 完成，待后端联调 |
| 七维评分量表 | `docs/phase0/SCORING-RUBRIC.md` | 完成；与计划五维差异已记录 |
| LangGraph4j PoC | `scripts/poc/langgraph4j/`、`LANGGRAPH4J-POC.md` | 本地编译、路由、PostgreSQL checkpoint 保存和新 JVM 恢复均通过 |
| Spring AI 双候选 PoC | `scripts/poc/spring-ai/`、`SPRING-AI-POC.md`、`data/poc/results/models/` | GLM 低思考复测结构化/语义 20/20、报告 p95 11.716 秒；报告双人盲评均分 4.80/5、一致率 96.67% |
| 追问相关性评测 | `FOLLOW-UP-EVALUATION.md`、`data/poc/followup-cases.v1.json`、`scripts/poc/spring-ai/prepare_followup_review.py`、`score_followup_review.py`、`data/poc/results/followup-review-v1/` | Qwen 与 GLM 40 例同集输出完成；两名评审 160 条质量评分完成；盲评汇总保留 A/B 匿名 |
| MinerU PoC | `scripts/poc/`、`MINERU-POC.md` | 24 份文档解析与三项抽取指标已实测 |
| 合规检查表 | `COMPLIANCE-CHECKLIST.md` | 完成；多项实现或服务形态待确认 |
| 24 份合成文档和 manifests | `data/poc/samples/` | 数据集结构完整；哈希核对通过 |
| 模型基准集 | `data/poc/model-cases.v1.json` | 10 个用例，版本化 |
| 一页摘要 | `PHASE0-SUMMARY.md` | 完成 |

## 门槛结果

| 门槛 | 计划/任务阈值 | 观测结果 | 结论 |
|---|---:|---:|---|
| 样例数量/覆盖 | ≥20，任务明确覆盖扫描 PDF | 24 份；简历 10、题库 10、JD 4；扫描 PDF 3；复杂排版 15 | 通过（数据量/覆盖） |
| 简历字段 F1 | ≥90%（计划阶段 0） | 1.000（TP=50、FP=0、FN=0） | 通过 |
| 题库召回率 | ≥90% | 0.900（TP=27/30，精确率 1.000；Q05 漏 3 题） | 通过，压线 |
| 关键内容抽取 | ≥95%（本次任务补充的 M0a gate；计划未单列） | 0.9674（89/92） | 通过 |
| 模型候选同集比较 | ≥2 家 | Qwen `qwen-plus-2025-12-01` 与 GLM `glm-5.3-flash` 完成 10-case 同集比较；数据集 SHA 与 Prompt 版本一致 | 通过（跑测完成） |
| 结构化输出 | 每候选 ≥95% | Qwen 20/20（100%）；GLM 旧正式基线 19/20（95%），90 秒诊断 20/20，当前低思考 60 秒复测 20/20（100%） | 通过 |
| 模型语义校验 | 追问/报告结论可追溯且满足结构契约 | Qwen 20/20；GLM 90 秒诊断 20/20；GLM 低思考 60 秒复测 20/20 | 通过 |
| 模型输出人工盲评质量 | 推荐模型平均 ≥4/5（本次任务补充的 M0a gate） | 新盲评：GLM 4.80/5（证据 4.90、覆盖 4.80、行动 4.70）；Qwen 3.5333/5；58/60 项差异 ≤1 | 推荐 GLM 通过；Qwen 对照未达 4/5 |
| 追问相关性盲评 | ≥4/5，至少两名评审（`DEVELOPMENT_PLAN.md`） | 候选 A：4.0125/5；候选 B：4.875/5；两名评审各评分 40 例；相关性均分 A 4.7308（78 项）、B 4.9808（52 项）；评分相差 ≤1 比例 92.5% | **通过；A/B 均过质量门槛** |
| 报告生成 p95 | ≤60 秒 | Qwen 10,252ms；GLM 旧 60 秒默认结果 60,027ms（缺 1 份）；旧 90 秒诊断 83,583ms；当前 GLM 60 秒低思考复测 11,716ms（10/10） | 通过（当前低思考复测） |
| LangGraph4j checkpoint | 保存且跨进程恢复 | Postgres 16.15 上保存 `lastNode=ask nextNode=evaluate`；单独 Maven/JVM 使用 `GraphInput.resume()` 续跑到 `report`，无节点回放 | 通过 |
| 50 场/月费用 | ≤¥300 | 静态 token/价格情景计算低于上限；本轮 GLM 同集跑测估算 ¥0.0233804，模型价格需核账户 | 预测通过；实价待核 |
| 合规清单 | 覆盖指定事项 | 覆盖并记录状态 | 文档通过；上线义务待确认 |

## 测试命令

```powershell
pwsh -File .\scripts\poc\install_mineru.ps1
& .\scripts\poc\.mineru\Scripts\python.exe .\scripts\poc\generate_samples.py
& .\scripts\poc\.mineru\Scripts\python.exe .\scripts\poc\verify_dataset.py
& .\scripts\poc\.mineru\Scripts\python.exe .\scripts\poc\verify_contracts.py
pwsh -File .\scripts\poc\run_mineru.ps1 -Tier basic -TimeoutSeconds 600
& .\scripts\poc\.mineru\Scripts\python.exe .\scripts\poc\score_parser_outputs.py
& .\scripts\poc\.mineru\Scripts\python.exe -m unittest discover -s .\scripts\poc -p 'test_*.py' -v
pwsh -File .\scripts\poc\langgraph4j\run-poc.ps1
pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1 -CompileOnly
pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1 -TestOnly
pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1
& .\scripts\poc\.mineru\Scripts\python.exe -m unittest discover -s .\scripts\poc\spring-ai -p 'test_*.py' -v
& .\scripts\poc\.mineru\Scripts\python.exe -m unittest discover -s .\scripts\poc\spring-ai -p 'test_followup_review.py' -v
& .\scripts\poc\.mineru\Scripts\python.exe .\scripts\poc\spring-ai\prepare_followup_review.py --seed 381
& .\scripts\poc\.mineru\Scripts\python.exe .\scripts\poc\spring-ai\score_blind_review.py --pack .\data\poc\results\review\blind-pack.v1.json --reviewer-1 .\data\poc\results\review\reviewer-1.csv --reviewer-2 .\data\poc\results\review\reviewer-2.csv --out .\data\poc\results\review\scores.v1.json
& .\scripts\poc\.mineru\Scripts\python.exe .\scripts\poc\spring-ai\score_blind_review.py --pack .\data\poc\results\review\reasoning-low\blind-pack.v1.json --reviewer-1 .\data\poc\results\review\reasoning-low\reviewer-1.csv --reviewer-2 .\data\poc\results\review\reasoning-low\reviewer-2.csv --out .\data\poc\results\review\reasoning-low\scores.v1.json
& .\scripts\poc\.mineru\Scripts\python.exe .\scripts\poc\estimate_model_cost.py --out data/poc/results/cost-estimate.v1.json
```

样例生成、校验、解析和评分必须使用锁定的 `scripts/poc/.mineru/Scripts/python.exe`（Python 3.12.14）；脚本会拒绝系统 Python 或依赖版本不符，以防静默改变样例哈希。MinerU 评分还要求 `run-manifest.json` 与当前输入语料及每个输出文件 SHA 一致。MinerU 脚本需要模型包下载完成，并允许子进程在工作区 `.mineru/tmp` 写临时文件；首次系统临时目录遇到 PermissionError，指定工作区临时目录后在本机授权 PowerShell 中完成全量解析。LangGraph runner 需要本机 Docker Engine 权限；数据库仅绑定 `127.0.0.1`，运行后停止容器。模型跑测先后修复 Docker 参数数组和 Boot 4 Jackson 3 Bean 配置；2026-10-01 Qwen/GLM 均已完成同集调用，记录见模型 PoC 文档。`score_parser_outputs.py` 在 MinerU 输出缺失或 provenance 不一致时故意返回失败，避免旧结果串集或空语料误判通过。

## 环境、数据与可复现性记录

- **基线检查（阶段 0 文件创建前）：**分支 `main`；`git log -1` 为 `4d856e17ab03111ea28e9f2d621d37867f88c071 新增面镜开发流程计划`。初始 `git status --short` 已包含 `M DEVELOPMENT_PLAN.md`，以及此前 demo 的未跟踪 `index.html`、`package.json`、`package-lock.json`、`src/`、`vite.config.js`；这些既有内容保留。
- **最终工作区：**Git HEAD 仍为上述 SHA；阶段 0 新增 `.gitignore`、`data/`、`docs/`、`scripts/`，未提交、未推送；demo 文件未改动。本地运行产生的模型权重、Maven/MinERU 构建缓存和评测 JSON 置于忽略目录。
- LangGraph4j 集成：`scripts/poc/langgraph4j/run-poc.ps1` 最终 exit code 0；JUnit 2/2 通过；三类路由断言通过；故障后快照 `ask → evaluate`；下一独立 JVM 从该点恢复到 `report`，步骤列表仅一次 `prepare`。临时容器 PostgreSQL `16.15`，image id `sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`。
- OS：MinerU Python 环境报告 `Windows-10-10.0.19045`；Java `21.0.12`；Python `3.12.14`；Docker Engine `29.6.1`；可见 GPU 显存约 `3.84 GB`、主机内存约 `31.8 GB`。
- MinerU：版本 `4.0.10`；Python `3.12.14`、psutil `7.2.2`；本地 ONNX 模型包 13 个文件、858,204,914 bytes；24 份均解析成功。此次锁定语料复测 PDF p95 16.34 秒（最大 RSS 862.99MB），DOCX p95 6.46 秒（最大 RSS 132.27MB）；Q05 双栏表格题库漏 3 题。
- 模型同集跑测：v1.0 首轮记录时间 `2026-10-01T10:33:25Z` / `10:49:34Z`；v1.1 Qwen/初次 GLM `createdAt` 为 `2026-10-01T12:10:37Z` / `12:24:46Z`，GLM-only 复测为 `2026-10-02T06:15:22Z`。v1.2 GLM 低思考复测 `createdAt=2026-10-02T09:40:53.737626840Z`。运行使用相同数据集 SHA `bf0c02df82fa6a008330765b7ee3de73bd3265b552d3a9b94330df9cc560d296`、Prompt `interview-evaluation.v1.0.0`、Git HEAD `4d856e17ab03111ea28e9f2d621d37867f88c071`、`gitDirty=true`。当前 GLM 低思考记录估算费用 ¥0.0233804；以上为 token usage 估算，实际账单与服务端重试计费仍需核对。
- 数据集 manifest SHA-256：`478b3e21e49f4ffdff52210572e6c7b77e9df7372eb7f03e859ec1097e69c031`；生成器固定 Python `3.12.14`、`python-toolchain.lock.json` 中依赖及微软雅黑字体 SHA，并重复生成核对；每份样例及解析输出均由 SHA-256 绑定。
- 模型数据：`interviewmirror.model-benchmark.v1.0.0`；既有 run Schema `interviewmirror.spring-ai-run.v1.1.0`，新 runner 使用 v1.2.0（超时、思考力度、provider-native usage）；Prompt `interview-evaluation.v1.0.0`；候选模型配置记录在 `SPRING-AI-POC.md`。
- LangGraph4j 版本：`1.8.27`；Spring Boot `4.1.0`、Spring AI `2.0.1`；MinerU `4.0.10`。

## 结论规则

所有要求的阶段 0 实测门槛须同时通过后，方可申请下一阶段。样例覆盖、MinerU 指标、LangGraph4j checkpoint、双候选同集模型调用、GLM 低思考力度报告性能和报告盲评，以及 40 例追问相关性双人盲评均已通过记录的门槛。追问候选 A/B 均分分别为 4.0125/5、4.875/5，一致性目标也通过（92.5%）。因此 **阶段 0 / M0a 量化验收通过，可进入 M0b/M1**；本次仅更新文档和评测结果，不启动下一阶段开发。合规清单中的公开服务要求仍为上线前待确认事项，本项目当前范围为本地演示。

## 自我审查记录

1. **样例可复现性：**早期发现默认 Python `3.14.5` 与 MinerU 环境 Python `3.12.14` 输出哈希不同；已锁定 Python `3.12.14`、6 个生成依赖和微软雅黑字体文件 SHA，固定 PDF 元数据及 DOCX ZIP 时间戳，并连续生成核对。默认 Python 现会明确拒绝运行；非锁定字体也会被拒绝。
2. **MinerU 调用路径：**验证后改为 `mineru-kit parse` 单文件调用；分开设置 PDF/image basic 与 DOCX flash 参数；指定可写临时目录；结果覆盖 24/24。评分器改为最大基数一对一题目匹配；一条合并预测不能命中多题。发现 Q05 解析为 0/3 题，保留为明确风险，没有修改人工标注来掩盖漏提取。
3. **LangGraph4j API：**编译发现 1.8.27 要求异步节点/边包装；故障注入异常会包装为图执行异常；空 `Map` 会新开图而非续跑。分别修复节点/边签名、cause 检查和 `GraphInput.resume()`，并加上 next-node 与无重放断言。最终整链 exit code 0。
4. **Spring AI 构建与在线跑测：**修正 `ResponseEntity` 包路径、Git HEAD/dirty metadata、PowerShell Docker 参数数组和 Boot 4 Jackson 3 `JsonMapper` 注入；compile-only 与禁用 Runner 的离线启动 smoke 均通过。随后两家候选的同集 API 调用完成并写出结果 JSON；运行单价已记录但 GLM 账号价仍需核实。
5. **合同与脚本：**数据集覆盖校验、5 个版本化 schema JSON 解析及模式不变量检查、解析指标复算、成本估算、PoC Python 文件语法和 `git diff --check` 均通过。新增 10 个回归用例覆盖合并题目、最大匹配、重复预测、数据集 manifest、输入/每样例/输出哈希、旧 run manifest 拒绝；均通过。`git diff --check` 仅报告既有 `DEVELOPMENT_PLAN.md` LF/CRLF 工作区提示。环境无通用 JSON Schema Draft 2020-12 validator，合同脚本做 JSON 语法及关键模式约束检查，不声称执行了完整 schema 引擎验证。
6. **仓库状态：**Git HEAD 为 `4d856e17ab03111ea28e9f2d621d37867f88c071`，dirty；原有 `DEVELOPMENT_PLAN.md` 修改和 demo 文件保持未提交。阶段 0 文件均未 commit/push；生成的 `data/poc/results/`、MinerU 模型与构建缓存按 `.gitignore` 排除。
7. **模型结果与后续限制（低思考实测前的记录）：**v1.1 中 Qwen 结构化/语义 20/20、报告 p95 10,252ms；GLM 60 秒正式基线为 19/20、报告 9/10、p95 60,027ms。修正 `ChatClient.defaultOptions` 请求级 timeout 后，独立 90 秒诊断日志显示 `effectiveChatClientRequestTimeout=PT1M30S`，GLM 结构化/语义 20/20、报告 10/10、p95 83,583ms、估算成本 ¥0.1120844；最慢 M06 用时 83,583ms。M06 报告输入 562 token、completion usage 4,570 token、结构化结果约 670 字符；10 份报告延迟与输出 token 数相关系数 0.982，提示瓶颈在模型生成 token 数。GLM-5.3-Flash 配置未指定思考力度，官方说明默认 `max`；旧结果未保存原生 usage 细项，无法量出旧轮内部思考 token 占比。旧轮双人盲评：候选 A/Qwen 3.50/5，候选 B/GLM 5.00/5，一致率 91.67%（55/60 项差异不超过 1 分）。当时据此提出低思考力度实验；该实验与新一轮质量盲评均已完成，结果见第 9 项。
8. **GLM 低思考力度跑测准备（当时状态，后续已完成）：**Runner 新增 `-ReasoningEffort default|low|high|max`、60 秒超时和 provider-native usage 保存；结果契约升至 v1.2.0，盲评脚本兼容 v1.1/v1.2。JDK 21 本地编译通过、12/12 Java 单测通过；Python 盲评工具 5/5 测试通过；5 个 schema 关键约束校验通过；PowerShell 语法校验通过。标准 `-TestOnly` 因当前进程无 Docker Engine npipe 权限未能执行，改用已缓存依赖手动 javac/JUnit launcher 完成编译和单测。当时尚未调用模型 API；后续模型实测记于第 9 项。
9. **GLM 低思考力度实测与双人盲评：**用户在本机执行 `pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1 -Provider glm -TimeoutSeconds 60 -ReasoningEffort low`。v1.2.0 记录显示结构化/语义 20/20、报告 p95 11,716ms、总输入/输出 10,364/5,389 token、估算费用 ¥0.0233804。M06 评估/报告耗时 3,254/7,896ms，报告输入 562、completion 326 token，其中 provider-native reasoning token 为 25。相较旧 90 秒默认思考结果的 83,583ms 报告 p95 和 M06 4,570 completion token，性能与 token 显著下降，当前跑测的 ≤60 秒门槛通过。基于 Qwen 正式结果与新 GLM 结果生成独立匿名盲评包 `data/poc/results/review/reasoning-low/`，两位评审的 40 条评分已写入 CSV 并通过汇总脚本校验。解盲后 GLM 4.80/5（证据 4.90、覆盖 4.80、行动性 4.70），Qwen 3.5333/5（4.65、3.45、2.50）；60 项评分比较中 58 项相差不超过 1 分，一致率 96.67%。推荐 GLM 的模型质量门槛通过。该次记录当时追问指标尚未评测；之后的追问跑测及盲评结果记于第 10 项。
10. **追问相关性评测准备与执行：**依据 `DEVELOPMENT_PLAN.md` 中“AI 追问相关性盲评均分 ≥4/5、至少两名评审”的要求，建立 40 个合成场景（ASK/MOVE_ON 各 20）、follow-up-only runner、匿名 A/B 评审包和评分量表。用户本机使用同一数据集 SHA `93288b4ff8f61dd9a5466a6d867a0465db01ff9a4e72217c2ca5a0aba9cfb366`、Prompt `interview-followup.v1.0.0` 完成 Qwen 与 GLM 两家候选跑测；随后两名评审各自评分 40 例 A/B，共 160 条质量评分。匿名结果：A=4.0125/5（评审均分 3.825、4.200），B=4.875/5（4.825、4.925）；追问相关性分别为 4.7308/5（78 项）与 4.9808/5（52 项）；质量评分差异≤1 为 74/80=92.5%。两候选均超过 ≥4/5 门槛，盲评仍未解盲。原空模板因文件占用而保留，最终评分使用 `reviewer-1.completed.csv` 和 `reviewer-2.completed.csv`；汇总为 `scores-blind.v1.json`，均处于本地忽略目录。详情见 `FOLLOW-UP-EVALUATION.md`。
11. **本轮自检：**follow-up 工具 10/10 Python 单测、两份评分表完整性与规则校验、A/B 匿名汇总脚本、40 例唯一 ID 与 ASK/MOVE_ON 各 20 的数据校验、PowerShell Parser 语法分析和 Python `py_compile` 通过；`git diff --check` 通过（仅有既存 `DEVELOPMENT_PLAN.md` LF/CRLF 提示）。模型 API 跑测由用户在本机执行；当前 Codex 进程不读取或复用聊天中展示过的密钥。评分结果保持盲态，不披露模型映射。

