package local.interviewmirror.backend.resumes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ResumeRepositoryTest {
    @Test void filenameWithOnlyAnExtensionKeepsAVisibleTitle() {
        assertEquals(".txt", ResumeRepository.titleFromFilename(".txt"));
        assertEquals("resume", ResumeRepository.titleFromFilename("resume.txt"));
    }
}
