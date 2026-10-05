# 报告生成流程

## 数据流

```text
Interview COMPLETE
  → PostgreSQL report_tasks (REPORT, PENDING)
  → Worker 使用租约原子领取
  → 读取 owner-scoped interview 与 source snapshot
  → 构建有类型、带 SHA-256 的 evidence catalog
  → Spring AI 生成结构化候选结果
  → 使用单独的 verifier 提示再次核验总体评价
  → 服务端校验 verifier 选择的 evidence ID 与 TURN 来源
  → 未通过核验时回退为带引用的原始回答摘录
  → 计算总分与 NOT_APPLICABLE / UNASSESSED 状态
  → interview_reports 持久化
  → 报告任务完成（岗位差异分析功能已停用）
```

## 任务生命周期

```text
PENDING → PROCESSING → SUCCESS
                    ↘ FAILED → 人工 retry → PENDING
```

`report_tasks` 对 `(interview_id, task_type)` 有唯一约束。Worker 只领取 `REPORT` 类型任务，通过数据库条件更新设置 `worker_id`、`lease_until`；任务运行时间超过租约会被回收。重复请求不会产生重复报告。旧版遗留的 `GAP_ANALYSIS` 任务不会再被领取。

历史记录按每个已完成的 `interview_id` 单独返回；数据库对报告的 interview ID 有唯一约束，不会用新面试覆盖旧报告。标题格式为“完成日期 + 用户名 + 第 N 次综合面试报告”，专项报告则包含题库名称和该题库的练习次数。报告生成期间只显示进度状态，不允许打开详情；失败状态会显示安全错误原因并提供重试入口。

启动配置位于 `interviewmirror.report`：worker 开关、worker ID、轮询间隔、租约、模型超时和可选 CJK 字体路径。Compose 默认启用 worker，模型默认遵从面试模型的启用状态。默认模型超时为 180 秒、任务租约为 5 分钟，以避免模型响应接近 90 秒时被过早判失败；两项均可通过环境变量调整。超时和模型不可用会保存为不同错误码，日志记录根因类型但不记录异常正文或简历/回答内容。

## 报告和证据

输入只来自创建 interview 时冻结的简历/题库/JD 快照，以及本场已回答 turn。模型提示中资料作为不可信内容处理。证据目录优先为每个回答保留证据 ID，再加入 JD 和简历文本；模型只能引用目录中的 ID。服务端会校验：

- 每个 turn 的反馈只引用该 turn 的回答证据；不允许错引其他题或不存在的 ID。
- 总体评价经过一次单独的证据核验模型调用。Verifier 会同时看到每条回答的完整文本、可引用的分段证据及其 ID，分段可共同支撑总结，减少长回答被截断后造成的误判。服务端只接受存在且来源为 `TURN` 的引用，并记录 `MODEL_SUPPORTED` 状态。若总结不受支持、verifier 调用失败或引用无效，总体评价改为中性提示、不展示原始回答摘录，状态为 `UNVERIFIED`；历史 `EXTRACTIVE_FALLBACK` 报告读取和导出时也会按此规则清洗。未核验报告可从详情或历史列表重新生成。无回答的空报告状态为 `NOT_APPLICABLE`。`summary.evidence` 与 `summary.overallReviewEvidence` 都只关联总体评价证据。
- 评分值在 1–5；未引用回答证据的维度降为 `UNASSESSED`。
- 六个固定维度必须完整出现。无 JD 时岗位匹配维度由服务端判定为不适用；报告没有协作方式匹配维度。
- 优势、风险、建议和学习路径必须有回答证据；服务端总分由可评估维度计算。
- 提前结束的面试会标注“本次面试提前结束，部分能力未充分覆盖”。

当前持久化报告 Schema 为 `1.4.0`，定义见 [`schemas/report.v1.4.schema.json`](schemas/report.v1.4.schema.json)；`1.1.0` 至 `1.3.0` 仅代表历史报告结构。报告生成 Prompt 为 `phase4.report.v1.7`，未完成维度定向复核 Prompt 为 `phase4.dimension-review.v1`，总体评价核验 Prompt 为 `phase4.summary-evidence.v1.2`。评分提示明确区分“回答薄弱但有直接相关证据”（应低分）与“本场没有相关回答证据”（未评估），并要求各已评分维度引用自己的相关 TURN 证据。若首次评分仍有适用维度缺少有效 TURN 引用，系统会额外进行一次仅针对这些维度的定向复核；没有相关回答证据时仍保留未评估。服务端仍会拒绝没有有效回答引用的分数，日志只记录报告/面试 ID、维度、原因码和是否存在引用/分数，不记录回答内容。历史报告存在适用维度未评估时，可从历史列表或详情页重新排队生成；新结果仍按同一证据规则校验。总体评价核验是使用同一配置模型的独立提示调用，不是独立模型或形式化证明，因此仍可能判断错误；审查页面和 PDF 应结合原始证据人工复核。报告生成需要总体评价核验调用；有未完成维度时还会再增加一次定向复核调用。输入证据内容不复制到任务错误信息或日志。

## 岗位差异分析（已停用）

当前版本不创建、领取或重试 `GAP_ANALYSIS` 任务。报告详情将差异状态规范为 `NOT_APPLICABLE`；旧版详情和重试 API 返回 `410 Gone`。历史数据库记录保留，不参与新报告或 PDF 生成。

## 删除与保留

报告随所属 interview 外键级联删除。PDF 对象以 owner 和 report UUID 分区、文件摘要命名，元数据保存 PDF SHA-256、大小及源内容 SHA-256。源摘要基于报告内容和 PDF 渲染版本；切换到无差异分析内容的渲染版本后，旧 PDF 缓存会失效并重新生成。对象不存在或摘要校验不一致时同样重新生成；对象写入与 DB 元数据写入失败时执行对象删除补偿。数据库和 MinIO 之间不是 ACID 事务，补偿失败会写安全日志供运维发现。
