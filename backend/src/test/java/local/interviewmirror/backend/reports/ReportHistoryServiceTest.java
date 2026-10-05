package local.interviewmirror.backend.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.files.ObjectStorage;
import local.interviewmirror.backend.reports.ReportRepository.PdfRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ReportHistoryServiceTest {
    @Test
    void keepsReportRecordWhenPdfObjectDeletionFails() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID interviewId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        String objectKey = "users/" + ownerId + "/reports/" + reportId + "/report.pdf";
        ReportSource source = new ReportSource(interviewId, ownerId, "COMPREHENSIVE", "COMPLETE",
                "面试报告", null, null, null, null, null, Instant.now(), List.of());
        PdfRow pdf = new PdfRow(UUID.randomUUID(), reportId, ownerId, objectKey,
                "sha256", 1024, "source-sha256", Instant.now());
        ReportRepository reports = mock(ReportRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(reports.loadSource(ownerId, interviewId)).thenReturn(source);
        when(reports.findPdfForInterview(ownerId, interviewId)).thenReturn(Optional.of(pdf));
        doThrow(new IllegalStateException("object store unavailable")).when(storage).delete(objectKey);
        ReportHistoryService service = new ReportHistoryService(reports, storage);

        ApiException failure = assertThrows(ApiException.class, () -> service.delete(ownerId, interviewId));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.status());
        assertEquals("REPORT_DELETE_FAILED", failure.code());
        verify(reports).findPdfForInterview(ownerId, interviewId);
        verify(reports, never()).deleteCompletedInterview(ownerId, interviewId);
    }
}
