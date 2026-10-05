package local.interviewmirror.backend.reports;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "interviewmirror.report", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class ReportWorker {
    private final ReportService reports;
    private final String workerId;
    private final Duration lease;
    public ReportWorker(ReportService reports,
            @Value("${interviewmirror.report.worker-id:local-report-worker}") String workerId,
            @Value("${interviewmirror.report.lease:PT5M}") Duration lease) {
        this.reports = reports; this.workerId = workerId; this.lease = lease;
    }
    @Scheduled(fixedDelayString = "${interviewmirror.report.poll-interval:PT2S}")
    public void poll() { reports.processOne(workerId, lease); }
}
