package local.interviewmirror.backend.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.files.ObjectStorage;
import local.interviewmirror.backend.reports.ReportRepository.GapRow;
import local.interviewmirror.backend.reports.ReportRepository.PdfRow;
import local.interviewmirror.backend.reports.ReportRepository.ReportRow;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ReportPdfServiceTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String FONT = "C:/Windows/Fonts/msyh.ttc";

    @Test
    void rejectsFontThatFailsGlyphWidthProbeBeforeRendering() throws Exception {
        PDFont unsupportedFont = mock(PDFont.class);
        when(unsupportedFont.getStringWidth("面试报告"))
                .thenThrow(new UnsupportedOperationException("OTF fonts do not have a glyf table"));

        assertThrows(UnsupportedOperationException.class,
                () -> ReportPdfService.validateFontForOutput(unsupportedFont));
    }

    @Test
    void rendersChineseMultiPageReportAndKeepsUnassessedDimensionOutOfZeroScale() throws Exception {
        ReportRepository repository = mock(ReportRepository.class);
        MemoryStorage storage = new MemoryStorage();
        ReportPdfService service = new ReportPdfService(repository, storage, JSON, FONT);
        var report = JSON.readTree(reportJson());

        byte[] bytes = service.render(report, null);

        try (var document = Loader.loadPDF(bytes)) {
            assertTrue(document.getNumberOfPages() >= 2, "long report should paginate");
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("面试复盘报告"));
            assertTrue(text.contains("总体评价"));
            assertTrue(text.contains("未评估"));
            assertFalse(text.contains("证据 ["));
            assertFalse(text.contains("总体评价专属引用"));
            assertFalse(text.contains("一句话结论专属引用"));
            assertFalse(text.contains("一句话结论"));
            assertFalse(text.contains("协作方式匹配"));
        }
    }

    @Test
    void rendersSpecializedReportWithoutJobGapAnalysis() throws Exception {
        ReportPdfService service = new ReportPdfService(mock(ReportRepository.class), new MemoryStorage(), JSON, FONT);
        var report = JSON.readTree(reportJson().replace("COMPREHENSIVE", "QUESTION_BANK")
                .replace("NO_JD", "QUESTION_BANK_MODE"));

        byte[] bytes = service.render(report, null);

        try (var document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("题库专项"));
            assertTrue(text.contains("专项能力评分"));
            assertFalse(text.contains("项目经验："));
            assertFalse(text.contains("岗位匹配"));
            assertFalse(text.contains("协作方式匹配"));
            assertFalse(text.contains("岗位差异分析"));
            assertFalse(text.contains("综合面试未提供 JD"));
        }
    }

    @Test
    void invalidatesSpecializedPdfCachedBeforeModeSpecificSections() throws Exception {
        UUID owner = UUID.randomUUID(), reportId = UUID.randomUUID(), interviewId = UUID.randomUUID();
        String content = reportJson().replace("COMPREHENSIVE", "QUESTION_BANK");
        String oldKey = "users/" + owner + "/reports/" + reportId + "/old.pdf";
        byte[] oldPdf = "old cached pdf".getBytes(StandardCharsets.UTF_8);
        String oldSource = digest(("gap-turn-evidence-sanitization-v2\u0000" + content + "\u0000")
                .getBytes(StandardCharsets.UTF_8));
        ReportRepository repository = mock(ReportRepository.class);
        MemoryStorage storage = new MemoryStorage();
        storage.values.put(oldKey, oldPdf);
        when(repository.findReport(owner, reportId)).thenReturn(Optional.of(new ReportRow(reportId, interviewId,
                owner, "1.2.0", "READY", content, "{}", Instant.now(), Instant.now(), 100)));
        when(repository.displayTitle(owner, interviewId)).thenReturn("中文多页报告");
        when(repository.findPdf(owner, reportId)).thenReturn(Optional.of(new PdfRow(UUID.randomUUID(), reportId,
                owner, oldKey, digest(oldPdf), oldPdf.length, oldSource, Instant.now())));
        ReportPdfService service = new ReportPdfService(repository, storage, JSON, FONT);

        byte[] regenerated = service.getOrCreate(owner, reportId);

        try (var document = Loader.loadPDF(regenerated)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("专项能力评分"));
            assertFalse(text.contains("项目经验："));
            assertFalse(text.contains("岗位匹配"));
            assertFalse(text.contains("协作方式匹配"));
            assertFalse(text.contains("岗位差异分析"));
        }
        assertFalse(storage.values.containsKey(oldKey), "old mode-specific PDF artifact should be removed");
    }

    @Test
    void doesNotRenderLegacyNegativeTextForUnassessedGap() throws Exception {
        ReportPdfService service = new ReportPdfService(mock(ReportRepository.class), new MemoryStorage(), JSON, FONT);
        var report = JSON.readTree(reportJson());
        var legacyGap = JSON.readTree("{\"requirements\":[{\"resumeScore\":4,\"resumeStatus\":\"MATCH\","
                + "\"interviewScore\":null,\"interviewStatus\":\"UNASSESSED\",\"rationale\":\"候选人明显不具备该能力\","
                + "\"evidence\":[{\"sourceType\":\"JD\",\"sourceLocation\":\"jd\",\"quote\":\"具备项目经验\"}]}],"
                + "\"gaps\":[{\"status\":\"NOT_EVALUATED\",\"title\":\"项目经验\",\"reason\":\"候选人没有能力\","
                + "\"recommendation\":\"立即补课\",\"attribution\":[\"EXPRESSION_GAP\"],\"evidence\":[{\"sourceType\":\"TURN\",\"quote\":\"旧版无效引用\"}]},"
                + "{\"status\":\"GAP\",\"title\":\"旧版无回答证据差距\",\"reason\":\"缺少面试能力\","
                + "\"recommendation\":\"立即补课并判定不合格\",\"confidence\":0.9,\"priorityScore\":8,\"evidence\":["
                + "{\"sourceType\":\"JD\",\"sourceLocation\":\"jd\",\"quote\":\"岗位要求不能作为回答证据\"},"
                + "{\"sourceType\":\"RESUME\",\"sourceLocation\":\"resume\",\"quote\":\"简历内容不能作为回答证据\"}]},"
                + "{\"status\":\"PARTIAL_GAP\",\"title\":\"有效面试差距\",\"reason\":\"回答证据显示仍需补充\","
                + "\"recommendation\":\"补充具体案例\",\"evidence\":["
                + "{\"sourceType\":\"JD\",\"sourceLocation\":\"jd\",\"quote\":\"不应显示在面试证据栏\"},"
                + "{\"sourceType\":\"TURN\",\"sourceLocation\":\"turn:1:answer\",\"quote\":\"我会检查线程转储和监控指标\"}]}]}");

        byte[] bytes = service.render(report, legacyGap);

        try (var document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertFalse(text.contains(ReportGapContentSanitizer.NOT_EVALUATED_TEXT));
            assertFalse(text.contains("候选人明显不具备该能力"));
            assertFalse(text.contains("候选人没有能力"));
            assertFalse(text.contains("立即补课"));
            assertFalse(text.contains("未评估旧标题负面候选"));
            assertFalse(text.contains("旧版无回答证据差距"));
            assertFalse(text.contains("未评估要求"));
            assertFalse(text.contains("缺少面试能力"));
            assertFalse(text.contains("立即补课并判定不合格"));
            assertFalse(text.contains("岗位要求不能作为回答证据"));
            assertFalse(text.contains("简历内容不能作为回答证据"));
            assertFalse(text.contains("不应显示在面试证据栏"));
            assertFalse(text.contains("我会检查线程转储和监控指标"));
        }
    }

    @Test
    void pdfArtifactIsPrivateAndReusesVerifiedOwnerObject() throws Exception {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), reportId = UUID.randomUUID(), interviewId = UUID.randomUUID();
        ReportRepository repository = mock(ReportRepository.class);
        MemoryStorage storage = new MemoryStorage();
        ReportRow row = new ReportRow(reportId, interviewId, owner, "1.1.0", "READY", reportJson(), "{}",
                Instant.now(), Instant.now(), 100);
        when(repository.findReport(owner, reportId)).thenReturn(Optional.of(row));
        when(repository.displayTitle(owner, interviewId)).thenReturn("中文多页报告");
        when(repository.findPdf(owner, reportId)).thenReturn(Optional.empty());
        ReportPdfService service = new ReportPdfService(repository, storage, JSON, FONT);

        byte[] pdf = service.getOrCreate(owner, reportId);
        assertTrue(pdf.length > 500);
        verify(repository).savePdf(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.eq(reportId),
                org.mockito.ArgumentMatchers.contains("users/" + owner + "/reports/" + reportId + "/"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq((long) pdf.length),
                org.mockito.ArgumentMatchers.anyString());
        assertFalse(storage.values.isEmpty());

        when(repository.findReport(other, reportId)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.getOrCreate(other, reportId));
        verify(repository, never()).findPdf(other, reportId);
    }

    @Test
    void regeneratesMissingPdfArtifactWithoutGapAnalysis() throws Exception {
        UUID owner = UUID.randomUUID(), reportId = UUID.randomUUID(), interviewId = UUID.randomUUID();
        ReportRepository repository = mock(ReportRepository.class);
        MemoryStorage storage = new MemoryStorage();
        ReportRow row = new ReportRow(reportId, interviewId, owner, "1.1.0", "READY", reportJson(), "{}",
                Instant.now(), Instant.now(), 100);
        when(repository.findReport(owner, reportId)).thenReturn(Optional.of(row));
        when(repository.displayTitle(owner, interviewId)).thenReturn("中文多页报告");
        when(repository.findPdf(owner, reportId)).thenReturn(Optional.of(new PdfRow(UUID.randomUUID(), reportId,
                owner, "users/" + owner + "/reports/" + reportId + "/missing.pdf", "incorrect-digest", 123, "old-source", Instant.now())));
        ReportPdfService service = new ReportPdfService(repository, storage, JSON, FONT);

        byte[] pdf = service.getOrCreate(owner, reportId);

        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertFalse(text.contains("岗位差异分析"));
            assertFalse(text.contains("Java 故障排查能力"));
            assertFalse(text.contains("线上定位经验不足"));
        }
        assertFalse(storage.values.isEmpty(), "missing private object should be regenerated");
        verify(repository).savePdf(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.eq(reportId),
                org.mockito.ArgumentMatchers.contains("users/" + owner + "/reports/" + reportId + "/"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq((long) pdf.length),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void regeneratesOldPdfVersionAndIgnoresLegacyGapData() throws Exception {
        UUID owner = UUID.randomUUID(), reportId = UUID.randomUUID(), interviewId = UUID.randomUUID();
        ReportRepository repository = mock(ReportRepository.class);
        MemoryStorage storage = new MemoryStorage();
        ReportRow row = new ReportRow(reportId, interviewId, owner, "1.2.0", "READY", reportJson(), "{}",
                Instant.now(), Instant.now(), 100);
        GapRow gap = new GapRow(UUID.randomUUID(), reportId, interviewId, owner, "1.1.0", gapJson(), Instant.now());
        ReportPdfService service = new ReportPdfService(repository, storage, JSON, FONT);
        byte[] oldPdf = service.render(JSON.readTree(row.content()), JSON.readTree(gapJson()));
        String oldKey = "users/" + owner + "/reports/" + reportId + "/legacy-gap-version.pdf";
        storage.values.put(oldKey, oldPdf);
        String oldSource = digest(("mode-specific-report-sections-v6-cjk-true-type-font\u0000" + row.content()
                + "\u0000" + gap.content()).getBytes(StandardCharsets.UTF_8));
        PdfRow oldArtifact = new PdfRow(UUID.randomUUID(), reportId, owner, oldKey, digest(oldPdf), oldPdf.length,
                oldSource, Instant.now());
        when(repository.findReport(owner, reportId)).thenReturn(Optional.of(row));
        when(repository.displayTitle(owner, interviewId)).thenReturn("中文多页报告");
        when(repository.findPdf(owner, reportId)).thenReturn(Optional.of(oldArtifact));

        byte[] updatedPdf = service.getOrCreate(owner, reportId);

        try (var document = Loader.loadPDF(updatedPdf)) {
            String text = new PDFTextStripper().getText(document);
            assertFalse(text.contains("岗位差异分析"));
            assertFalse(text.contains("线上定位经验不足"));
        }
        assertFalse(storage.values.containsKey(oldKey), "the superseded private PDF should be removed");
        verify(repository).savePdf(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.eq(reportId),
                org.mockito.ArgumentMatchers.contains("users/" + owner + "/reports/" + reportId + "/"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq((long) updatedPdf.length),
                org.mockito.ArgumentMatchers.eq(ReportPdfService.sourceSha256(
                        service.normalizedReportContent(row.content(), "中文多页报告"), "")));
    }

    @Test
    void regeneratesLegacyPdfArtifactToApplyAnswerEvidenceSanitization() throws Exception {
        UUID owner = UUID.randomUUID(), reportId = UUID.randomUUID(), interviewId = UUID.randomUUID();
        ReportRepository repository = mock(ReportRepository.class);
        MemoryStorage storage = new MemoryStorage();
        ReportRow row = new ReportRow(reportId, interviewId, owner, "1.2.0", "READY", reportJson(), "{}",
                Instant.now(), Instant.now(), 100);
        String legacyGapContent = "{\"requirements\":[],\"gaps\":[{\"status\":\"GAP\",\"title\":\"岗位要求\","
                + "\"reason\":\"历史负面结论\",\"recommendation\":\"错误建议\",\"confidence\":0.9,\"priority\":\"HIGH\","
                + "\"evidence\":[{\"sourceType\":\"JD\",\"sourceLocation\":\"jd\",\"quote\":\"不能作为回答证据\"}]}],"
                + "\"summary\":{\"topGaps\":[{\"gapId\":\"GAP-01\",\"title\":\"岗位要求\",\"priority\":\"HIGH\"}]}}";
        GapRow gap = new GapRow(UUID.randomUUID(), reportId, interviewId, owner, "1.1.0", legacyGapContent, Instant.now());
        ReportPdfService service = new ReportPdfService(repository, storage, JSON, FONT);
        byte[] legacyPdf = service.render(JSON.readTree(row.content()), null);
        String oldKey = "users/" + owner + "/reports/" + reportId + "/legacy.pdf";
        storage.values.put(oldKey, legacyPdf);
        String legacySourceSha = digest((row.content() + "\u0000" + gap.content()).getBytes(StandardCharsets.UTF_8));
        when(repository.findReport(owner, reportId)).thenReturn(Optional.of(row));
        when(repository.displayTitle(owner, interviewId)).thenReturn("中文多页报告");
        when(repository.findPdf(owner, reportId)).thenReturn(Optional.of(new PdfRow(UUID.randomUUID(), reportId,
                owner, oldKey, digest(legacyPdf), legacyPdf.length, legacySourceSha, Instant.now())));

        byte[] regenerated = service.getOrCreate(owner, reportId);

        try (var document = Loader.loadPDF(regenerated)) {
            String text = new PDFTextStripper().getText(document);
            assertFalse(text.contains(ReportGapContentSanitizer.NOT_EVALUATED_TEXT));
            assertFalse(text.contains("历史负面结论"));
            assertFalse(text.contains("错误建议"));
            assertFalse(text.contains("不能作为回答证据"));
        }
        assertFalse(storage.values.containsKey(oldKey), "superseded legacy PDF should be removed");
        verify(repository).savePdf(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.eq(reportId),
                org.mockito.ArgumentMatchers.contains("users/" + owner + "/reports/" + reportId + "/"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq((long) regenerated.length),
                org.mockito.ArgumentMatchers.eq(ReportPdfService.sourceSha256(
                        service.normalizedReportContent(row.content(), "中文多页报告"), "")));
    }

    private static String gapJson() {
        return "{\"matchScore\":72,\"coverage\":{\"evaluated\":2,\"total\":3},"
                + "\"requirements\":[{\"text\":\"Java 故障排查能力\",\"resumeStatus\":\"SUPPORTED\","
                + "\"interviewStatus\":\"PARTIAL\",\"rationale\":\"回答缺少指标对照。\",\"evidence\":["
                + "{\"sourceType\":\"TURN\",\"sourceLocation\":\"turn:1:answer\",\"quote\":\"我会检查日志和指标。\"}]}],"
                + "\"gaps\":[{\"title\":\"线上定位经验不足\",\"status\":\"GAP\",\"reason\":\"缺少量化验证。\","
                + "\"recommendation\":\"补充一次线上故障复盘案例。\",\"evidence\":["
                + "{\"sourceType\":\"TURN\",\"sourceLocation\":\"turn:1:answer\",\"quote\":\"我会查看服务指标和日志。\"}]}]}";
    }

    private static String reportJson() {
        var dimensions = Map.of("TECHNICAL_DEPTH", 4, "PROJECT_EXPERIENCE", 4, "JOB_MATCH", 4,
                "COMMUNICATION", 4, "LOGICAL_STRUCTURE", 4, "PROBLEM_SOLVING", 4, "CULTURE_MATCH", 4);
        StringBuilder scores = new StringBuilder("{");
        int index = 0;
        for (var entry : dimensions.entrySet()) {
            if (index++ > 0) scores.append(',');
            String status = entry.getKey().equals("CULTURE_MATCH") ? "UNASSESSED" : "ASSESSED";
            String value = status.equals("ASSESSED") ? String.valueOf(entry.getValue()) : "null";
            scores.append('"').append(entry.getKey()).append("\":{\"status\":\"").append(status)
                    .append("\",\"value\":").append(value).append(",\"rationale\":\"本场报告使用可追溯证据进行评估。\",\"evidence\":[]}");
        }
        scores.append('}');
        String longText = "候选人回答证据片段：解释了如何使用线程池和指标观察系统负载，并说明异常时的处理步骤。";
        StringBuilder turns = new StringBuilder("[");
        for (int i = 0; i < 36; i++) {
            if (i > 0) turns.append(',');
            turns.append("{\"questionId\":\"q").append(i).append("\",\"kind\":\"MAIN\",\"question\":\"如何定位高并发服务中的延迟问题？\",\"answer\":\"")
                    .append(longText).append("\",\"feedback\":\"回答包含可验证的排查步骤，并可以补充量化结果。\",\"strengths\":[\"证据表达清楚\"],\"improvements\":[\"补充基线和结果\"],\"evidence\":[{\"sourceType\":\"TURN\",\"sourceLocation\":\"turn:1:answer\",\"quote\":\"")
                    .append(longText).append("\"}]}");
        }
        turns.append(']');
        return "{\"title\":\"中文多页报告\",\"mode\":\"COMPREHENSIVE\",\"completedAt\":\"2026-10-04T00:00:00Z\",\"scores\":"
                + scores + ",\"summary\":{\"overallScore\":80,\"overallScoreStatus\":\"PARTIAL\",\"overallReview\":\"本场面试整体表现稳定。\",\"oneLineConclusion\":\"有证据支持的表现\","
                + "\"overallReviewEvidence\":[{\"sourceType\":\"TURN\",\"sourceLocation\":\"turn:1:answer\",\"quote\":\"总体评价专属引用\"}],"
                + "\"oneLineConclusionEvidence\":[{\"sourceType\":\"TURN\",\"sourceLocation\":\"turn:1:answer\",\"quote\":\"一句话结论专属引用\"}]},\"turns\":" + turns
                + ",\"strengths\":[],\"risks\":[],\"recommendations\":[],\"learningPath\":[],\"gapAnalysis\":{\"status\":\"NOT_APPLICABLE\",\"notApplicableReason\":\"NO_JD\"}}";
    }

    private static String digest(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class MemoryStorage implements ObjectStorage {
        private final Map<String, byte[]> values = new ConcurrentHashMap<>();
        @Override public void put(String key, InputStream content, long size, String contentType) throws Exception { values.put(key, content.readAllBytes()); }
        @Override public InputStream get(String key) {
            byte[] bytes = values.get(key);
            if (bytes == null) throw new IllegalStateException("missing object");
            return new ByteArrayInputStream(bytes);
        }
        @Override public void delete(String key) { values.remove(key); }
    }
}
