package local.interviewmirror.backend.common;

import java.util.Map;

public record ApiErrorResponse(ErrorBody error) {
    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(new ErrorBody(code, message, Map.of()));
    }

    public static ApiErrorResponse of(String code, String message, Map<String, String> details) {
        return new ApiErrorResponse(new ErrorBody(code, message, details));
    }

    public record ErrorBody(String code, String message, Map<String, String> details) {}
}
