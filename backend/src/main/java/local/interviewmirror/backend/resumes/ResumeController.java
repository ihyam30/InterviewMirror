package local.interviewmirror.backend.resumes;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import local.interviewmirror.backend.common.ApiResponse;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.reports.ReportPdfService;
import local.interviewmirror.backend.security.AccountPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ResumeController {
    private final ResumeService resumes;
    private final ResumeUploadLimiter limiter;
    private final ReportPdfService pdf;
    public ResumeController(ResumeService resumes, ResumeUploadLimiter limiter, ReportPdfService pdf) {
        this.resumes = resumes; this.limiter = limiter; this.pdf = pdf;
    }
    @PostMapping(path = "/api/v1/resumes/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<ResumeViews.Resume> upload(Authentication auth, HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        limiter.check(userId(auth), request.getRemoteAddr());
        return ApiResponse.of(resumes.upload(userId(auth), file));
    }
    @GetMapping("/api/v1/resumes")
    ApiResponse<ResumeViews.Page<ResumeViews.Resume>> list(Authentication auth,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean usableOnly) {
        return ApiResponse.of(resumes.list(userId(auth), page, size, usableOnly));
    }
    @GetMapping("/api/v1/resumes/{id}/detail")
    ApiResponse<ResumeViews.Detail> detail(Authentication auth, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int analysisPage, @RequestParam(defaultValue = "0") int interviewPage,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(resumes.detail(userId(auth), id, analysisPage, interviewPage, size));
    }
    @GetMapping("/api/v1/resumes/{id}/preview")
    ApiResponse<ResumeViews.Resume> preview(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(resumes.get(userId(auth), id));
    }
    @GetMapping("/api/v1/interview-sources/resumes/{id}")
    ApiResponse<ResumeViews.Resume> source(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(resumes.usable(userId(auth), id));
    }
    @PostMapping("/api/v1/resumes/{id}/reanalyze")
    ApiResponse<ResumeViews.Resume> reanalyze(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(resumes.reanalyze(userId(auth), id));
    }
    @DeleteMapping("/api/v1/resumes/{id}")
    ResponseEntity<ApiResponse<ResumeViews.Resume>> delete(Authentication auth, @PathVariable UUID id) {
        resumes.delete(userId(auth), id);
        return ResponseEntity.accepted().body(ApiResponse.of(resumes.get(userId(auth), id)));
    }
    @GetMapping("/api/v1/resumes/{id}/file")
    ResponseEntity<InputStreamResource> file(Authentication auth, @PathVariable UUID id) {
        var row = resumes.require(userId(auth), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(row.contentType())).contentLength(row.fileSize())
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(row.originalFilename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(resumes.download(userId(auth), id)));
    }
    @GetMapping("/api/v1/resumes/{id}/export")
    ResponseEntity<byte[]> export(Authentication auth, @PathVariable UUID id) {
        var resume = resumes.usable(userId(auth), id);
        if (resume.latestAnalysis() == null) throw new ApiException(HttpStatus.CONFLICT, "RESUME_ANALYSIS_MISSING", "还没有成功的简历分析结果。");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("resume-analysis-" + id + ".pdf").build().toString())
                .body(pdf.renderResume(resume.title(), resume.latestAnalysis()));
    }
    private static UUID userId(Authentication auth) { return ((AccountPrincipal) auth.getPrincipal()).id(); }
}
