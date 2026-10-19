package local.interviewmirror.backend.resumes;

import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ResumeMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong outbox = new AtomicLong(), cleanup = new AtomicLong(), recoveries = new AtomicLong();
    public ResumeMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("resume.outbox.pending", outbox, AtomicLong::get).register(registry);
        Gauge.builder("resume.cleanup.failed", cleanup, AtomicLong::get).register(registry);
        Gauge.builder("resume.recovery.count", recoveries, AtomicLong::get).register(registry);
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void refresh() {
        outbox.set(jdbc.queryForObject("SELECT COUNT(*) FROM resume_analysis_outbox WHERE delivered_at IS NULL", Long.class));
        cleanup.set(jdbc.queryForObject("SELECT COUNT(*) FROM resume_object_cleanup WHERE completed_at IS NULL AND attempts>0", Long.class));
        recoveries.set(jdbc.queryForObject("SELECT COALESCE(SUM(analyze_recovery_count),0) FROM resumes", Long.class));
    }
}
