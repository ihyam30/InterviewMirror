package local.interviewmirror.backend.interviews;

import java.util.List;
import java.util.UUID;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.common.ApiResponse;
import local.interviewmirror.backend.security.AccountPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/interviews")
public class InterviewController {
    private static final java.util.Set<String> CREATE_FIELDS = java.util.Set.of(
            "schemaVersion", "clientRequestId", "mode", "resumeId", "questionBankId",
            "jdText", "locale", "modelDataConsent");
    private final InterviewService interviews;
    private final InterviewEventStream events;
    private final Validator validator;

    public InterviewController(InterviewService interviews, InterviewEventStream events, Validator validator) {
        this.interviews = interviews;
        this.events = events;
        this.validator = validator;
    }

    @PostMapping
    ApiResponse<InterviewViews.Interview> create(Authentication auth, @RequestBody JsonNode body) {
        InterviewRequests.Create request = createRequest(body);
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            ConstraintViolation<InterviewRequests.Create> first = violations.iterator().next();
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", first.getMessage());
        }
        return ApiResponse.of(interviews.create(userId(auth), request));
    }

    @GetMapping
    ApiResponse<List<InterviewViews.Interview>> list(Authentication auth) {
        return ApiResponse.of(interviews.list(userId(auth)));
    }

    @GetMapping("/{id}")
    ApiResponse<InterviewViews.Interview> get(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(interviews.get(userId(auth), id));
    }

    @PostMapping("/{id}/start")
    ApiResponse<InterviewViews.Interview> start(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(interviews.start(userId(auth), id));
    }

    @GetMapping("/{id}/turns")
    ApiResponse<InterviewViews.TurnList> turns(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(interviews.turns(userId(auth), id));
    }

    @PostMapping("/{id}/answers")
    ApiResponse<InterviewViews.Interview> answer(Authentication auth, @PathVariable UUID id,
            @Valid @RequestBody InterviewRequests.Answer body) {
        return ApiResponse.of(interviews.answer(userId(auth), id, body));
    }

    @PostMapping("/{id}/replace-question")
    ApiResponse<InterviewViews.Interview> replace(Authentication auth, @PathVariable UUID id,
            @Valid @RequestBody InterviewRequests.Replace body) {
        return ApiResponse.of(interviews.replaceQuestion(userId(auth), id, body));
    }

    @PostMapping("/{id}/end")
    ApiResponse<InterviewViews.Interview> end(Authentication auth, @PathVariable UUID id,
            @Valid @RequestBody InterviewRequests.End body) {
        return ApiResponse.of(interviews.end(userId(auth), id, body));
    }

    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(Authentication auth, @PathVariable UUID id,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        long cursor = 0L;
        try { if (lastEventId != null && !lastEventId.isBlank()) cursor = Math.max(0L, Long.parseLong(lastEventId)); }
        catch (NumberFormatException ignored) { cursor = 0L; }
        return events.open(userId(auth), id, cursor);
    }

    private static UUID userId(Authentication auth) {
        return ((AccountPrincipal) auth.getPrincipal()).id();
    }

    private static InterviewRequests.Create createRequest(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", "面试创建请求必须是 JSON 对象。");
        }
        long knownFieldCount = CREATE_FIELDS.stream().filter(body::has).count();
        if (body.size() != knownFieldCount) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                    "面试创建请求包含当前版本不支持的字段。");
        }
        String modeText = optionalText(body, "mode");
        InterviewMode mode = null;
        if (modeText != null) {
            try { mode = InterviewMode.valueOf(modeText); }
            catch (IllegalArgumentException invalidMode) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INTERVIEW_MODE_INPUT_INVALID", "面试模式不受支持。");
            }
        }
        String jdText = optionalText(body, "jdText");
        return new InterviewRequests.Create(optionalText(body, "schemaVersion"), optionalText(body, "clientRequestId"),
                mode, optionalUuid(body, "resumeId"), optionalUuid(body, "questionBankId"), jdText,
                body.has("jdText"), optionalText(body, "locale"), optionalBoolean(body, "modelDataConsent"));
    }

    private static String optionalText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_BODY", field + " 必须是字符串或 null。");
        return value.asString();
    }

    private static UUID optionalUuid(JsonNode body, String field) {
        String value = optionalText(body, field);
        if (value == null) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalidUuid) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", field + " 必须是 UUID。");
        }
    }

    private static Boolean optionalBoolean(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isBoolean()) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_BODY", field + " 必须是布尔值。");
        return value.booleanValue();
    }
}
