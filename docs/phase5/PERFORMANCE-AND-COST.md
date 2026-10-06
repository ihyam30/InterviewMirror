# 阶段 5 性能与模型成本验收

所有性能输入均为固定合成文本或本地演示账号数据。门槛统计必须保留样本数、成功/失败数、p50、p95、最大值；失败不能从结果集删除。

## 普通 API

`api-performance.js` 对五类认证 GET 端点各发出 100 次请求：简历列表、题库列表、面试列表、报告列表、当前用户。一个 VU 顺序测量，不代表高并发容量测试。门槛为各组 p95 `<300 ms`、错误数为 0。原始汇总写入 `data/phase5/results/api-performance.json`。

## 模型 TTFT

`model-performance.mjs` 对当前 `.env` 中配置的 provider、model 发起 SSE Chat Completions 请求，使用合成提示词，收集第一次非空 assistant `delta.content`、完整响应耗时及服务端 usage。PowerShell 默认要求显式 `-RunModelCalls`，默认 20 次、限制 10–30 次；失败率必须为 0、usage 必须由服务端返回、TTFT p95 必须不超过 5 秒。

这是**供应商 SSE 接口的真实 TTFT**。InterviewMirror 的面试模型 Java 路径通过一次 `ChatClient.prompt().call().responseEntity(...)` 同时取得完整结构化对象与响应 usage；页面不是逐 token 流式展示，所以本测试不能宣称产品 API 已有 TTFT。后端回归测试会验证每个模型适配调用只发起一次结构化请求。

## 报告生成

结束面试后，后台 `ReportWorker` 将从回答快照生成报告、核验总体评价和落库。`record-report-performance.ps1` 直接读取报告任务的 `started_at`、`completed_at`、`duration_ms` 与状态；`-SinceUtc` 可以限定为当前代码版本产生的新任务，避免把修复前的基线混入重新验收。最终至少需要三份真实模型报告样本，错误数为 0，p95 `<=60000 ms`。完整用户面试流程耗时另由 `record-demo-runs.ps1` 验证 `<=10 min`。

## 月成本预算

报告必须用实际模型 usage 计算每个探针或完整面试的 input/output token 和成本。初始配置为 `qwen-plus-2025-12-01`。按官方 Model Studio 当前中国北京、输入不超过 128K 的非思考调用牌价，估算可用输入 ¥0.8 / 百万 token、输出 ¥2 / 百万 token；思考模式、跨区、长上下文、缓存或优惠会改变费率。执行当日和账户实际部署区域的账单规则优先，文档价格只作为可复核假设。[阿里云官方模型价格表](https://help.aliyun.com/zh/model-studio/model-pricing)

成本计算：

```text
单次成本 = inputTokens × inputPrice / 1,000,000
         + outputTokens × outputPrice / 1,000,000
月成本 = 单次成本 × 每月面试数
```

`record-model-cost.ps1` 从后端安全 usage 日志汇总成功的面试计划、回答追问和报告生成 / 核验调用，并把观测到的每次完整面试成本乘以明确假设的 100 次 / 月。可以传入 `-SinceUtc` 只评估当前构建之后的新会话。只有日志 usage 全部存在、三份面试与报告完整、无模型或报告失败且估算不超过 ¥300 时，才写 `budgetPass=true`。模型性能 JSON 中的 `monthlyCallsAtBudgetEstimate` 仅反映短 SSE 探针，不能作为产品月成本通过证据。结构化校验 advisor 只会在输出未通过校验时发起受限重试；实际运行需结合 `model_usage` 日志和供应商账单核对这类重试成本。
