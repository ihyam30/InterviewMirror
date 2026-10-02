# Spring AI 模型比较 PoC

**契约版本：** `interviewmirror.model-benchmark.v1.0.0`  
**数据集：** `data/poc/model-cases.v1.json`，10 个合成问答用例，包含证据引用、未评估、文化边界和岗位匹配；文件 SHA-256 `bf0c02df82fa6a008330765b7ee3de73bd3265b552d3a9b94330df9cc560d296`；Prompt `interview-evaluation.v1.0.0`。  
**代码：** `scripts/poc/spring-ai/`。既有跑测记录为 `interviewmirror.spring-ai-run.v1.1.0`；当前 runner 新结果使用 `interviewmirror.spring-ai-run.v1.2.0`，额外记录请求超时、思考力度和每次响应的 provider-native usage。新 Schema 见 `docs/phase0/schemas/model-run.v1.2.schema.json`，盲评生成脚本同时兼容 v1.1/v1.2。

编译基线：Git HEAD `4d856e17ab03111ea28e9f2d621d37867f88c071`、`dirty=true`；Java `21.0.12`；Docker Maven image `maven:3.9.9-eclipse-temurin-21`。最终 `-CompileOnly` 构建通过，不访问任一模型 API。

## 实现与比较设计

Spring Boot `4.1.0`、Spring AI `2.0.1`、Java 21；OpenAI-compatible provider 接口，覆盖 Qwen 和 GLM 两个不同供应商。对每个案例各请求一次结构化回答评价和一次结构化报告草稿，共 20 个请求/模型。通过类型映射、Schema validation 和服务端语义检查（分值状态一致、证据必须是原回答精确子串、必填报告字段非空）统计成功率；记录模型 ID、数据集 SHA-256、Prompt 版本、响应延迟、可用 token usage 和按实际 token/单价计算费用。报告 p95 使用最近秩法，10 次报告调用的第 10 个排序样本。

## 候选模型

| 候选 | API 配置 | 参考输入/输出价（CNY/百万 token） | 当前状态 |
|---|---|---:|---|
| Qwen `qwen-plus-2025-12-01` | DashScope OpenAI-compatible（北京）；`DASHSCOPE_API_KEY` | ≤128K 非思考：0.8 / 2.0；更长上下文分档，思考模式输出价格不同 | 已完成同集实测；该单价按运行参数记录 |
| GLM `glm-5.3-flash` | BigModel OpenAI-compatible；`ZHIPUAI_API_KEY` | 本次运行参数：0.8 / 2.8；仍需以智谱账户控制台确认 | 已完成同集实测；价格来源待核实 |

模型 ID、可用地域、价格和配额随供应商变化；执行前在各自控制台确认 endpoint/model ID/报价。Spring AI 的 JSON 转换为 best-effort，需保留应用层校验；如供应商支持 strict schema，可同时开启。参考 [Spring AI structured output](https://docs.spring.io/spring-ai/reference/api/structured-output.html)、[Qwen JSON/schema 输出](https://help.aliyun.com/zh/model-studio/qwen-structured-output)、[Qwen 定价](https://help.aliyun.com/zh/model-studio/model-pricing)。

## 运行

本机 Docker/Maven 命令不打印密钥：

```powershell
$secret = Read-Host 'DashScope API key' -AsSecureString
$env:DASHSCOPE_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
$secret = Read-Host 'BigModel API key' -AsSecureString
$env:ZHIPUAI_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
$secret = $null
$env:QWEN_INPUT_PRICE = '0.8'
$env:QWEN_OUTPUT_PRICE = '2'
# GLM 单价应先在智谱当前账号控制台核实
$env:GLM_INPUT_PRICE = '核实后的输入单价'
$env:GLM_OUTPUT_PRICE = '核实后的输出单价'
pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1
```

脚本分别运行两个配置，将结构化 JSON 写入 `data/poc/results/models/qwen.json`、`glm.json`，失败保留每条错误但不写 API key。价格通过 `QWEN_INPUT_PRICE`、`QWEN_OUTPUT_PRICE`、`GLM_INPUT_PRICE`、`GLM_OUTPUT_PRICE` 环境变量传入（单位 CNY/百万 token）。

编译检查（不调用模型）：

```powershell
pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1 -CompileOnly
```

Spring Boot 4 默认 JSON Bean 使用 Jackson 3 `JsonMapper`。本 PoC 已加入 `spring-boot-starter-json` 并注入 `JsonMapper`；评测 Runner 默认启用，也可用 `--interviewmirror.model.run-enabled=false` 禁用，以便仅检查启动、不发出模型请求。离线启动检查命令：

```powershell
$repo = (Resolve-Path .).Path
docker run --rm -v "$repo`:/workspace" -w /workspace/scripts/poc/spring-ai `
  -e MODEL_BASE_URL=http://127.0.0.1:9 -e MODEL_API_KEY=offline-smoke -e MODEL_ID=offline-smoke `
  maven:3.9.9-eclipse-temurin-21 java -jar target/spring-ai-phase0-poc-1.0.0.jar `
  --interviewmirror.model.run-enabled=false
```

复测结果：`-CompileOnly` 编译通过；`-TestOnly` 的 Java validator 单测通过；使用上述离线启动命令后 Spring Boot 4.1.0 显示 `Started ModelBenchmarkApplication`，退出码 0。禁用了评测 Runner，使用回环地址和占位 Key，未发出模型 API 请求。

## 实测结果

下表是 2026-10-01 首轮在线跑测（输出契约 v1.0.0）的历史观测值。该版没有把结构化 DTO 和语义失败原因写入 JSON；当前代码已升级为 v1.1.0，会保存合成样例输出和稳定错误码。旧结果已由 runner 自动归档为带时间戳的 `.previous.json` 文件。

| 模型 | 人工质量均分 / 5 | 结构化 schema 成功 | 语义校验通过 | 报告 p95 | 输入/输出 token | Runner 统计费用 |
|---|---:|---:|---:|---:|---:|---:|
| Qwen `qwen-plus-2025-12-01` | 待双人盲评 | 20/20（100%） | 13/20（65%） | 10,857 ms | 9,849 / 4,881 | ¥0.0176412 |
| GLM `glm-5.3-flash` | 待双人盲评 | 18/20（90%） | 11/20（55%） | 60,037 ms | 8,594 / 30,687 | ¥0.0927988 |

2026-10-01 首轮逐调用结果已归档为 `data/poc/results/models/*.previous.json`。首轮 Qwen 20/20 结构化通过，但 13/20 语义校验通过；GLM 18/20 结构化、11/20 语义通过，M06 与 M10 报告请求失败且 p95 超过 60 秒 37ms。首轮 Runner 统计费用为 ¥0.0176412 和 ¥0.0927988，基于响应 token usage，不替代供应商账单核验。

日志中的 `null found, integer expected` 暴露出结构化约束冲突：首轮输出规则允许未评估时 `score=null`，而生成 Schema 将 `score` 限定为整数。当前改为 wire DTO 字符串枚举：`1`–`5`、`UNASSESSED`、`NOT_APPLICABLE`；服务端再归一化为整数或 null，并记录状态/分值不匹配、证据缺失/错引和追问缺失的稳定错误码。结果契约 v1.1.0 保存结构化输出。在后续低思考力度复测前，60 秒 GLM 基线只有 9 份有效报告；修正请求级 timeout 后的 90 秒诊断结果有 10 份完整报告，可用于该旧配置的质量盲评。已从旧诊断结果生成 10 场/20 行评审包；首轮 v1.0 结果仍无法盲评。

### v1.1.0 同集在线跑测与 GLM-only 复测

Qwen v1.1 结果创建于 `2026-10-01T12:10:37Z`；GLM 初次 v1.1 结果创建于 `2026-10-01T12:24:46Z`。2026-10-02 对 GLM 单独复测，结果创建于 `2026-10-02T06:15:22Z`。结果使用相同数据集 SHA-256 `bf0c02df82fa6a008330765b7ee3de73bd3265b552d3a9b94330df9cc560d296`、Prompt `interview-evaluation.v1.0.0`、Git HEAD `4d856e17ab03111ea28e9f2d621d37867f88c071`、`gitDirty=true`；JSON `schemaVersion` 为 `interviewmirror.spring-ai-run.v1.1.0`。

| 模型 | 人工质量均分 / 5 | 结构化输出 | 语义校验 | 成功报告数 | 报告 p95 | 输入/输出 token | Runner 统计费用 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Qwen `qwen-plus-2025-12-01` | 3.50/5（候选 A） | 20/20（100%） | 20/20（100%） | 10/10 | 10,252 ms | 10,570 / 4,219 | ¥0.016894 |
| GLM `glm-5.3-flash`（10/02 GLM-only） | 5.00/5（候选 B；质量评分取 90 秒诊断轮次） | 19/20（95%） | 19/20（95%） | 9/10 | 60,027 ms | 9,790 / 26,425 | ¥0.081822 |

**历史 60 秒基线：**GLM 初次 v1.1 运行中 M01、M06、M09、M10 报告请求失败；10/02 复测时 M06、M09、M10 成功，M01 在约 60 秒后失败，正式结果为结构化/语义 19/20、报告 9/10、p95 60,027ms。之后检查 Spring AI `2.0.1` 请求构造与配置发现，`spring.ai.openai.timeout` 只设置公共 HTTP 客户端超时，而 `OpenAiChatOptions` 中 60 秒的请求级默认值会覆盖它。Runner 已改为显式设置 `ChatClient.defaultOptions(...)` 请求级 timeout。**根因已由 90 秒诊断复测确认：**日志打印 `effectiveChatClientRequestTimeout=PT1M30S`；M06 在 83,583ms 成功返回，其余报告也在 60 秒内完成，没有证据表明供应商存在硬性 60 秒上限。历史正式文件 `glm.json` 仍保留修复前的 60 秒基线；修复后的诊断结果单独保存在 `glm.timeout-90s.json`，没有覆盖正式文件。

延长超时只用于诊断，**不改变 M0a 的报告 p95 ≤60 秒验收门槛**。配置 GLM 的本机 API key 和价格后，可执行以下命令；脚本会只跑 GLM，并把结果写入独立文件 `data/poc/results/models/glm.timeout-90s.json`，不覆盖 60 秒验收结果。Runner 会在 `ChatClient` 默认 `OpenAiChatOptions` 中显式设置请求级超时，并打印 `effectiveChatClientRequestTimeout`：

```powershell
pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1 -Provider glm -TimeoutSeconds 90
```

90 秒诊断复跑打印 `effectiveChatClientRequestTimeout=PT1M30S`，结构化与语义校验均 20/20、报告 10/10 成功、报告 p95 为 83,583ms，Runner 估算费用 ¥0.1120844。M06 最慢，为 83,583ms；其余九份报告在 26,692–57,965ms 完成。与前次仍在 60 秒失败的跑测对照，证明前次实际生效的是 Spring AI `OpenAiChatOptions` 的 60 秒请求级默认值；当前 `ChatClient.defaultOptions(...)` 显式覆盖后，超过 60 秒的 GLM 请求能成功返回。该证据未观察到供应商硬性 60 秒限制。**在随后低思考力度复测之前，该 90 秒结果未通过 p95 ≤60 秒门槛，不能作为验收结果。**脚本不会输出 API key；本次每场调用评估和报告各一次，共 20 次模型调用。当前验收数据以本节后的“GLM 低思考力度同集复测”为准。

### M06 延迟归因与方案判断

90 秒结果中 M06 的两次顺序调用数据如下：

| 调用 | 延迟 | 输入 token | 输出 token | 结构化结果可见字符数 |
|---|---:|---:|---:|---:|
| 回答评估 | 36,333 ms | 471 | 1,922 | — |
| 报告生成 | 83,583 ms | 562 | 4,570 | 约 670 |

报告只有 562 个输入 token，处于其余 9 份报告的 530–580 区间；结构化结果可见内容约 670 字符，字段数量也没有异常膨胀。相反，M06 报告是本轮最大输出 token 数；10 份报告的延迟与输出 token 数相关系数约 0.982，观测生成速度约 49–63 token/s。该样本支持“生成 token 数导致耗时增加”，不支持先把用户可见报告大幅删短。相关性仅基于 10 个样本，不能单独证明因果。

在 GLM 90 秒诊断结果中，M06 报告生成耗时 83,583ms，输入 562 token、completion usage 4,570 token，可见报告约 670 字符。之后保留模型、Prompt、报告字段与 60 秒请求级超时，仅将 `reasoning_effort` 设为 `low` 进行同集复测。结果 JSON v1.2.0 同时保存每次调用的 `providerNativeUsageType` 和 `providerNativeUsage`，因此可检查服务端返回的 reasoning token 明细。

### GLM 低思考力度同集复测（2026-10-02）

运行命令：

```powershell
pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1 -Provider glm -TimeoutSeconds 60 -ReasoningEffort low
```

同集比较使用模型 `glm-5.3-flash`、10 个用例、相同数据集 SHA-256 `bf0c02df82fa6a008330765b7ee3de73bd3265b552d3a9b94330df9cc560d296`、Prompt `interview-evaluation.v1.0.0`；配置请求超时 60 秒、`reasoningEffort=low`。基线 Git HEAD 为 `4d856e17ab03111ea28e9f2d621d37867f88c071`，`gitDirty=true`。

| 指标 | GLM 90 秒历史诊断（默认思考力度） | GLM 60 秒低思考复测 | 变化 |
|---|---:|---:|---:|
| 结构化 / 语义通过 | 20/20、20/20 | 20/20、20/20 | 保持 |
| 报告成功 / p95 | 10/10、83,583ms | 10/10、11,716ms | p95 降低约 86.0% |
| M06 评估 / 报告耗时 | 36,333ms / 83,583ms | 3,254ms / 7,896ms | 报告耗时降低约 90.6% |
| M06 报告 token | 输入 562、completion 4,570 | 输入 562、completion 326（其中 reasoning 25） | 输出减少约 92.9% |
| 全部调用输入 / 输出 token | 10,364 / 37,069 | 10,364 / 5,389 | 输入相同、输出减少约 85.5% |
| 本轮估算费用 | ¥0.1120844 | ¥0.0233804 | 降低约 79.1% |

低思考力度将报告生成 p95 拉回 60 秒门槛以内，且结构化/语义通过保持 20/20；现有数据不支持为了提速而先大幅删减报告内容。新一轮双人盲评的 GLM 均分为 4.80/5，达到 ≥4/5 目标。`data/poc/results/models/glm.reasoning-low.json` 是此次 60 秒低思考结果；旧 90 秒结果保留为历史诊断，不覆盖。

为避免把旧 GLM 输出评分沿用到新输出，已基于 Qwen 正式基线与此 GLM 低思考结果生成独立盲评包，位置为 `data/poc/results/review/reasoning-low/`。两位评审已为 10 个案例的 20 份报告分别评分。解盲结果为 GLM 4.80/5（证据准确性 4.90、覆盖度 4.80、行动性 4.70），Qwen 3.5333/5（4.65、3.45、2.50）；两位评审在 60 项维度评分中的 58 项相差不超过 1 分，一致率 96.67%。评审认为 GLM 的证据、风险和行动建议整体较完整，仍需关注少量过度外推；Qwen 主要不足是风险、建议和学习路径较少。模型质量门槛由推荐 GLM 低思考配置通过；根计划要求的追问相关性评测仍待完成，所以当前仍不判定进入 M0b/M1。

门槛口径：结构化输出目标 ≥95%、报告生成 p95 ≤60 秒沿用计划；根计划评价追问相关性 ≥4/5，本任务另加推荐模型质量均分 ≥4/5。历史盲评（Qwen 3.50/5、GLM 90 秒默认思考结果 5.00/5，一致率 91.67%）只作历史对照；低思考 GLM 新结果的双人评分为 4.80/5，一致率 96.67%。追问相关性仍待单独补测。Spring AI 结构化输出是 best-effort，并非供应商端严格 schema 保证，应用层仍需校验；单次格式修复最多 1 次。

## 复跑与双人盲评

Qwen 正式结果和 GLM 低思考结果都各有 10 份报告。若修改模型、Prompt 或 Runner，可只重跑 GLM（保留 Qwen 结果，不重复调用 Qwen）：

```powershell
pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1 -Provider glm
```

**历史默认思考力度盲评：**使用 GLM 请求级 90 秒诊断报告，候选 A 对应 Qwen、候选 B 对应 GLM；GLM 得分 5.00/5，但该结果的 p95 为 83.583 秒，不满足性能门槛，也不代表当前低思考输出质量。历史盲评包和汇总保留在 `data/poc/results/review/`。

脚本默认仍运行两家；`-Provider` 可设 `qwen`、`glm` 或 `both`。若要复现历史 90 秒诊断评审包，须显式指定旧诊断文件：

```powershell
& .\scripts\poc\.mineru\Scripts\python.exe .\scripts\poc\spring-ai\prepare_blind_review.py --seed 381 --glm .\data\poc\results\models\glm.timeout-90s.json
```

历史评审包对应的评分命令仍可按旧文件复算。最新的 GLM 低思考力度盲评单独保存在 `data/poc/results/review/reasoning-low/`；评审表已完成，评分汇总为 `scores.v1.json`，解盲结果为 `unblinded-summary.v1.json`。低思考版候选 A=GLM、候选 B=Qwen；均分分别为 4.80/5 与 3.5333/5，一致率 96.67%。低思考版的复算命令：

```powershell
& .\scripts\poc\.mineru\Scripts\python.exe .\scripts\poc\spring-ai\score_blind_review.py `
  --pack .\data\poc\results\review\reasoning-low\blind-pack.v1.json `
  --reviewer-1 .\data\poc\results\review\reasoning-low\reviewer-1.csv `
  --reviewer-2 .\data\poc\results\review\reasoning-low\reviewer-2.csv `
  --out .\data\poc\results\review\reasoning-low\scores.v1.json
```

## 50 场月费用估算

静态预算场景（不是模型观测值）：每场上限估为 400,000 输入 token + 40,000 输出 token，含资料摘要、对话和报告；50 场共 20M 输入 + 2M 输出。公式：`50 × (Tin × Pin + Tout × Pout) / 1,000,000`。Qwen 按北京地域、单次输入不超过 128K、非思考模式价格 0.8/2.0 估算约 ¥20/月；思考模式输出单价不同，超过 128K 输入也按阶梯价格。按本轮 GLM 运行参数 0.8/2.8 情景估算约 ¥21.60/月，但须先向智谱账号核价；沿用保守预设 2.0/8.0 时约 ¥56/月。Qwen 与 GLM 的 2 倍 token 压测分别约 ¥40、¥43.20（运行参数情景）或 ¥112（GLM 保守情景），均低于 ¥300。实测单集 Runner 统计费用分别为 ¥0.0176412、¥0.0927988；需按账户账单确认重试 token 是否全部纳入 usage 统计。Qwen 价格依据：[阿里云百炼模型定价](https://help.aliyun.com/zh/model-studio/model-pricing)。
