package local.interviewmirror.backend.resumes;

import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import local.interviewmirror.backend.files.ObjectStorage;
import local.interviewmirror.backend.interviews.InterviewGraphRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

class ResumeWorkerTest {
    @Test void outboxFailuresKeepTheirBackoffAndDoNotRecreateRedisGroup() {
        var properties = ResumeParserTest.properties(100000, Duration.ofMinutes(2));
        var repository = mock(ResumeRepository.class);
        when(repository.outbox()).thenThrow(new IllegalStateException("database unavailable"));
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        var worker = new ResumeWorker(repository, mock(ResumeService.class), mock(ResumeParser.class),
                mock(ResumeGradingService.class), mock(ObjectStorage.class), mock(InterviewGraphRuntime.class), redis, properties);
        try {
            worker.poll();
            worker.poll();
            verify(repository, times(2)).outbox();
            verify(redis, times(1)).execute(any(DefaultRedisScript.class), eq(java.util.List.of(properties.stream())), eq(properties.group()));
        } finally { worker.close(); }
    }

    @Test void legacyTextRefreshCannotBorrowARecoveredConsumersAttempt() throws Exception {
        var properties = ResumeParserTest.properties(100000, Duration.ofMinutes(2));
        var repository = mock(ResumeRepository.class);
        var service = mock(ResumeService.class);
        var parser = mock(ResumeParser.class);
        var grading = mock(ResumeGradingService.class);
        var storage = mock(ObjectStorage.class);
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        var original = row(id, user, UUID.randomUUID(), "");
        var recovered = row(id, user, UUID.randomUUID(), "recovered canonical text");
        when(repository.claim(id, 0)).thenReturn(Optional.of(original));
        when(storage.get("key")).thenReturn(new ByteArrayInputStream("old file".getBytes()));
        when(parser.parse(any(), anyString(), anyString())).thenReturn("extracted");
        when(service.structured("extracted")).thenReturn("{}");
        when(repository.backfill(original, "extracted", "{}")).thenReturn(true);
        when(repository.find(id)).thenReturn(Optional.of(recovered));
        var worker = new ResumeWorker(repository, service, parser, grading, storage, mock(InterviewGraphRuntime.class), redis, properties);
        try {
            MapRecord<String, Object, Object> message = MapRecord.create(properties.stream(), Map.of("resumeId", id.toString(), "retryCount", "0"));
            worker.consume(message);
            verifyNoInteractions(grading);
            verify(repository, never()).complete(any(), any());
            verify(repository, never()).fail(any());
        } finally { worker.close(); }
    }
    private static ResumeRecord row(UUID id, UUID user, UUID attempt, String text) {
        return new ResumeRecord(id, user, "hash", "Resume", "resume.txt", 8, "text/plain", "key", text, "{}",
                Instant.now(), 1, "PROCESSING", null, Instant.now(), 0, 0, attempt, "ACTIVE");
    }
}
