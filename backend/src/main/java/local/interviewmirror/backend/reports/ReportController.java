package local.interviewmirror.backend.reports;

import java.util.List;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiResponse;
import local.interviewmirror.backend.security.AccountPrincipal;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {
    private final ReportService reports;
    private final ReportPdfService pdf;
    private final ReportHistoryService history;
    public ReportController(ReportService reports, ReportPdfService pdf, ReportHistoryService history) {
        this.reports = reports; this.pdf = pdf; this.history = history;
    }

    @PostMapping("/api/v1/interviews/{id}/reports")
    ApiResponse<ReportRepository.ReportTask> generate(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(reports.request(user(auth), id));
    }
    @PostMapping("/api/v1/interviews/{id}/reports/retry")
    ApiResponse<ReportRepository.ReportTask> retryInterview(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(reports.retry(user(auth), id));
    }
    @GetMapping("/api/v1/interviews/{id}/report-status")
    ApiResponse<ReportService.ReportStatus> status(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(reports.status(user(auth), id));
    }
    @GetMapping("/api/v1/reports")
    ApiResponse<List<ReportService.ReportSummary>> list(Authentication auth) { return ApiResponse.of(reports.list(user(auth))); }
    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/interviews/{id}/report")
    ApiResponse<Void> delete(Authentication auth, @PathVariable UUID id) {
        history.delete(user(auth), id);
        return ApiResponse.of(null);
    }
    @GetMapping("/api/v1/reports/{id}")
    ApiResponse<ReportService.ReportDetail> detail(Authentication auth, @PathVariable UUID id) { return ApiResponse.of(reports.get(user(auth), id)); }
    @GetMapping("/api/v1/reports/{id}/gap-analysis")
    ApiResponse<ReportService.GapDetail> gap(Authentication auth, @PathVariable UUID id) { return ApiResponse.of(reports.getGap(user(auth), id)); }
    @PostMapping("/api/v1/reports/{id}/gap-analysis/retry")
    ApiResponse<Void> retryGap(Authentication auth, @PathVariable UUID id) { reports.retryGap(user(auth), id); return ApiResponse.of(null); }
    @GetMapping("/api/v1/reports/{id}/pdf")
    ResponseEntity<ByteArrayResource> pdf(Authentication auth, @PathVariable UUID id) {
        byte[] bytes = pdf.getOrCreate(user(auth), id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(bytes.length)
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("interviewmirror-report-" + id + ".pdf").build().toString())
                .body(new ByteArrayResource(bytes));
    }
    private static UUID user(Authentication auth) { return ((AccountPrincipal) auth.getPrincipal()).id(); }
}
