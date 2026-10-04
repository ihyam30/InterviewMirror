package local.interviewmirror.backend.documents;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.StreamSupport;
import local.interviewmirror.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class DocumentContentRules {
    public void validateShape(DocumentType type, JsonNode content) {
        if (content == null || !content.isObject()) throw invalid("解析内容必须是 JSON 对象。");
        String expectedSchema = type == DocumentType.RESUME
                ? "interviewmirror.resume-content.v1" : "interviewmirror.question-bank-content.v1";
        if (!expectedSchema.equals(content.path("schemaVersion").asString(""))) {
            throw invalid("资料结构版本不匹配，请重新加载后再保存。");
        }
        if (type == DocumentType.RESUME) {
            if (!content.path("personalInfo").isObject()) throw invalid("简历个人信息结构无效。");
            for (String field : new String[]{"education", "experiences", "projects", "skills", "awards", "metrics"}) {
                if (!content.path(field).isArray()) throw invalid("简历字段结构无效：" + field);
            }
        } else {
            JsonNode questions = content.path("questions");
            if (!questions.isArray()) throw invalid("题库问题列表结构无效。");
            Set<Integer> positions = new HashSet<>();
            Set<String> normalizedStems = new HashSet<>();
            for (JsonNode question : questions) {
                String stem = question.path("stem").asString("").trim();
                if (!question.isObject() || stem.isBlank() || stem.length() > 1000) {
                    throw invalid("每道题都需要填写题目，且题目不能超过 1000 字。");
                }
                if (!normalizedStems.add(normalizeQuestionStem(stem))) {
                    throw invalid("题库中不能包含重复题目，请检查题干后再保存。");
                }
                if (question.path("answer").asString("").length() > 4000) throw invalid("参考答案不能超过 4000 字。");
                int position = question.path("position").asInt(-1);
                if (position < 1 || !positions.add(position)) throw invalid("题目顺序无效。");
            }
        }
    }

    public void validateConfirm(DocumentType type, JsonNode content, String title) {
        validateShape(type, content);
        if (title == null || title.isBlank()) throw invalid("请先填写资料名称。");
        if (type == DocumentType.QUESTION_BANK && content.path("questions").isEmpty()) {
            throw invalid("题库至少需要保留一道问题。");
        }
        if (type == DocumentType.RESUME) {
            JsonNode personal = content.path("personalInfo");
            boolean hasName = !personal.path("name").asString("").isBlank();
            boolean hasOtherContent = !content.path("education").isEmpty() || !content.path("experiences").isEmpty()
                    || !content.path("projects").isEmpty() || !content.path("skills").isEmpty();
            if (!hasName && !hasOtherContent) throw invalid("请至少补充姓名、教育、经历、项目或技能中的一项。");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_CONTENT", message);
    }

    public static String normalizeQuestionStem(String stem) {
        return stem == null ? "" : stem.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？、,.!?；;：:]", "");
    }

    public static long countDistinctQuestionStems(JsonNode questions) {
        if (questions == null || !questions.isArray()) return 0;
        return StreamSupport.stream(questions.spliterator(), false)
                .map(item -> item.path("stem").asString("").trim())
                .filter(stem -> !stem.isBlank())
                .map(DocumentContentRules::normalizeQuestionStem)
                .distinct().count();
    }
}
