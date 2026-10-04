package local.interviewmirror.backend.interviews;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import local.interviewmirror.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@Component
public class InterviewEventStream {
    private final InterviewRepository repository;
    private final ObjectMapper json;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "interview-sse-poller");
        thread.setDaemon(true);
        return thread;
    });

    public InterviewEventStream(InterviewRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    public SseEmitter open(UUID ownerId, UUID interviewId, long afterEventId) {
        repository.findOwned(ownerId, interviewId);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicLong cursor = new AtomicLong(afterEventId);
        AtomicLong lastHeartbeat = new AtomicLong(System.nanoTime());
        AtomicBoolean closed = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>> futureRef = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                ScheduledFuture<?> future = futureRef.get();
                if (future != null) future.cancel(false);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(() -> {
            if (closed.get()) return;
            try {
                var batch = repository.eventsAfter(ownerId, interviewId, cursor.get(), 100);
                for (var event : batch) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.eventId()))
                            .name(event.eventType()).data(Map.of("schemaVersion", "interviewmirror.sse-event.v1.0.0",
                                    "eventId", event.eventId(), "interviewId", interviewId.toString(),
                                    "type", event.eventType(), "timestamp", event.createdAt(), "payload", event.payload())));
                    cursor.set(event.eventId());
                }
                if (System.nanoTime() - lastHeartbeat.get() > TimeUnit.SECONDS.toNanos(15)) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    lastHeartbeat.set(System.nanoTime());
                }
                if (batch.isEmpty() && repository.findOwned(ownerId, interviewId).status() == InterviewStatus.COMPLETE) {
                    emitter.complete();
                }
            } catch (IOException | RuntimeException failure) {
                cleanup.run();
                emitter.completeWithError(failure);
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
        futureRef.set(future);
        return emitter;
    }

    @PreDestroy
    public void stop() { executor.shutdownNow(); }
}
