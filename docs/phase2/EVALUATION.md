# 阶段 2：数据集、质量与性能评测

## 可复现命令

前置条件：Docker Compose 服务健康、本地 MinerU Worker 使用 `.env` 中的独立 `MINERU_WORKER_TOKEN` 启动、锁定 MinerU Python/模型可用。不要设置真实模型 API Key；此评测不调用大模型或云解析。

```powershell
scripts\poc\.mineru\Scripts\python.exe scripts\phase2\generate_eval_dataset.py
scripts\poc\.mineru\Scripts\python.exe scripts\phase2\verify_eval_dataset.py
pwsh -File .\scripts\phase2\run-evaluation.ps1 -PerDocumentTimeout 600
scripts\poc\.mineru\Scripts\python.exe scripts\phase2\export_evaluation_snapshot.py
```

重新生成语料会写入 `data/poc/phase2/` 中被版本控制的 fixtures、annotations 和 manifest；生成器使用锁定依赖及固定 PDF/DOCX 创建参数。生成后先检查 manifest SHA 是否仍与当前受审结果一致，再运行全量评测。

数据集验证检查主清单与逐样本 SHA、每份独立 annotation 文件、类别分布和已知 Q05 双栏回归。全量脚本验证锁定的 Python / MinerU 依赖；使用本地账号 `demo2` 上传全部样本，通过资料和持久化解析任务 API 轮询到终态，按 task 的 `startedAt` 到数据库结构化提交完成时间统计真实解析耗时。失败样本仍计入分母。脚本最终只删除本轮创建的记录，并验证删除错误；评测结果不进入 Git。

结果文件：

```text
data/poc/results/phase2/live-evaluation.json   # .gitignore 中的本地测量结果
docs/phase2/results/live-evaluation.json       # 去掉随机任务 ID 的可审阅快照
```

成功评测后运行 `export_evaluation_snapshot.py` 可更新快照；脚本拒绝发布未达门槛或不足 30 个样本的结果。

## 数据集

| 类别 | 数量 | 格式分布 |
|---|---:|---|
| 简历 | 15 | PDF 8、DOCX 7 |
| 题库 | 15 | PDF 4、DOCX 2、Markdown 5、TXT 4 |
| 合计 | 30 | 含双栏、多栏、扫描/OCR、表格、公式、中英混排、多页和跨页标签 |

所有样本为合成资料，不含真实个人信息。前 20 份复用阶段 0 已 hash 校验的合成 fixtures，另 10 份由版本锁定脚本产生；每个样本都有独立 annotation JSON，包含文件 SHA、字段/题目 ground truth 和来源。新增样本的预期值在样本生成定义中先行确定，不由 MinerU 输出反向生成。

为保证 Windows 与 Linux checkout 结果一致，`.md`、`.txt`、`.json` 文件的 SHA-256 先将 CRLF / LF 统一规范为 CRLF；此规则兼容阶段 0 在 Windows 记录的文本 fixture 哈希。PDF 和 DOCX 按原始二进制字节计算。`test_eval_dataset_hashes.py` 覆盖跨换行一致性和二进制原样哈希。

标注范围说明：这是适合受控解析回归的合成 ground-truth 数据集，并非真实用户简历样本。本次没有独立第二标注者对 30 份材料做盲标或逐条复核，因此评测通过只证明该数据集上的解析表现，不证明真实材料的泛化能力；进入真实用户试用前应由另一位评审独立复核标注并加入明确授权、去标识化的代表性文档。

清单 SHA-256：

```text
43746bf24544772fe423311dd3f7982faba2e061e08c3d67bc51937efd39154e
```

## 指标定义

简历从五类字段（姓名、教育、技能、项目、量化结果）做规范化的字段级匹配。值去除大小写差异及非字母数字字符，再比较相应字段文本；额外非空预测记 FP，漏字段记 FN。汇总 `Precision = TP/(TP+FP)`、`Recall = TP/(TP+FN)`、`F1 = 2PR/(P+R)`。数组数据在字段维度聚合，不把整份 JSON 做字符串相等判断。

题库使用 `scripts/poc/score_parser_outputs.py` 的固定最大基数一对一匹配算法；同一预测不能命中多个标注问题，固定匹配阈值继承阶段 0。`Recall = 匹配题目数 / 标注题目总数`；同时记录预测数与 Precision，避免漏报重复/额外提取。

解析耗时在 DB worker claim 时写 `startedAt`，在结构化内容与成功任务状态的事务提交时写 `completedAt`，`durationMs` 是两者的差值。聚合全部 30 个完成任务；p95 使用 nearest-rank，即排序后 `ceil(0.95*n)` 位置。文本直读耗时很短，PDF/DOCX 计入真实本机 MinerU 运行时间。

## 最终真实运行结果

| 指标 | 结果 | 门槛 |
|---|---:|---:|
| 标注文档 | 30（简历 15、题库 15） | ≥30 |
| 解析终态成功 | 30/30 | 全部成功 |
| 简历字段 | TP 75、FP 0、FN 0 | — |
| 简历 Precision / Recall / F1 | 100% / 100% / 100% | F1 ≥90% |
| 题库标注题数 / 匹配 | 45 / 45 | — |
| 题库预测数 / Precision / Recall | 49 / 91.84% / 100% | Recall ≥90% |
| 解析 p50 / p95 / max | 5.758s / 16.039s / 19.389s | p95 ≤60s |
| MinerU | 4.0.10 | 本地自托管 |

复现记录：2026-10-03 UTC，Windows 11 x64 开发机，Python 3.12.14，4 个逻辑 CPU，PDF basic tier，DOCX flash tier，文本 UTF-8 直读；不经过远程解析，Worker 仅绑定 `127.0.0.1`。Git HEAD `f39e09abb529629fdc903067eaa0a4e505ee9bc0`；本轮工作树存在待审阅改动。任务/样本明细、时间戳和每个输入 SHA 存在被忽略的结果 JSON及去掉随机任务 ID 的版本化快照。

## 已知版式回归

| 样本 | 阶段 0 | 阶段 2 最终运行 |
|---|---|---|
| Q05 双栏题库 | 漏 3 道；阶段 0 总题库召回 90% | 3/3 命中。测试包含原始双栏版面和文本层，仍应留在回归集 |
| Q11 表格题库 | 新增合成回归 | MinerU 输出 HTML 表格；结构化解析器增加 HTML table 提取和题库标题过滤后 3/3 命中 |

全量结果有 4 个多余题目预测（49 个预测、45 个标注）；因此题库 Precision 为 91.84%。阶段门槛要求召回率，当前召回为 100%，并同时披露额外预测以便后续降噪。
