# 阶段 4 报告评测

## 固定输入

> 历史评测说明：此评测记录阶段 4 曾实现的岗位差异分析。该功能已从当前产品停用；不要为日常验收运行差异分析模型调用。以下差异分析指标只作历史结果解释。

评测集：[`data/phase4/report-eval.v1.json`](../../data/phase4/report-eval.v1.json)，版本为 `interviewmirror.phase4-report-evaluation.v1`，只使用合成简历、JD 和回答，不包含真实个人资料。10 个样本覆盖：有 JD 的综合面试、无 JD 的综合面试、题库专项、提前结束和证据稀疏场景；其中 8 个符合旧版差异分析条件。文件中的 `expected.gapApplicable` 由固定模式/JD 规则校验，不依据模型输出。

## 指标定义与门槛

- **报告完整度**：总体评价、总分状态、六维状态、每个已回答 turn 的反馈、优势、风险、建议、学习路径和下一步操作逐项检查比例。结构缺项会使测试失败，门槛 `>=95%`。
- **证据覆盖**：总体评价作为一条声明，经单独模型调用核验并使用该次选择的回答证据 ID；核验上下文包括完整回答及可引用分段。再加上所有已评分维度、逐题反馈、优势、风险、建议和学习路径，计算至少有一条合法引用的声明比例。Verifier 输出必须通过服务端 ID 存在性和 `TURN` 来源校验；不支持、调用失败或引用无效时，总体评价显示中性未核验提示，不展示原始回答摘录。评测结果记录 `MODEL_SUPPORTED` 与 `UNVERIFIED` 数量；证据覆盖率本身不等于语义正确率。Verifier 与报告生成使用同一模型，不能替代人工盲评。
- **差异分析安全性**：只对综合+JD 样本调用模型；其他样本状态必须为 `NOT_APPLICABLE`。差距项必须有可追溯证据；若无回答证据，必须是 `NOT_EVALUATED` 且差距证据和 attribution 均为空。门槛：不适用生成数 `0`，每个差距项必须“有证据或明确未评估”。

## 执行

```powershell
python scripts/phase4/verify_report_eval_dataset.py
python -m unittest discover -s scripts/phase4 -p 'test_*.py' -v
pwsh -File scripts/phase4/run-report-evaluation.ps1
```

最后一条命令读取本地 `.env` 的模型供应商配置但不打印密钥；预计最多调用 10 次报告生成、10 次总结证据核验和 8 次差异分析。需配置 `INTERVIEW_MODEL_API_KEY`、base URL 和模型 ID，并接受模型服务计费。结果写入 `backend/target/phase4-report-evaluation.json`，不应提交评测生成物或任何密钥。

## 本次状态

自动化结构/证据/状态规则由后端单元和集成测试验证。真实模型评测只有在最后一条命令实际完成后才能报告指标；仓库中的合成输入与确定性测试不能冒充真实模型结果，也不能证明真实简历上的语义准确率。人工审查真实使用场景仍需单独进行。
