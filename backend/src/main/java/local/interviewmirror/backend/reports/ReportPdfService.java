package local.interviewmirror.backend.reports;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.files.ObjectStorage;
import local.interviewmirror.backend.reports.ReportRepository.PdfRow;
import local.interviewmirror.backend.reports.ReportRepository.ReportRow;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ReportPdfService {
    private static final Logger log = LoggerFactory.getLogger(ReportPdfService.class);
    private static final String PDF_SOURCE_VERSION = "report-v1.6-no-evidence-spaced-items-no-next-step";
    private static final String UNASSESSED_DIMENSION_TEXT = "本场未评估。";
    private final ReportRepository reports;
    private final ObjectStorage storage;
    private final ObjectMapper json;
    private final String configuredFont;

    public ReportPdfService(ReportRepository reports, ObjectStorage storage, ObjectMapper json,
            @Value("${interviewmirror.report.pdf-font-path:}") String configuredFont) {
        this.reports = reports; this.storage = storage; this.json = json; this.configuredFont = configuredFont;
    }

    public byte[] getOrCreate(UUID ownerId, UUID reportId) {
        ReportRow report = reports.findReport(ownerId, reportId).orElseThrow(ReportPdfService::notFound);
        String displayTitle = reports.displayTitle(ownerId, report.interviewId());
        String reportContent = normalizedReportContent(report.content(), displayTitle);
        String sourceSha = sourceSha256(reportContent, "");
        PdfRow prior = reports.findPdf(ownerId, reportId).orElse(null);
        if (prior != null && Objects.equals(sourceSha, prior.sourceSha256())) {
            try (InputStream input = storage.get(prior.objectKey())) {
                byte[] bytes = input.readAllBytes();
                if (bytes.length == prior.sizeBytes() && sha256(bytes).equals(prior.sha256())) return bytes;
            } catch (Exception ignored) { /* regenerate from the owner's saved report below */ }
        }
        try {
            JsonNode root = json.readTree(reportContent);
            byte[] bytes = render(root, null);
            if (bytes.length == 0 || bytes.length > 20L * 1024 * 1024) throw new IllegalStateException("PDF size is invalid");
            String digest = sha256(bytes);
            String key = "users/" + ownerId + "/reports/" + reportId + "/" + digest + ".pdf";
            boolean objectStored = false;
            try {
                try (var data = new java.io.ByteArrayInputStream(bytes)) { storage.put(key, data, bytes.length, "application/pdf"); }
                objectStored = true;
                reports.savePdf(ownerId, reportId, key, digest, bytes.length, sourceSha);
            } catch (Exception failure) {
                if (objectStored) try { storage.delete(key); }
                catch (Exception cleanupFailure) { log.warn("report_pdf_cleanup_failed report={} owner={} failure_type={}", reportId, ownerId, cleanupFailure.getClass().getSimpleName()); }
                throw failure;
            }
            if (prior != null && !prior.objectKey().equals(key)) try { storage.delete(prior.objectKey()); } catch (Exception ignored) { }
            return bytes;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            Throwable root = e;
            for (int depth = 0; root.getCause() != null && depth < 16; depth++) root = root.getCause();
            log.error("report_pdf_generation_failed report={} owner={} failure_type={} failure_location={} root_cause_type={} root_cause_location={}",
                    reportId, ownerId, e.getClass().getSimpleName(), location(e), root.getClass().getSimpleName(), location(root));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REPORT_PDF_FAILED", "PDF 生成失败，请稍后重试。");
        }
    }

    private static String location(Throwable failure) {
        StackTraceElement[] stack = failure.getStackTrace();
        if (stack.length == 0) return "unknown";
        StackTraceElement frame = stack[0];
        return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    byte[] render(JsonNode report, JsonNode gap) throws Exception {
        try (PDDocument document = new PDDocument(); FontHandle handle = loadFont(document)) {
            PDFont font = handle.font();
            document.getDocumentInformation().setTitle(text(report.path("title"), "面试复盘报告"));
            document.getDocumentInformation().setAuthor("InterviewMirror");
            Canvas canvas = new Canvas(document, font);
            canvas.heading("面镜 InterviewMirror · 面试复盘报告", 20);
            canvas.body(text(report.path("title"), "面试报告"), 13, true);
            canvas.body("模式：" + mode(text(report.path("mode"), "")) + "    完成时间：" + text(report.path("completedAt"), ""), 9, false);
            if ("USER_ENDED".equals(text(report.path("completionReason"), ""))) {
                canvas.body("本次面试提前结束，部分能力未充分覆盖。", 9, true);
            }
            canvas.gap(12);

            JsonNode summary = report.path("summary");
            canvas.heading("总体评价", 14);
            String scoreStatus = text(summary.path("overallScoreStatus"), "UNASSESSED");
            canvas.body("综合得分：" + (summary.path("overallScore").isNumber() ? summary.path("overallScore").asInt() + " / 100" : "未评估"), 11, true);
            canvas.body(text(summary.path("overallReview"), "暂无总体评价"), 10, false);
            canvas.body("总体评估状态：" + scoreStatus, 8, false);
            canvas.gap(12);

            boolean specialized = "QUESTION_BANK".equals(text(report.path("mode"), ""));
            List<String> scoreKeys = specialized
                    ? List.of("TECHNICAL_DEPTH", "COMMUNICATION", "LOGICAL_STRUCTURE", "PROBLEM_SOLVING")
                    : List.of("TECHNICAL_DEPTH", "PROJECT_EXPERIENCE", "JOB_MATCH", "COMMUNICATION", "LOGICAL_STRUCTURE", "PROBLEM_SOLVING");
            canvas.heading(specialized ? "专项能力评分" : "六维能力评分", 14);
            JsonNode scores = report.path("scores");
            for (String key : scoreKeys) {
                JsonNode score = scores.path(key);
                canvas.body(label(key) + "：" + (score.path("status").asString().equals("ASSESSED") ? score.path("value").asInt() + " / 5" : "未评估")
                        + " · " + text(score.path("rationale"), ""), 9, false);
                canvas.gap(10);
            }
            drawRadar(canvas, scores, scoreKeys);
            canvas.body("雷达图维度顺序与上方评分一致；未评估维度留白，不按零分绘制。", 8, false);
            canvas.gap(12);

            canvas.heading("逐题反馈", 14);
            int turnNumber = 1;
            for (JsonNode turn : report.path("turns")) {
                canvas.body("第 " + turnNumber++ + " 题 · " + text(turn.path("kind"), "MAIN"), 10, true);
                canvas.body("问题：" + text(turn.path("question"), ""), 9, false);
                canvas.gap(12);
                canvas.body("回答：" + text(turn.path("answer"), ""), 9, false);
                canvas.body("反馈：" + text(turn.path("feedback"), ""), 9, false);
                writeStrings(canvas, "做得好", turn.path("strengths"));
                writeStrings(canvas, "待改进", turn.path("improvements"));
                canvas.gap(14);
            }
            writeInsightSection(canvas, "优势", report.path("strengths"));
            writeInsightSection(canvas, "待提升风险", report.path("risks"));
            canvas.heading("改进建议", 14);
            for (JsonNode item : report.path("recommendations")) {
                canvas.body("• " + text(item.path("action"), ""), 10, true);
                canvas.body("原因：" + text(item.path("why"), ""), 9, false);
                canvas.gap(12);
            }
            canvas.heading("学习路径", 14);
            for (JsonNode item : report.path("learningPath")) {
                canvas.body(item.path("order").asInt() + ". " + text(item.path("objective"), ""), 10, true);
                writeStrings(canvas, "练习", item.path("activities"));
                canvas.gap(12);
            }
            canvas.close();
            ByteArrayOutputStream output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }

    private static void writeInsightSection(Canvas canvas, String title, JsonNode values) throws Exception {
        canvas.heading(title, 14);
        for (JsonNode item : values) {
            canvas.body("• " + text(item.path("text"), ""), 10, false);
            canvas.gap(12);
        }
    }
    private static void writeStrings(Canvas canvas, String label, JsonNode values) throws Exception {
        for (JsonNode item : values) {
            canvas.body(label + "：" + text(item, ""), 9, false);
            canvas.gap(10);
        }
    }

    private static void drawRadar(Canvas canvas, JsonNode scores, List<String> keys) throws Exception {
        canvas.ensure(170);
        float cx = 290, cy = canvas.y() - 68, radius = 54;
        try (PDPageContentStream cs = new PDPageContentStream(canvas.document(), canvas.page(),
                PDPageContentStream.AppendMode.APPEND, true)) {
            for (int ring = 1; ring <= 5; ring++) {
                float r = radius * ring / 5f; float previousX = 0, previousY = 0;
                for (int i = 0; i <= keys.size(); i++) {
                    double angle = -Math.PI / 2 + 2 * Math.PI * (i % keys.size()) / keys.size();
                    float x = cx + (float)Math.cos(angle) * r, y = cy + (float)Math.sin(angle) * r;
                    if (i > 0) { cs.moveTo(previousX, previousY); cs.lineTo(x, y); cs.stroke(); }
                    previousX = x; previousY = y;
                }
            }
            float[] values = new float[keys.size()];
            boolean[] present = new boolean[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                JsonNode score = scores.path(keys.get(i));
                double angle = -Math.PI / 2 + 2 * Math.PI * i / keys.size();
                float x = cx + (float)Math.cos(angle) * radius;
                float y = cy + (float)Math.sin(angle) * radius;
                cs.moveTo(cx, cy); cs.lineTo(x, y); cs.stroke();
                if (score.path("status").asString().equals("ASSESSED") && score.path("value").isNumber()) {
                    values[i] = score.path("value").asInt() / 5f;
                    present[i] = true;
                }
            }
            cs.setStrokingColor(40 / 255f, 100 / 255f, 220 / 255f);
            for (int i = 0; i < keys.size(); i++) {
                int next = (i + 1) % keys.size();
                if (!present[i] || !present[next]) continue;
                double a = -Math.PI / 2 + 2 * Math.PI * i / keys.size();
                double b = -Math.PI / 2 + 2 * Math.PI * next / keys.size();
                cs.moveTo(cx + (float)Math.cos(a) * radius * values[i], cy + (float)Math.sin(a) * radius * values[i]);
                cs.lineTo(cx + (float)Math.cos(b) * radius * values[next], cy + (float)Math.sin(b) * radius * values[next]);
                cs.stroke();
            }
        }
        canvas.y(cy - radius - 12);
    }

    private FontHandle loadFont(PDDocument document) throws Exception {
        List<File> candidates = new ArrayList<>();
        if (configuredFont != null && !configuredFont.isBlank()) candidates.add(new File(configuredFont));
        candidates.addAll(List.of(new File("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"),
                new File("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                new File("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"),
                new File("C:/Windows/Fonts/msyh.ttc"), new File("C:/Windows/Fonts/simsun.ttc")));
        for (File file : candidates) {
            if (!file.isFile()) continue;
            TrueTypeCollection collection = null;
            try {
                if (file.getName().toLowerCase().endsWith(".ttc")) {
                    collection = new TrueTypeCollection(file);
                    TrueTypeFont font = null;
                    for (String name : List.of("WenQuanYiMicroHei", "WenQuanYi Micro Hei", "NotoSansCJKsc-Regular",
                            "Noto Sans CJK SC", "MicrosoftYaHei", "SimSun")) {
                        try { font = collection.getFontByName(name); if (font != null) break; } catch (RuntimeException ignored) { }
                    }
                    if (font != null) {
                        try {
                            PDFont embedded = PDType0Font.load(document, font, true);
                            embedded.encode("面试报告"); // Probe glyph access now; some CFF collections fail only on first use.
                            return new FontHandle(embedded, collection);
                        }
                        catch (Exception failure) { collection.close(); collection = null; throw failure; }
                    }
                    collection.close(); collection = null;
                } else {
                    PDFont embedded = PDType0Font.load(document, file);
                    embedded.encode("面试报告");
                    return new FontHandle(embedded, null);
                }
            } catch (Exception failure) {
                if (collection != null) try { collection.close(); } catch (Exception ignored) { }
                log.warn("report_pdf_font_candidate_rejected font={} failure_type={}",
                        file.getName(), failure.getClass().getSimpleName());
            }
        }
        throw new IllegalStateException("A CJK TrueType font is required for report PDF output");
    }
    private record FontHandle(PDFont font, TrueTypeCollection collection) implements AutoCloseable {
        @Override public void close() throws Exception { if (collection != null) collection.close(); }
    }

    private static String label(String key) { return switch (key) {
        case "TECHNICAL_DEPTH" -> "技术深度"; case "PROJECT_EXPERIENCE" -> "项目经验"; case "JOB_MATCH" -> "岗位匹配";
        case "COMMUNICATION" -> "沟通表达"; case "LOGICAL_STRUCTURE" -> "逻辑结构"; case "PROBLEM_SOLVING" -> "问题解决";
        default -> key; }; }
    private static String mode(String value) { return "QUESTION_BANK".equals(value) ? "题库专项" : "综合面试"; }
    private static String text(JsonNode value, String fallback) { return value == null || value.isNull() || value.isMissingNode() ? fallback : value.asString(fallback); }
    private static String sha256(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    static String sourceSha256(String reportContent, String gapContent) {
        try { return sha256((PDF_SOURCE_VERSION + "\u0000" + reportContent + "\u0000" + gapContent).getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("Could not fingerprint report PDF source", e); }
    }
    String normalizedReportContent(String reportContent, String title) {
        try {
            ObjectNode normalized = ((ObjectNode) json.readTree(reportContent)).deepCopy();
            normalized.put("title", title);
            if (normalized.path("summary") instanceof ObjectNode summary) {
                ReportSummaryEvidenceSanitizer.sanitize(summary);
            }
            if (normalized.path("scores") instanceof ObjectNode scores) {
                for (String key : List.of("TECHNICAL_DEPTH", "PROJECT_EXPERIENCE", "JOB_MATCH",
                        "COMMUNICATION", "LOGICAL_STRUCTURE", "PROBLEM_SOLVING")) {
                    if (scores.path(key) instanceof ObjectNode score
                            && "UNASSESSED".equals(text(score.path("status"), ""))) {
                        score.put("rationale", UNASSESSED_DIMENSION_TEXT);
                    }
                }
            }
            return json.writeValueAsString(normalized);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not normalize report title for PDF", failure);
        }
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "报告不存在。"); }

    private static final class Canvas {
        private final PDDocument document; private final PDFont font; private PDPage page; private PDPageContentStream stream; private float y;
        Canvas(PDDocument document, PDFont font) throws Exception { this.document=document; this.font=font; newPage(); }
        PDDocument document() { return document; } PDPage page() { return page; } float y() { return y; } void y(float value) { y=value; }
        void gap(float amount) { y -= amount; }
        void ensure(float height) throws Exception { if (y - height < 50) newPage(); }
        void heading(String value, float size) throws Exception { ensure(size + 16); gap(6); body(value, size, true); gap(3); }
        void body(String value, float size, boolean bold) throws Exception {
            if (value == null || value.isBlank()) return;
            for (String line : wrap(safeGlyphs(value), size, 515)) {
                ensure(size + 4); stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(48, y);
                stream.showText(line); stream.endText(); y -= size + 4;
            }
        }
        private List<String> wrap(String value, float size, float width) throws Exception {
            List<String> lines = new ArrayList<>(); StringBuilder line = new StringBuilder();
            for (String segment : value.replace("\r", "").split("\n", -1)) {
                for (int offset = 0; offset < segment.length();) {
                    int cp = segment.codePointAt(offset); String glyph = new String(Character.toChars(cp)); offset += Character.charCount(cp);
                    String trial = line + glyph;
                    if (!line.isEmpty() && font.getStringWidth(trial) * size / 1000f > width) { lines.add(line.toString()); line.setLength(0); }
                    line.append(glyph);
                }
                lines.add(line.toString()); line.setLength(0);
            }
            return lines;
        }
        private String safeGlyphs(String value) {
            StringBuilder out = new StringBuilder();
            value.codePoints().forEach(cp -> { String glyph = new String(Character.toChars(cp)); try { font.encode(glyph); out.append(glyph); } catch (Exception ignored) { out.append('□'); } });
            return out.toString();
        }
        private void newPage() throws Exception {
            if (stream != null) stream.close(); page = new PDPage(PDRectangle.A4); document.addPage(page);
            stream = new PDPageContentStream(document, page); y = 790;
            stream.setStrokingColor(228 / 255f, 232 / 255f, 240 / 255f); stream.moveTo(48, 38); stream.lineTo(547, 38); stream.stroke();
        }
        void close() throws Exception { if (stream != null) stream.close(); }
    }
}
