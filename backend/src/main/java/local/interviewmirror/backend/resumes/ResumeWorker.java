package local.interviewmirror.backend.resumes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import local.interviewmirror.backend.files.ObjectStorage;
import local.interviewmirror.backend.interviews.InterviewGraphRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "interviewmirror.resume", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class ResumeWorker {
    private static final Logger log = LoggerFactory.getLogger(ResumeWorker.class);
    private final ResumeRepository repository;
    private final ResumeService service;
    private final ResumeParser parser;
    private final ResumeGradingService grading;
    private final ObjectStorage storage;
    private final InterviewGraphRuntime graph;
    private final StringRedisTemplate redis;
    private final ResumeProperties properties;
    private final String consumer = "resume-" + UUID.randomUUID();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), runnable -> { Thread t = new Thread(runnable, "resume-analysis"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<UUID, ResumeRecord> active = new ConcurrentHashMap<>();
    private boolean groupReady;
    private long lastOutboxWarning;
    private long lastStreamWarning;
    private int consecutiveOutboxFailures;
    private int consecutiveStreamFailures;
    private static final int MAX_FAILURE_BACKOFF_SECONDS = 30;
    public ResumeWorker(ResumeRepository repository, ResumeService service, ResumeParser parser,
            ResumeGradingService grading, ObjectStorage storage, InterviewGraphRuntime graph,
            StringRedisTemplate redis, ResumeProperties properties) {
        this.repository = repository; this.service = service; this.parser = parser; this.grading = grading;
        this.storage = storage; this.graph = graph; this.redis = redis; this.properties = properties;
    }
    @Scheduled(fixedDelay = 2000)
    public void poll() {
        int backoffSeconds = 0;
        try {
            deliverOutbox();
            consecutiveOutboxFailures = 0;
        } catch (RuntimeException error) {
            backoffSeconds = Math.max(backoffSeconds, failed("outbox", error));
        }
        try {
            consumeStream();
            consecutiveStreamFailures = 0;
        } catch (RuntimeException error) {
            backoffSeconds = Math.max(backoffSeconds, failed("stream", error));
        }
        if (backoffSeconds > 0) {
            // A healthy dependency must not erase another stage's failure backoff.
            try { Thread.sleep(backoffSeconds * 1000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    private void deliverOutbox() {
        for (var task : repository.outbox()) {
            redis.opsForStream().add(properties.stream(), Map.of("resumeId", task.resumeId().toString(), "retryCount", Integer.toString(task.retryCount())));
            repository.delivered(task.id());
        }
    }
    private void consumeStream() {
        ensureGroup();
        if (executor.getActiveCount() + executor.getQueue().size() >= 2) return;
        var pending = redis.opsForStream().pending(properties.stream(), properties.group(), Range.unbounded(), 2);
        if (pending != null) for (var item : pending) {
            if (item.getElapsedTimeSinceLastDelivery().compareTo(properties.processingTimeout().plusMinutes(2)) > 0) {
                var claimed = redis.opsForStream().claim(properties.stream(), properties.group(), consumer,
                        properties.processingTimeout().plusMinutes(2), item.getId());
                if (claimed != null) claimed.forEach(this::submit);
            }
        }
        if (executor.getActiveCount() + executor.getQueue().size() >= 2) return;
        var messages = redis.opsForStream().read(Consumer.from(properties.group(), consumer), StreamReadOptions.empty().count(1),
                StreamOffset.create(properties.stream(), ReadOffset.lastConsumed()));
        if (messages != null) messages.forEach(this::submit);
    }
    /** Reports which dependency failed; the two have different fixes, so the log must keep them apart. */
    private int failed(String stage, RuntimeException error) {
        int failures;
        long lastWarning;
        if ("outbox".equals(stage)) {
            failures = ++consecutiveOutboxFailures;
            lastWarning = lastOutboxWarning;
        } else {
            failures = ++consecutiveStreamFailures;
            lastWarning = lastStreamWarning;
            groupReady = false;
        }
        if (System.currentTimeMillis() - lastWarning > 60000) {
            log.warn("resume poll failed stage={} failure={} consecutive={}", stage, error.getClass().getSimpleName(), failures);
            if ("outbox".equals(stage)) lastOutboxWarning = System.currentTimeMillis();
            else lastStreamWarning = System.currentTimeMillis();
        }
        return Math.min(failures, MAX_FAILURE_BACKOFF_SECONDS);
    }
    private void ensureGroup() {
        if (groupReady) return;
        redis.execute(new DefaultRedisScript<>("""
                local result=redis.pcall('XGROUP','CREATE',KEYS[1],ARGV[1],'0','MKSTREAM')
                if type(result)=='table' and result.err and not string.find(result.err,'BUSYGROUP') then
                  return redis.error_reply(result.err)
                end
                return 1
                """, Long.class), List.of(properties.stream()), properties.group());
        groupReady = true;
    }
    private void submit(MapRecord<String, Object, Object> message) {
        try { executor.execute(() -> consume(message)); }
        // A rejected message stays pending and is retried by a later poll instead of being lost.
        catch (java.util.concurrent.RejectedExecutionException error) { log.debug("resume message deferred id={}", message.getId()); }
    }
    void consume(MapRecord<String, Object, Object> message) {
        boolean acknowledge = false;
        ResumeRecord claim = null;
        int retry = 0;
        try {
            UUID id = UUID.fromString(String.valueOf(message.getValue().get("resumeId")));
            retry = Integer.parseInt(String.valueOf(message.getValue().get("retryCount")));
            if (retry < 0 || retry > 3) throw new IllegalArgumentException("Invalid retryCount");
            claim = repository.claim(id, retry).orElse(null);
            if (claim == null) { acknowledge = true; return; }
            active.put(claim.analyzeAttemptId(), claim);
            if (claim.resumeText().isBlank()) {
                String text;
                try (var input = storage.get(claim.storageKey())) {
                    byte[] bytes = input.readNBytes(10 * 1024 * 1024 + 1);
                    if (bytes.length > 10 * 1024 * 1024) throw new IllegalStateException("Legacy resume too large");
                    text = parser.parse(bytes, claim.originalFilename(), claim.contentType());
                }
                if (!repository.backfill(claim, text, service.structured(text))) { acknowledge = true; return; }
                ResumeRecord refreshed = repository.find(claim.id()).orElseThrow();
                if (!claim.analyzeAttemptId().equals(refreshed.analyzeAttemptId())) { acknowledge = true; return; }
                claim = refreshed;
            }
            repository.complete(claim, grading.analyze(claim));
            acknowledge = true;
        } catch (Exception error) {
            if (claim != null) {
                Throwable rootCause = rootCause(error);
                log.warn("resume analysis failed resume={} attempt={} retry={} type={} causeType={} reason={}",
                        claim.id(), claim.analyzeAttemptId(), retry, error.getClass().getSimpleName(),
                        rootCause.getClass().getSimpleName(), safeFailureReason(rootCause.getMessage()));
                try { repository.fail(claim); acknowledge = true; }
                catch (RuntimeException persistenceFailure) { log.warn("resume failure persistence unavailable resume={}", claim.id()); }
            } else if (error instanceof IllegalArgumentException) acknowledge = true;
        } finally {
            if (claim != null) active.remove(claim.analyzeAttemptId());
            if (acknowledge) try {
                redis.opsForStream().acknowledge(properties.stream(), properties.group(), message.getId());
                redis.opsForStream().delete(properties.stream(), message.getId());
            } catch (RuntimeException error) { log.warn("resume ack unavailable message={}", message.getId()); }
        }
    }
    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }
    private static String safeFailureReason(String message) {
        if (message == null || message.isBlank()) return "<no detail>";
        String sanitized = message.replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("(?i)(api[-_ ]?key|secret|password|token|authorization)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2[REDACTED]");
        return sanitized.length() <= 240 ? sanitized : sanitized.substring(0, 240) + "…";
    }
    @Scheduled(fixedDelay = 30000)
    public void heartbeat() {
        for (var claim : active.values()) try { repository.heartbeat(claim.id(), claim.analyzeAttemptId()); }
        catch (RuntimeException error) { log.warn("resume heartbeat failed resume={}", claim.id()); }
    }
    @Scheduled(fixedDelay = 60000)
    public void recover() {
        int recovered = repository.recover(Instant.now().minus(properties.pendingTimeout()), Instant.now().minus(properties.processingTimeout()));
        if (recovered > 0) log.info("resume tasks recovered count={}", recovered);
    }
    @Scheduled(fixedDelay = 5000)
    public void cleanup() {
        for (var task : repository.pendingCleanup()) {
            try { storage.delete(task.objectKey()); repository.cleaned(task.id()); }
            catch (Exception error) { repository.cleanupFailed(task); log.warn("resume object cleanup failed task={}", task.id()); }
        }
        for (var resume : repository.deletable()) {
            try {
                for (UUID interviewId : repository.deletingInterviews(resume.id())) graph.delete(interviewId);
                repository.finishDelete(resume.id());
            } catch (Exception error) { log.warn("resume graph cleanup failed resume={} type={}", resume.id(), error.getClass().getSimpleName()); }
        }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
