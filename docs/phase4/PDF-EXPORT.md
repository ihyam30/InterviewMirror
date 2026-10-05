# 面试报告 PDF

前端通过 `GET /api/v1/reports/{reportId}/pdf` 请求导出。后端先按当前用户查询报告，再从数据库读取或生成 PDF；调用方不能提交 object key，也不会获得公开 MinIO URL。

PDF 内容包含报告总评、适用能力维度分数、雷达图、逐题问答反馈、优势/风险、建议和学习路径，不包含岗位差异分析。内容较长时按 A4 分页并按中文字体字宽换行。未评估维度显示“未评估”，雷达不绘制为零分；提前结束说明会保留。

Docker 后端镜像安装 `fonts-wqy-microhei` 和 `fonts-noto-cjk`，PDFBox 优先使用 WenQuanYi TrueType 字体；Noto CJK 的 CFF/OpenType 字形不适合当前 PDFBox 嵌入路径。如果本地非容器运行，可通过 `PDF_CJK_FONT_PATH` 指定支持中文且具有 TrueType 字形轮廓的 `.ttf` 或 `.ttc` 字体；字体候选加载失败时会继续尝试其他候选，全部不可用才返回生成失败，不会返回损坏的空 PDF。

对象 key 采用 `users/{ownerId}/reports/{reportId}/{sha256}.pdf`。保存对象后写元数据；DB 保存失败时尝试补偿删除新对象。已存在对象需同时通过 owner 查询、大小和 SHA-256 校验才能复用。报告删除通过外键清理元数据；对象存储的级联清理需依赖已有数据清理策略，后续应按生命周期任务定期巡检孤儿对象。
