package local.interviewmirror.backend.interviews;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers durable answer transitions after a process restart or a stale worker lease. */
@Component
@ConditionalOnProperty(prefix = "interviewmirror.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InterviewRecoveryWorker {
    private final InterviewService interviews;

    public InterviewRecoveryWorker(InterviewService interviews) {
        this.interviews = interviews;
    }

    @Scheduled(fixedDelayString = "${interviewmirror.graph.recovery-interval:PT2S}")
    public void recover() {
        interviews.recover();
    }
}
