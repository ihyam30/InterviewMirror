# 阶段 3 面试与追问评测

## 固定评测集

- 源数据：`data/poc/followup-cases.v1.json`，40 个合成单轮问答场景，覆盖 ASK / MOVE_ON 各 20 条。
- 阶段 3 固定副本：`data/poc/phase3-followup-cases.v1.json`，40 个唯一 `caseId`，沿用原问题、回答、预期动作和人工评分目标，仅将评测版本绑定到 `phase3.followup.v1`。
- 阶段 3 数据集 SHA-256：运行 `Get-FileHash .\data\poc\phase3-followup-cases.v1.json -Algorithm SHA256` 获取；模型 run 也会记录同一 SHA。
- 数据集含合成面试回答，不含真实用户简历或答案。盲评包不包含 `expectedAction`、`probeObjective` 或供应商身份。
- 这 40 例均来自综合面试上下文；专项模式的创建、confirmed QuestionBank 门禁和题目来源由模式/API 集成测试验证，不把专项质量分布伪称为已覆盖。

## Prompt 与结构化评估

运行器针对 `promptVersion=phase3.followup.v1` 调用与 `SpringAiInterviewModel.evaluateAnswer` 相同的 system/user prompt 文本与 `RuntimeFollowupWire(shouldFollowUp, rationale, question)` 输出结构。验证要求 rationale 非空；ASK 必须有不超过 600 字且不重复当前问题的追问；MOVE_ON 不要求追问文本。结果在盲评 JSON 中规范化为 `followUpRequired` / `followUpQuestion`，兼容既有匿名评审工具。

运行器对旧 prompt 的 Phase 0 分数不复用；只有 prompt、模型、数据集 SHA 都与阶段 3 新版本绑定的输出，才能创建本阶段盲评包。

## 复现命令

从仓库根目录，在本地 PowerShell 会话设置密钥与已核实的单价；密钥不得写入命令历史、结果或仓库。随后对同一数据集分别运行两个候选：

```powershell
pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1 `
  -Provider qwen -CasesPath .\data\poc\phase3-followup-cases.v1.json -RunLabel phase3-followup-40

pwsh -File .\scripts\poc\spring-ai\run-model-comparison.ps1 `
  -Provider glm -CasesPath .\data\poc\phase3-followup-cases.v1.json -RunLabel phase3-followup-40 `
  -ReasoningEffort low
```

如阶段 3 同步版本的两份 run 文件均通过结构和语义校验，生成随机匿名盲包：

```powershell
$python = '.\scripts\poc\.mineru\Scripts\python.exe'
& $python .\scripts\poc\spring-ai\prepare_followup_review.py `
  --dataset .\data\poc\phase3-followup-cases.v1.json `
  --qwen .\data\poc\results\models\qwen.phase3-followup-40.json `
  --glm .\data\poc\results\models\glm.phase3-followup-40.reasoning-low.json `
  --out-dir .\data\poc\results\phase3-followup-review-v1
```

省略 `--seed` 时脚本生成私有随机种子，并只写入 `unblinding-key.v1.json`；如需精确复现，才通过私密方式保存并传回同一个 `--seed`。将 `review-template.v1.csv` 分别复制为 `reviewer-1.csv` 与 `reviewer-2.csv`，让评审人分别独立填写 80 行（40 case × A/B），MOVE_ON 的 `questionRelevance` 留空。评审者只收到 `blind-pack.v1.json` 和各自的 CSV 模板，不能收到 `unblinding-key.v1.json`。两位评审独立完成后运行：

```powershell
& $python .\scripts\poc\spring-ai\score_followup_review.py `
  --pack .\data\poc\results\phase3-followup-review-v1\blind-pack.v1.json `
  --reviewer-1 .\data\poc\results\phase3-followup-review-v1\reviewer-1.csv `
  --reviewer-2 .\data\poc\results\phase3-followup-review-v1\reviewer-2.csv `
  --out .\data\poc\results\phase3-followup-review-v1\scores-blind.v1.json
```

每个候选的 `followUpQualityMean` 必须 ≥4.0/5；达到门槛前不得解盲或进入下一阶段。原 Phase 0 的 A/B 质量分仅是基线，不是新 prompt 的通过证据。

## 当前实测状态（2026-10-04）

本地自动校验已在 2026-10-04 复跑：40/40 案例有效（ASK 20、MOVE_ON 20），数据集 SHA 为 `d1dea5c5c6de06536d35b20d8229fdb82cafdd165f951b7015a00487d5bf6cd1`；新增模式来源组合矩阵 11/11 通过，API 额外字段和 Schema 不允许的 `jdText: null` 均被拒绝。上述是规则/数据集检查，不是 40 例真实模型质量评测。

真实 LangGraph4j 图的 checkpoint 另在隔离临时 PostgreSQL 16 环境完成跨多个独立 JVM 的写入、回答推进和恢复测试：恢复后仍是“主问题 2”，`mainQuestionIndex=1` 且已回答 turn ID 一致；同一 smoke 首先将 Flyway V1–V4 全部应用到空 PostgreSQL，并验证图接受两次追问、第三次回答后强制进入下一主问题。可复现命令为 `pwsh -File .\scripts\phase3\run-postgres-checkpoint-smoke.ps1`；该脚本使用无宿主端口、无持久卷的独立 Docker 网络，结束后清理临时容器/网络，不连接项目现有数据库。该项只证明真实 Stage 3 图的 PostgreSQL checkpoint 跨 JVM 恢复；完整 Spring Boot API 与 interview 业务表的进程重启恢复另见下节。

### 完整后端进程重启恢复 smoke（2026-10-04）

已新增 `pwsh -File .\scripts\phase3\run-backend-restart-smoke.ps1`，在随机命名的临时 PostgreSQL 17.6、MinIO 容器及独立网络中，连续启动两个独立 Spring Boot JVM。数据库和对象存储容器无持久卷，不连接现有 Compose 项目；脚本结束会删除临时容器和网络，Maven 依赖使用既有 `interviewmirror-m2` 缓存卷。

测试使用真实登录/CSRF、面试创建、开始、回答及查询 API；测试专用确定性模型不调用外部模型。第一个 JVM 将回答事务提交到 PostgreSQL 后立即硬退出，确认 `transition_state=PENDING`；第二个 JVM 启动后由 recovery worker 消费待处理转移并恢复 PostgreSQL graph checkpoint。通过条件为 API 返回 `RUNNING`、当前题为“重启恢复后的主问题 2”、待处理标记清除，且原回答及回答 turn ID 在 `/turns` 中仍存在。实际结果：`InterviewBackendRestartRecoveryTest` 1/1 PASS；同脚本还运行 `InterviewPostgresCheckpointRecoveryTest`，1/1 PASS；Maven 总计 2/2，无失败、错误或跳过。这个测试证明的是后端业务状态与图状态在不同 JVM 间恢复，不证明生产模型的生成质量。

### 完整 Compose 启动 smoke（2026-10-04）

可复现命令：

```powershell
pwsh -File .\scripts\phase3\run-compose-smoke.ps1
```

脚本以 `.env.example` 中的本地演示值、关闭模型调用的配置、随机项目名/端口和新建临时卷执行 build → up；验收 PostgreSQL、MinIO、Backend、Frontend 全部 healthy，Flyway V1–V4 已应用，前端 HTTP 200，`demo1` 登录成功，然后清理本次临时容器、网络和数据库/对象存储卷。2026-10-04 实跑结果 `ISOLATED_COMPOSE_PASS`，health `4/4`、migrations `1,2,3,4`、frontend `200`、login `demo1`。这次 build/up 没有调用外部模型 API，也没有停止当前开发 Compose 项目。

| 指标 | 当前结果 | 证据状态 |
|---|---:|---|
| 场景数量 | 40 | 40/40 数据集结构与固定 hash 校验通过；两家模型均完成 40/40 |
| 综合 / 专项场景 | 40 / 0 | 原 40 例均为综合面试 |
| 模式矩阵自动化 | 11/11 组合通过 | 包含必填/互斥来源、JD 缺省/存在/显式 null；未知创建字段 API 拒绝 |
| 主问题数量 5–8 | 服务端计划校验 + 固定 6 | 假模型生命周期集成覆盖；40 场景运行报告未实测 |
| 每主问题追问 ≤2 | 隔离 PostgreSQL 的真实图跨 JVM smoke 验证第三次回答转下一主问题 | 追问次数图规则通过；模型质量仍待真实评测 |
| 真实模型两候选同集 | PASS | Qwen `qwen-plus-2025-12-01` 与 GLM `glm-5.3-flash`，同一数据集 SHA、Prompt `phase3.followup.v1` |
| 结构化 / 语义成功率 | PASS：两家均 40/40（100%） | 真实运行记录 `data/poc/results/models/qwen.phase3-followup-40.json`、`glm.phase3-followup-40.reasoning-low.json`（本地忽略文件） |
| 追问调用耗时 | Qwen p50 2.369s / p95 3.502s / max 24.406s；GLM p50 3.137s / p95 4.236s / max 27.853s | 40 个 `EVALUATION` 调用的 `elapsedMs`，p95 使用 nearest-rank；`reportP95Ms=0` 是未运行报告生成，不能当作追问耗时 |
| Token / 本次费用 | Qwen 14,478 输入 + 4,032 输出，¥0.019646；GLM 14,126 输入 + 4,028 输出，¥0.022579；合计 ¥0.042225 | 按本次记录的单价估算；50 场/月预测应按实际使用策略另行计算 |
| 双人盲评追问质量均分 | PASS：Qwen 4.025/5；GLM 4.7375/5 | A=Qwen、B=GLM；两位评审各覆盖 40 案例。Qwen 单评审均分 3.925/4.125，GLM 为 4.75/4.725；按阶段门槛，每候选两评审合并均分均 ≥4.0 |
| 追问相关性均分 | Qwen 4.475/5（80 个评分）；GLM 4.8553/5（76 个评分） | MOVE_ON 的相关性留空；GLM 有 38 次 ASK，Qwen 有 40 次 ASK |
| 评审一致性 | 96.25% | 80 组 followUpQuality 评分中，两位评审分差 ≤1 的比例 |
| 动作标注诊断 | Qwen action agreement 50%（ASK precision 50%，recall 100%）；GLM 55%（precision 52.63%，recall 100%） | 两家都明显偏向 ASK（Qwen 40/40、GLM 38/40；评测集标注为 ASK 20 / MOVE_ON 20）。这是作者预标注诊断，不替代盲评；提示“该不该追问”的停止决策仍需重点复核，尤其 Qwen 质量均分刚过门槛 |
| PostgreSQL Graph checkpoint 跨 JVM | PASS | 隔离 PostgreSQL 16；新 JVM 恢复阶段 3 图并验证已消费回答 ID |
| PostgreSQL Spring Boot API + 业务状态重启恢复 | PASS（2026-10-04） | `scripts/phase3/run-backend-restart-smoke.ps1`：两个独立 Spring Boot JVM；真实登录/API；已提交回答和 PENDING 状态在 JVM 硬退出后保留，由第二 JVM recovery worker 通过 PostgreSQL 图 checkpoint 推进并由 API 验证 |
| 全量 Docker Compose build/start | PASS（2026-10-04） | `scripts/phase3/run-compose-smoke.ps1`：Backend/Frontend/MinIO 镜像构建，四服务健康，Flyway V1–V4、HTTP 与本地登录验证；临时卷已清理 |

本次两家 run 均记录 Git HEAD `8dd94b1291fea4db97e9edefe908952bb7861c78`、dirty 状态、相同数据集 SHA `d1dea5c5c6de06536d35b20d8229fdb82cafdd165f951b7015a00487d5bf6cd1`、模型 ID、Prompt 版本、单价、逐次延迟和 Token。真实模型 run 和盲评结果默认位于 `.gitignore` 排除的 `data/poc/results/`。匿名盲包生成器现在默认使用私有随机种子；种子与候选映射只保存在 `unblinding-key.v1.json`，评审者不得看到该文件。本轮两位评审的 160 条评分已由 `score_followup_review.py` 校验并汇总，盲评结果与解盲结果分别保存在 `scores-blind.v1.json`、`scores-unblinded.v1.json`。当前评审包路径为 `data/poc/results/phase3-followup-review-v1/`。

## 不能混淆的验收

- 单测 fake graph 只验证业务持久化/API，不证明 PostgreSQL checkpoint 跨 JVM 恢复。
- 旧的阶段 0 盲评只作为 Prompt 对照基线，不能写作 `phase3.followup.v1` 结果。
- 40 份输出即使结构化通过，也必须两位评审按冻结量表盲评；模型自评分不能替代人工评分。
- 本阶段没有报告生成性能目标；追问评测 run 的 `reportP95Ms=0` 是“未调用报告模型”，不是实测 0ms。
