package local.interviewmirror.backend.resumes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import local.interviewmirror.backend.documents.StructuredDocumentParser;
import local.interviewmirror.backend.files.ObjectStorage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ResumeStructuredContentToggleTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void disabledConfigurationReturnsMarkerWithoutGeneratingStructuredContent() {
        var service = new ResumeService(mock(ResumeRepository.class), mock(ResumeParser.class),
                new StructuredDocumentParser(), mock(ObjectStorage.class), JSON, false);

        var result = JSON.readTree(service.structured("Name: Lin\nSkills: Java"));

        assertEquals("interviewmirror.resume-content.disabled.v1", result.path("schemaVersion").asString());
        assertTrue(result.size() == 1);
    }

    @Test
    void enabledConfigurationGeneratesStructuredContentFromExtractedText() {
        var service = new ResumeService(mock(ResumeRepository.class), mock(ResumeParser.class),
                new StructuredDocumentParser(), mock(ObjectStorage.class), JSON, true);

        var result = JSON.readTree(service.structured("Name: Lin\nSkills: Java, PostgreSQL"));

        assertEquals("interviewmirror.resume-content.v1", result.path("schemaVersion").asString());
        assertEquals("Lin", result.path("personalInfo").path("name").asString());
        assertTrue(result.path("skills").size() > 0);
    }
}
