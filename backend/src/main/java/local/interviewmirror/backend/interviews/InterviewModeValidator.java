package local.interviewmirror.backend.interviews;

import local.interviewmirror.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class InterviewModeValidator {
    public static final String SCHEMA_VERSION = "1.1.0";
    public static final int MAIN_QUESTION_TARGET = 6;

    public void validate(InterviewRequests.Create request) {
        if (request == null || request.mode() == null) throw invalid("面试模式不能为空。");
        if (!SCHEMA_VERSION.equals(request.schemaVersion())) throw invalid("面试请求结构版本不受支持，请刷新页面重试。");
        if (!"zh-CN".equals(request.locale())) throw invalid("当前仅支持 zh-CN 面试语言。");
        if (!Boolean.TRUE.equals(request.modelDataConsent())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MODEL_DATA_CONSENT_REQUIRED",
                    "开始面试前需要确认资料与回答将发送至已配置的模型服务进行处理。");
        }
        if (request.jdText() != null && request.jdText().length() > 1500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "JD_TOO_LONG", "JD 请控制在 1500 字以内。");
        }
        boolean hasJd = request.jdText() != null && !request.jdText().isBlank();
        if (request.mode() == InterviewMode.COMPREHENSIVE) {
            if (request.resumeId() == null) throw invalid("综合面试必须选择一份已确认简历。");
            if (request.questionBankId() != null) throw conflict("综合面试不能同时指定题库。");
            if (request.jdTextPresent() && request.jdText() == null) {
                throw invalid("jdText 存在时必须是字符串；不提供 JD 时请省略该字段。");
            }
        } else {
            if (request.questionBankId() == null) throw invalid("专项面试必须选择一份已确认题库。");
            if (request.resumeId() != null || request.jdTextPresent()) throw conflict("专项面试不关联简历或 JD。");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INTERVIEW_MODE_INPUT_INVALID", message);
    }

    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "MODE_INPUT_CONFLICT", message);
    }
}
