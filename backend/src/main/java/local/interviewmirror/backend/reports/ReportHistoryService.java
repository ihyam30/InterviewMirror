package local.interviewmirror.backend.reports;

import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.files.ObjectStorage;
import local.interviewmirror.backend.reports.ReportRepository.PdfRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ReportHistoryService {
    private static final Logger log = LoggerFactory.getLogger(ReportHistoryService.class);
    private final ReportRepository reports;
    private final ObjectStorage storage;

    public ReportHistoryService(ReportRepository reports, ObjectStorage storage) {
        this.reports = reports;
        this.storage = storage;
    }

    public void delete(UUID ownerId, UUID interviewId) {
        ReportSource source = reports.loadSource(ownerId, interviewId);
        if (!"COMPLETE".equals(source.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "INTERVIEW_NOT_COMPLETE", "只有已完成面试的报告记录可以删除。");
        }

        PdfRow pdf = reports.findPdfForInterview(ownerId, interviewId).orElse(null);
        if (pdf != null) {
            try {
                storage.delete(pdf.objectKey());
            } catch (Exception failure) {
                log.warn("report_history_delete_failed interview={} owner={} failure_type={}",
                        interviewId, ownerId, failure.getClass().getSimpleName());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REPORT_DELETE_FAILED",
                        "报告文件删除失败，报告记录仍保留，请稍后重试。");
            }
        }

        if (!reports.deleteCompletedInterview(ownerId, interviewId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "报告不存在。");
        }
    }
}
