package local.interviewmirror.backend.resumes;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import local.interviewmirror.backend.common.ApiException;

class ResumeParserTest {
    static ResumeProperties properties(int limit, Duration timeout) {
        return new ResumeProperties(false, timeout, limit, 50, 2, "chi_sim+eng", "", Duration.ofSeconds(180),
                Duration.ofMinutes(10), Duration.ofMinutes(15), "test:resumes", "test", 60, 5, 5, java.util.List.of("pdf", "doc", "docx", "txt"));
    }
    @Test void actualTikaProcessExtractsChineseAndEnglishText() {
        ResumeParser parser = new ResumeParser(properties(100000, Duration.ofMinutes(2)));
        byte[] bytes = "姓名：张三\n技能：Java、Redis\nProject: API development".getBytes(StandardCharsets.UTF_8);
        assertEquals("text/plain", parser.detect(bytes, "resume.txt"));
        String text = parser.parse(bytes, "resume.txt", "text/plain");
        assertTrue(text.contains("张三")); assertTrue(text.contains("API development"));
        assertThrows(ApiException.class, () -> parser.detect(bytes, "fake.pdf"));
    }
    @Test void textLimitAndDeadlineRejectRatherThanSilentlyTruncate() {
        ResumeParser limited = new ResumeParser(properties(10, Duration.ofMinutes(2)));
        ApiException tooLong = assertThrows(ApiException.class, () -> limited.parse("Java project details ".repeat(20).getBytes(StandardCharsets.UTF_8), "resume.txt", "text/plain"));
        assertEquals("RESUME_TEXT_LIMIT", tooLong.code());
        ResumeParser deadline = new ResumeParser(properties(100000, Duration.ofMillis(1)));
        assertEquals("PARSER_TIMEOUT", assertThrows(ApiException.class,
                () -> deadline.parse("resume".getBytes(StandardCharsets.UTF_8), "resume.txt", "text/plain")).code());
    }
    @Test void actualTikaParsesWord97AndDocx() throws Exception {
        ResumeParser parser = new ResumeParser(properties(100000, Duration.ofMinutes(2)));
        byte[] doc;
        try (var input = getClass().getResourceAsStream("/resumes/word97.doc")) { doc = input.readAllBytes(); }
        assertEquals("application/msword", parser.detect(doc, "resume.doc"));
        assertFalse(parser.parse(doc, "resume.doc", "application/msword").isBlank());
        var data = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(data)) {
            var entries = java.util.Map.of(
                    "[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
                    "_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
                    "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>张三 Java Redis 项目经历</w:t></w:r></w:p></w:body></w:document>");
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        String type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        assertEquals(type, parser.detect(data.toByteArray(), "resume.docx"));
        assertTrue(parser.parse(data.toByteArray(), "resume.docx", type).contains("张三 Java Redis"));
    }
}
