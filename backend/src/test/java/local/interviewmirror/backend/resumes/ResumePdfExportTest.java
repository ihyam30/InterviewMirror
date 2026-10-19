package local.interviewmirror.backend.resumes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;
import local.interviewmirror.backend.files.ObjectStorage;
import local.interviewmirror.backend.reports.ReportPdfService;
import local.interviewmirror.backend.reports.ReportRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ResumePdfExportTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void exportedResumePdfContainsFeedbackButNoEvidenceQuotesOrLocations() throws Exception {
        var service = new ReportPdfService(mock(ReportRepository.class), mock(ObjectStorage.class), JSON,
                "C:/Windows/Fonts/msyh.ttc");
        var analysis = new ResumeViews.Analysis(UUID.randomUUID(), UUID.randomUUID(), "resume.v1", 82,
                12, 13, 18, 8, 31,
                JSON.readTree("[{\"text\":\"清晰呈现项目成果\",\"evidence\":[{\"quote\":\"PRIVATE_QUOTE_SENTINEL\",\"sourceLocation\":\"resume_text:10:30\"}]}]"),
                JSON.readTree("[{\"category\":\"项目经历\",\"priority\":\"HIGH\",\"issue\":\"缺少量化结果\",\"recommendation\":\"补充性能提升比例\",\"evidence\":[{\"quote\":\"PRIVATE_SUGGESTION_EVIDENCE\",\"sourceLocation\":\"resume_text:30:55\"}]}]"),
                Instant.parse("2026-10-19T00:00:00Z"));

        byte[] pdf = service.renderResume("林同学简历", analysis);

        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("82 / 100"));
            assertTrue(text.contains("清晰呈现项目成果"));
            assertTrue(text.contains("项目经历"));
            assertTrue(text.contains("HIGH"));
            assertTrue(text.contains("缺少量化结果"));
            assertTrue(text.contains("补充性能提升比例"));
            assertFalse(text.contains("PRIVATE_QUOTE_SENTINEL"));
            assertFalse(text.contains("PRIVATE_SUGGESTION_EVIDENCE"));
            assertFalse(text.contains("resume_text:10:30"));
            assertFalse(text.contains("resume_text:30:55"));
        }
    }
}
