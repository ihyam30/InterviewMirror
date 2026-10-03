package local.interviewmirror.backend.documents;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import local.interviewmirror.backend.files.FileService;
import local.interviewmirror.backend.files.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "interviewmirror.mineru", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentParseWorker {
    private static final Logger log = LoggerFactory.getLogger(DocumentParseWorker.class);
    private final DocumentRepository documents;
    private final local.interviewmirror.backend.files.FileRepository files;
    private final FileService fileService;
    private final DocumentParser parser;
    private final StructuredDocumentParser structuredParser;
    private final ObjectMapper json;
    private final String workerId;
    private final Duration lease;

    public DocumentParseWorker(DocumentRepository documents,
                               local.interviewmirror.backend.files.FileRepository files,
                               FileService fileService,
                               DocumentParser parser,
                               StructuredDocumentParser structuredParser,
                               ObjectMapper json,
                               @org.springframework.beans.factory.annotation.Value("${interviewmirror.mineru.worker-id:local-worker-1}") String workerId,
                               @org.springframework.beans.factory.annotation.Value("${interviewmirror.mineru.lease:PT3M}") Duration lease) {
        this.documents = documents;
        this.files = files;
        this.fileService = fileService;
        this.parser = parser;
        this.structuredParser = structuredParser;
        this.json = json;
        this.workerId = workerId;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${interviewmirror.mineru.poll-interval:PT2S}")
    public void poll() {
        documents.recoverExpired(Instant.now());
        processOne();
    }

    public boolean processOne() {
        Optional<ParseTaskRecord> claimed = documents.claimNext(workerId, lease);
        if (claimed.isEmpty()) return false;
        ParseTaskRecord task = claimed.get();
        Instant startedAt = task.startedAt() == null ? Instant.now() : task.startedAt();
        String failureCode = "PARSER_FAILED";
        try {
            ManagedDocumentRecord document = documents.findByIdAndOwner(task.documentId(), task.ownerId())
                    .orElseThrow(() -> new DocumentParseException("SOURCE_MISSING", "原始文件记录不存在，请重新上传。"));
            if (document.fileId() == null) throw new DocumentParseException("SOURCE_MISSING", "原始文件不存在，请重新上传。");
            StoredFile stored = files.findByIdAndOwner(document.fileId(), task.ownerId())
                    .orElseThrow(() -> new DocumentParseException("SOURCE_MISSING", "原始文件不存在，请重新上传。"));
            byte[] bytes;
            try (InputStream stream = fileService.download(task.ownerId(), stored.id())) {
                bytes = stream.readAllBytes();
            }
            String markdown = parser.parse(stored.originalFilename(), stored.contentType(), bytes);
            var structured = structuredParser.parse(document.type(), markdown);
            String serialized = json.writeValueAsString(structured);
            if (!documents.complete(task.id(), task.documentId(), task.ownerId(), serialized, startedAt,
                    task.workerId(), task.attemptCount())) {
                log.warn("parse_task={} document={} user={} status=STALE_RESULT_IGNORED attempt_count={}",
                        task.id(), task.documentId(), task.ownerId(), task.attemptCount());
                return true;
            }
            long durationMs = documents.findTaskByIdAndOwner(task.id(), task.ownerId())
                    .map(ParseTaskRecord::durationMs).orElse(0L);
            log.info("parse_task={} document={} user={} status=SUCCEEDED duration_ms={} retry_count={}",
                    task.id(), task.documentId(), task.ownerId(), durationMs, task.retryCount());
        } catch (DocumentParseException error) {
            failureCode = error.code();
            completeFailure(task, startedAt, failureCode, error.safeMessage());
        } catch (Exception error) {
            // Keep user content and parser diagnostics out of logs and API responses.
            log.error("parse_task={} document={} user={} failure_type={}", task.id(), task.documentId(),
                    task.ownerId(), error.getClass().getSimpleName());
            completeFailure(task, startedAt, failureCode, "解析服务暂时无法处理此文件，请检查文件后重试。");
        }
        return true;
    }

    private void completeFailure(ParseTaskRecord task, Instant startedAt, String code, String safeMessage) {
        if (!documents.fail(task.id(), task.documentId(), task.ownerId(), code, safeMessage, startedAt,
                task.workerId(), task.attemptCount())) {
            log.warn("parse_task={} document={} user={} status=STALE_FAILURE_IGNORED attempt_count={}",
                    task.id(), task.documentId(), task.ownerId(), task.attemptCount());
            return;
        }
        long durationMs = documents.findTaskByIdAndOwner(task.id(), task.ownerId())
                .map(ParseTaskRecord::durationMs).orElse(0L);
        log.warn("parse_task={} document={} user={} status=FAILED failure_code={} duration_ms={} retry_count={}",
                task.id(), task.documentId(), task.ownerId(), code, durationMs, task.retryCount());
    }
}
