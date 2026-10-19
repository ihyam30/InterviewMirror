package local.interviewmirror.backend.resumes;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResumeEvidenceTest {
    @Test void fullTextBeyond250FragmentsIsCoveredAndResolvedByBackend() {
        String text = "项目：优化接口，使用 Java 和 Redis。😀\n".repeat(5000);
        UUID resumeId = UUID.randomUUID();
        var catalog = new ResumeEvidenceCatalog(resumeId, text);
        assertTrue(catalog.all().size() > 250);
        assertEquals(text, catalog.all().stream().map(ResumeEvidenceCatalog.Evidence::quote).collect(java.util.stream.Collectors.joining()));
        var last = catalog.all().getLast();
        assertEquals(ResumeEvidenceCatalog.sha256(last.quote()), last.sha256());
        assertEquals(resumeId.toString(), last.sourceId());
        assertEquals(List.of(last), catalog.resolve(List.of(last.evidenceId(), last.evidenceId()), true));
        assertThrows(IllegalArgumentException.class, () -> catalog.resolve(List.of("FORGED"), true));
        assertThrows(IllegalArgumentException.class, () -> catalog.resolve(List.of(), true));
    }
    @Test void outputOnlySelectsIdsAndTotalIsCalculatedFromRubric() {
        var catalog = new ResumeEvidenceCatalog(UUID.randomUUID(), "使用Java实现接口。\n");
        var scores = new ResumeGradingService.Scores(15, 15, 20, 10, 40);
        var raw = new ResumeGradingService.Output(scores,
                List.of(new ResumeGradingService.RawStrength("列明Java实践", List.of("E001"))),
                List.of(new ResumeGradingService.RawSuggestion("项目", "高", "SUPPLEMENT", "未提供量化结果", "建议补充真实测试结果", List.of())));
        var result = ResumeGradingService.assemble(raw, catalog);
        assertEquals(100, result.scores().total());
        assertEquals("使用Java实现接口。\n", result.strengths().getFirst().evidence().getFirst().quote());
        assertTrue(result.suggestions().getFirst().evidence().isEmpty());
        var invalid = new ResumeGradingService.Output(scores, List.of(new ResumeGradingService.RawStrength("编造", List.of("E099"))), List.of());
        assertThrows(IllegalArgumentException.class, () -> ResumeGradingService.assemble(invalid, catalog));
        var noEvidence = new ResumeGradingService.Output(scores, List.of(), List.of(new ResumeGradingService.RawSuggestion("项目", "高", "ISSUE", "问题", "修改", List.of())));
        assertThrows(IllegalArgumentException.class, () -> ResumeGradingService.assemble(noEvidence, catalog));
        var outOfRange = new ResumeGradingService.Output(new ResumeGradingService.Scores(16, 15, 20, 10, 40), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> ResumeGradingService.assemble(outOfRange, catalog));
    }
}
