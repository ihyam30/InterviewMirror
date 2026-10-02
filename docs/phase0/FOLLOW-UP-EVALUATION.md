# 追问相关性评测方案

**契约版本：** `interviewmirror.followup-evaluation.v1.0.0`  
**用例集：** `data/poc/followup-cases.v1.json` (`interviewmirror.followup-benchmark.v1.0.0`)  
**Prompt 版本：** `interview-followup.v1.0.0`  
**数据集 SHA-256：** `93288b4ff8f61dd9a5466a6d867a0465db01ff9a4e72217c2ca5a0aba9cfb366`  
**状态：** 40 例同集模型跑测及两名评审评分已完成；A/B 标签继续保持匿名，两个候选的 followUpQuality 均分均达到 ≥4/5 门槛。

## 测什么

数据集包含 40 个合成对话场景，其中 20 个作者预标注为 `ASK`，20 个为 `MOVE_ON`。场景涉及检索评测、提示注入、重试与幂等、Token 预算、SSE 恢复、结构化输出、RAG 切分、隔离权限、文档解析、项目经历边界和 JD 明示的协作要求。预期动作、追问目标和信号仅供整理者核对，模型 Prompt 与盲评材料均不包含这些标签。

每个模型对每例只执行一次回答评估，追问决策与追问文本来自同一结构化响应。Follow-up 数据集不会额外调用报告生成模型，以避免为本项评测产生无关费用。要求 Qwen 与 GLM 使用同一份数据集 SHA 和同一 Prompt 版本；命令选择预期候选配置 Qwen `qwen-plus-2025-12-01` 默认思考力度、GLM `glm-5.3-flash` `low` 思考力度。模型配置不同属于候选模型配置比较，需在结论里明示。

作者的 `expectedAction` 只用于解盲后的次级诊断，不是人类金标准，也不取代盲评。主要指标是两位评审独立给出的 **followUpQuality** 均分。另记录实际追问时的 `questionRelevance` 均分及相对作者标注的 ASK precision/recall 和动作一致率。

## 盲评规则

评审逐例比较匿名 A/B 两个候选。两位评审收到同一个 `blind-pack.v1.json`，分别填写 CSV，不交换意见；打分完成前不查看 `unblinding-key.v1.json`。输出文本里可能有模型风格线索，所以只能称为匿名候选评审，不能保证对熟悉模型输出的评审实现完美盲法。

| 分数 | `followUpQuality`：动作判断与整体质量 | `questionRelevance`：仅实际发起追问时填写 |
|---:|---|---|
| 5 | ASK/MOVE_ON 判断恰当；追问时聚焦、可回答、命中重要缺口且无无依据前提；答案充分时正确停止 | 紧扣本轮答案、岗位上下文和需要澄清的信息 |
| 4 | 判断合理、追问相关，仅有轻微遗漏或稍宽泛 | 基本切题，有小幅偏宽或未问到最佳切口 |
| 3 | 部分有用，但较泛、轻微重复/过早，或漏掉一处重要细节 | 有一定关系，但没有充分利用已给答案 |
| 2 | 动作明显欠妥，或追问大幅重复、带预设，或遗漏明显关键澄清 | 与回答关联弱或建立在未经证实的假设上 |
| 1 | 错误/离题动作，严重越过证据边界，或显著伤害面试体验 | 离题、无法回答或含无依据事实 |

`questionRelevance` 对 `MOVE_ON` 留空，由脚本校验。每名评审提交 80 行（40 题 × A/B）；总计 160 条 `followUpQuality` 评分和最多 160 条问题相关性评分。主门槛按每候选 80 个独立评分（两人 × 40 题）求均值，至少一个被推荐候选需达到 ≥4.0/5；报告还列出两位评审各自均值和逐项相差 ≤1 分比例，后者目标 ≥80%。若两名评审发现量表理解不一致，保留原始分并记录分歧，不事后协商改分。

## 本轮实测结果（保持匿名）

| 指标 | 候选 A | 候选 B |
|---|---:|---:|
| Follow-up quality 均分（80 条） | 4.0125/5 | 4.875/5 |
| 人工1 / 人工2 均分 | 3.825 / 4.200 | 4.825 / 4.925 |
| 实际追问时 question relevance | 4.7308/5（78 条） | 4.9808/5（52 条） |
| ASK 次数（40 例） | 39 | 26 |
| 质量评分差异 ≤1 的比例 | 两名评审共 80 组比较，74/80 = 92.5% | — |
| ≥4/5 门槛 | 通过 | 通过 |

两位评审各评阅 40 个场景的 A/B 输出，共 160 条质量评分。评分器确认 reviewer ID、完整案例集合、分值范围、`MOVE_ON` 的相关性留空规则及盲包 SHA。汇总见 `data/poc/results/followup-review-v1/scores-blind.v1.json`；原始输入为 `reviewer-1.completed.csv` 与 `reviewer-2.completed.csv`。候选映射尚未解盲，因此这里不把 A/B 对应到供应商，也不按分数选择模型。两候选都超过主门槛，相关性均分作辅助诊断。模型结果和运行元数据见本地忽略目录 `data/poc/results/models/`；同集运行使用数据集 SHA `93288b4ff8f61dd9a5466a6d867a0465db01ff9a4e72217c2ca5a0aba9cfb366` 与 Prompt `interview-followup.v1.0.0`。本项不生成报告，`reportP95Ms=0` 不代表报告性能结果。

## 运行步骤

先在本机环境配置模型凭据和已核实的输入/输出单价。不要将密钥写入仓库、命令历史、评审材料或结果 JSON。PowerShell 可交互读取密钥：

```powershell
$secret = Read-Host 'DashScope API key' -AsSecureString
$env:DASHSCOPE_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
$secret = Read-Host 'BigModel API key' -AsSecureString
$env:ZHIPUAI_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
$secret = $null
$env:QWEN_INPUT_PRICE = '核实后的输入单价'
$env:QWEN_OUTPUT_PRICE = '核实后的输出单价'
$env:GLM_INPUT_PRICE = '核实后的输入单价'
$env:GLM_OUTPUT_PRICE = '核实后的输出单价'
```

从仓库根目录分别跑同一数据集。结果写入被 `.gitignore` 排除的本地 `data/poc/results/models/`。专用数据集 run 中 `reportP95Ms=0` 表示没有报告调用，并非报告耗时实测：

```powershell
pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1 `
  -Provider qwen -CasesPath .\data\poc\followup-cases.v1.json -RunLabel followup-40
pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1 `
  -Provider glm -CasesPath .\data\poc\followup-cases.v1.json -RunLabel followup-40 `
  -ReasoningEffort low
```

若只复跑其中一方，另一个结果必须属于相同数据集 SHA、数据版本和 Prompt 版本；盲评准备脚本会拒绝不同数据集、重复/缺失案例或同一家供应商的两份结果。脚本固定记录 Git HEAD/工作区状态；模型 ID、请求逐项延迟、Token 用量、实际运行价格、成本和 JSON 输出写在 run 文件。此 40 例只调用评估模型，不调用报告模型；实际总费用按供应商账单复核。

```powershell
$python = '.\scripts\poc\.mineru\Scripts\python.exe'
& $python .\scripts\poc\spring-ai\prepare_followup_review.py --seed 381
```

生成 `data/poc/results/followup-review-v1/blind-pack.v1.json`、`review-template.v1.csv` 和私有解盲键。固定随机种子用于复现匿名映射；评审只拿盲包和各自评分表，不得拿解盲键。复制 CSV 模板为 `reviewer-1.csv`、`reviewer-2.csv`，评审填入不同 reviewerId、评分和必要备注。本次原定文件名被其他进程占用，故原始空模板保持不变；已完成评分以 `reviewer-1.completed.csv`、`reviewer-2.completed.csv` 保存，并在下面的命令中直接引用。

```powershell
& $python .\scripts\poc\spring-ai\score_followup_review.py `
  --pack .\data\poc\results\followup-review-v1\blind-pack.v1.json `
  --reviewer-1 .\data\poc\results\followup-review-v1\reviewer-1.completed.csv `
  --reviewer-2 .\data\poc\results\followup-review-v1\reviewer-2.completed.csv `
  --out .\data\poc\results\followup-review-v1\scores-blind.v1.json
```

先复核 CSV 行数、两人 ID、分数范围、无缺项、问题相关性空值规则和 pack SHA。确认盲评冻结后，才添加私有解盲键与数据集进行解盲及作者标签诊断：

```powershell
& $python .\scripts\poc\spring-ai\score_followup_review.py `
  --pack .\data\poc\results\followup-review-v1\blind-pack.v1.json `
  --reviewer-1 .\data\poc\results\followup-review-v1\reviewer-1.completed.csv `
  --reviewer-2 .\data\poc\results\followup-review-v1\reviewer-2.completed.csv `
  --unblinding-key .\data\poc\results\followup-review-v1\unblinding-key.v1.json `
  --dataset .\data\poc\followup-cases.v1.json `
  --out .\data\poc\results\followup-review-v1\scores-unblinded.v1.json
```

## 可复现与当前限制

```powershell
$python = '.\scripts\poc\.mineru\Scripts\python.exe'
& $python -m unittest discover -s .\scripts\poc\spring-ai -p 'test_followup_review.py' -v
```

数据集本身以 Schema/Prompt 版本和 run 记录中的 SHA-256 绑定。API Key 和价格由执行环境提供，不落入结果。准备评审包脚本存 seed 与 A/B→供应商映射于独立解盲键；盲包不含 `expectedAction`、`probeObjective` 或模型 ID。结果目录是本地忽略文件，不应提交或发送给不需访问合成评测结果的人。

本评测将每个“面试官问题 + 候选回答”视为独立决策点，测单轮 ASK/MOVE_ON 与追问相关性；它不覆盖同一候选人跨多轮面试中的问题去重、主题覆盖或追问深度稳定性，这些应在 M1 集成回归和试用中继续测量。

本轮模型调用和双人评分由用户在本机完成；当前 Codex 进程不复用聊天中展示过的 API 凭据。评审完成结果和盲评汇总位于 `.gitignore` 排除的 `data/poc/results/followup-review-v1/`。A/B 映射仍保存在私有 `unblinding-key.v1.json`，汇总保持 `unblinded=false`。追问相关性质量门槛现已通过；阶段 0 是否进入下一阶段应以 `PHASE0-ACCEPTANCE.md` 汇总的其余 gate 和环境条件为准。
