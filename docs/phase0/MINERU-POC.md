# MinerU 本地解析 PoC

**契约版本：** `interviewmirror.mineru-eval.v1.1.0`  
**语料：** `data/poc/samples/manifest.json`，24 份全合成样例。  
**脚本：** `generate_samples.py`、`verify_dataset.py`、`run_mineru.py`、`score_parser_outputs.py`。

## 样例分布与复杂场景

| 类别 | 数量 | 格式和覆盖 |
|---|---:|---|
| 简历 | 10 | PDF 5、DOCX 5；姓名（虚构）、技能、教育、项目、量化成果 |
| 自定义题库 | 10 | Markdown/TXT、PDF/DOCX；多题拆分、题型、标签 |
| JD/办公室文档 | 4 | PDF/DOCX；技能、职责、协作要求 |
| 扫描型 PDF | 3（包含在上述 24 份内） | 图片化 PDF，用于 OCR 探索，不计首版 OCR 门槛 |

至少 5 项复杂排版由 manifest 的 `complexityTags` 标识：表格、公式、双栏、跨页、多页、扫描图像；本次共 15 个带复杂标签的样例。文件 SHA-256 和预期关键事实见各 `<ID>.manifest.json`。

## 运行命令

```powershell
pwsh -File .\scripts\poc\install_mineru.ps1
pwsh -File .\scripts\poc\run_mineru.ps1 -Tier basic -TimeoutSeconds 600
& .\scripts\poc\.mineru\Scripts\python.exe -m unittest discover -s .\scripts\poc -p 'test_*.py' -v
```

锁定 MinerU `4.0.10`、Python `3.12.14`、psutil `7.2.2`；生成依赖与传递依赖版本见 `scripts/poc/python-toolchain.lock.json` 及 `requirements-generator.lock.txt`。生成器、校验器、解析器和评分器都会拒绝非锁定 Python 版本；生成器会校验依赖版本、Windows 平台及微软雅黑字体文件 SHA，避免字体替换改变 OCR fixture。PDF/image 使用本地 ONNX basic bundle、ModelScope 只负责权重下载；DOCX 使用 MinerU `flash` 原生 Office 路线；TXT/Markdown 直接读取。权重位于忽略的 `scripts/poc/.mineru/models`；每个文档解析都设置 `MINERU_MODEL_SOURCE=local`，命令绝不传 `--remote`。basic 适配当前 3.84GB GPU 的 CPU/ONNX 路线，标准 tier VLM 不在首轮验收内。将临时目录绑定到工作区可写的忽略目录，避免权限边界影响解析。产物和测量数据写入被 `.gitignore` 排除的 `data/poc/results/mineru/`。MinerU 不得上传样例到外部解析服务。

## 指标定义

- 简历字段 micro-F1：对预先标注的精确字段/内容原子，按字段规范化后计数 TP/FP/FN；`F1 = 2PR/(P+R)`。空字段不能推断。验收线 F1 ≥90%。
- 题库召回率：提取出的标准化题目与 manifest 预期题目一对一匹配；`Recall = matched expected questions / expected questions`。另报告 precision，防止把段落误拆成伪题。验收线 recall ≥90%。
- 一对一匹配使用最大基数二分匹配，每条预测题和每道预期题最多计一次；子串包含关系只表示候选边，不允许一条合并预测同时计为多个 TP。回归用例位于 `scripts/poc/test_score_parser_outputs.py`。
- 评分器必须校验 `run-manifest.json` 的 Schema、当前 dataset manifest SHA、全体输入及逐样例 SHA、运行结果集合、每个输出文件 SHA。缺失/过期/不匹配时退出非零，不计算指标。
- 关键内容召回：resume/JD/question-bank manifest 所有关键原子的命中数 / 预期原子总数，门槛 ≥95%（本次任务补充的 M0a gate）。根目录 `DEVELOPMENT_PLAN.md` 没有单列 M0a，也没有把关键内容召回定义为独立 95% 门槛；它另列报告结论证据覆盖 ≥95%。本评测同时报告两者，不混为同一指标。
- 延迟：每文件 wall time；报告中位数、p95、超时/失败；资源峰值 RSS 由操作系统进程采样器记录，GPU 显存另记；本地解析/API 费用为 ¥0，不包括模型下载后的电力成本。

## 结果记录

| 指标 | 结果 |
|---|---|
| 文档数 / 格式 | 24；PDF 9、DOCX 8、Markdown 4、TXT 3 |
| 扫描 PDF / 复杂样例 | 3 / 15 |
| 数据集 manifest SHA-256（锁定工具链重生成批次） | `478b3e21e49f4ffdff52210572e6c7b77e9df7372eb7f03e859ec1097e69c031` |
| MinerU 版本 / 环境 | `4.0.10`；Python runtime 报告 `Windows-10-10.0.19045`；Python `3.12.14`、psutil `7.2.2`；本地 ONNX 模型包 13 个文件、858,204,914 bytes |
| Git 基线 | HEAD `4d856e17ab03111ea28e9f2d621d37867f88c071`；工作区 `dirty=true` |
| 解析覆盖 | 24/24 成功；17 个 MinerU PDF/DOCX，7 个 TXT/Markdown 直接读入 |
| 简历字段 F1 | `1.000`（TP=50、FP=0、FN=0，10 份；通过 ≥0.90） |
| 题库召回率 / 精确率 | `0.900`（TP=27/30，精确率 1.000，10 份；通过 ≥0.90，刚好压线） |
| 关键内容召回 | `89/92 = 0.9674`（通过本次任务补充的 ≥0.95 门槛） |
| PDF 延迟 / 峰值 RSS | n=9；p50 `14.49s`、最近秩 p95 `16.34s`；最大 RSS `862.99MB` |
| DOCX 延迟 / 峰值 RSS | n=8；p50 `5.63s`、最近秩 p95 `6.46s`；最大 RSS `132.27MB` |
| Markdown/TXT 直接读取 | n=7；Markdown p95 `1.01ms`、TXT p95 `0.90ms`；无推理 RSS 采样值 |
| 全语料总耗时 / 失败样例 | `176.47s`；0 失败。Q05 双栏/表格 PDF 预期 3 题全部未抽出，是主要质量风险；详见下文 |
| 本地推理/解析费用 | ¥0；无第三方解析 API。模型文件在 ModelScope 下载后本地运行，未产生 API 费用；不含耗电成本 |

统计脚本 `score_parser_outputs.py` 对 24 份输出重算得到上述准确率，逐文件耗时、峰值 RSS、输入/输出 SHA 和命令记录在运行器输出 `data/poc/results/mineru/run-manifest.json`（`interviewmirror.mineru-run.v1.1.0`），指标 JSON 写入 `data/poc/results/mineru-metrics.json`。该 results 目录被 `.gitignore` 排除；版本化文档记录摘要，执行命令可重新生成原始结果。每次评分先重新验证 manifest 与全部文件哈希，并只接受绑定到当前语料的 run manifest。

### 失败样例与质量风险

MinerU 进程对 24 份均返回成功，但 Q05（`two-column`, `table`, `complex`）结构化文字未包含预期的 3 道题：“Explain SSE reconnect behavior.”、“How do you persist partial model output?”、“What belongs in a trace identifier?”。按语料总量题库召回恰为 90%，没有余量；布局复杂题库进入 M1 前应增加人工确认/编辑兜底，并扩充双栏和表格回归样本。扫描件 R05、Q08、J04 的 OCR 属探索性覆盖，不据此承诺所有扫描件质量。

## 硬件与降级记录

执行环境：Python runtime 报告 `Windows-10-10.0.19045`；Java `21.0.12`；Python `3.12.14`；Docker Engine 可用；主机内存约 31.8GB；NVIDIA GPU 可见显存约 3.84GB；D 盘可用约 419.7GB。根据计划第 12 节，只自托管 MinerU；先尝试 CPU/ONNX 基础环境。扫描 PDF 的 OCR 只作探索，因为计划第 2 节明确“不作为首版 OCR 验收要求”。若 MinerU 在本机无法安装或质量不达线，不擅自改第三方付费 API，也不直接进入 M1：提交复现日志，暂停并按计划重新确认替代解析方案；可以评估轻量 Java PDF/DOCX 路线作为有记录的备选 PoC，但不将其伪装为 MinerU 通过。

复现结果：`pwsh -File .\scripts\poc\run_mineru.ps1 -Tier basic -TimeoutSeconds 600` 会使用 `.mineru` 下的锁定 Python 生成语料、验证、解析和评分；评分也可用 `& .\scripts\poc\.mineru\Scripts\python.exe .\scripts\poc\score_parser_outputs.py` 单独重算。受限 shell 的系统临时目录权限曾导致子进程 PermissionError；本地临时目录已重定向到 `.mineru/tmp`，在本机允许进程写入工作区临时目录的 PowerShell 中完成全量解析。安装和下载脚本使用本地工具/模型源，不把合成文档送至远程解析服务。参考 [MinerU 仓库及本地运行说明](https://github.com/opendatalab/MinerU)。

