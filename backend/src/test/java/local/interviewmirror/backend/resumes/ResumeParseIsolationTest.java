package local.interviewmirror.backend.resumes;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The parse JVM must keep its scratch space inside the job directory and leave the system temp directory untouched. */
class ResumeParseIsolationTest {
    private static ResumeProperties properties(int limit, Duration timeout) {
        return new ResumeProperties(false, timeout, limit, 50, 2, "chi_sim+eng", "", Duration.ofSeconds(180),
                Duration.ofMinutes(10), Duration.ofMinutes(15), "test:resumes", "test", 60, 5, 5,
                List.of("pdf", "doc", "docx", "txt"));
    }
    @Test void successfulParseLeavesNoNewTemporaryFiles() throws Exception {
        Set<String> before = scratchEntries();
        ResumeParser parser = new ResumeParser(properties(100000, Duration.ofMinutes(2)));
        String text = "姓名：张三 技能：Java、Redis、PostgreSQL 项目：面试镜像平台".repeat(40);
        assertFalse(parser.parse(text.getBytes(StandardCharsets.UTF_8), "resume.txt", "text/plain").isBlank());
        assertEquals(Set.of(), newEntries(before));
    }
    @Test void forceKilledChildLeavesNoNewTemporaryFilesWithoutOcr() throws Exception {
        Set<String> before = scratchEntries();
        ResumeParser parser = new ResumeParser(properties(100000, Duration.ofMillis(1)));
        assertEquals("PARSER_TIMEOUT", assertThrows(local.interviewmirror.backend.common.ApiException.class,
                () -> parser.parse("resume".getBytes(StandardCharsets.UTF_8), "resume.txt", "text/plain")).code());
        assertEquals(Set.of(), newEntries(before));
    }
    @Test void forceKilledParseLeavesNoNewTemporaryFiles() throws Exception {
        Assumptions.assumeTrue(tesseractInstalled(), "Tesseract is not installed");
        Set<String> before = scratchEntries();
        byte[] pdf;
        try (var input = getClass().getResourceAsStream("/resumes/scanned-resume.pdf")) { pdf = input.readAllBytes(); }
        // Five seconds is long enough for the child JVM to extract the page image and start OCR, so the
        // force-kill path really does have scratch files to clean up.
        ResumeParser parser = new ResumeParser(properties(100000, Duration.ofSeconds(5)));
        assertEquals("PARSER_TIMEOUT", assertThrows(local.interviewmirror.backend.common.ApiException.class,
                () -> parser.parse(pdf, "scan.pdf", "application/pdf")).code());
        assertEquals(Set.of(), newEntries(before));
    }
    @Test void realOcrRunLeavesNoNewTemporaryFiles() throws Exception {
        Assumptions.assumeTrue(tesseractInstalled(), "Tesseract is not installed");
        Set<String> before = scratchEntries();
        byte[] pdf;
        try (var input = getClass().getResourceAsStream("/resumes/scanned-resume.pdf")) { pdf = input.readAllBytes(); }
        ResumeParser parser = new ResumeParser(properties(100000, Duration.ofMinutes(2)));
        String text = parser.parse(pdf, "scan.pdf", "application/pdf");
        assertTrue(text.contains("Java"), () -> "OCR produced unexpected text: " + text);
        assertEquals(Set.of(), newEntries(before));
    }
    private static boolean tesseractInstalled() {
        try {
            Process process = new ProcessBuilder("tesseract", "--list-langs").redirectErrorStream(true).start();
            process.getInputStream().readNBytes(65536);
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception error) {
            return false;
        }
    }
    /** Parse scratch directories in the system temp directory; entries that predate the test are not evidence. */
    private static Set<String> scratchEntries() throws Exception {
        try (Stream<Path> entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("interviewmirror-resume-") || name.startsWith("apache-tika-"))
                    .collect(Collectors.toSet());
        }
    }
    private static Set<String> newEntries(Set<String> before) throws Exception {
        Set<String> after = scratchEntries();
        after.removeAll(before);
        for (String name : after) {
            try (Stream<Path> contents = Files.walk(Path.of(System.getProperty("java.io.tmpdir"), name))) {
                fail("Leftover parse scratch directory " + name + " contains "
                        + contents.map(Path::toString).collect(Collectors.joining(", ")));
            }
        }
        return after;
    }
}
