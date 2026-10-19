package local.interviewmirror.backend.resumes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import local.interviewmirror.backend.common.StructuredOutputInvoker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ResumeGradingService {
    public static final String RUBRIC_VERSION = "resume.technical-audit.v1";
    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);
    private static final List<String> CATEGORIES = List.of("内容", "格式", "技能", "项目");
    private static final List<String> PRIORITIES = List.of("高", "中", "低");
    private static final List<String> SUGGESTION_TYPES = List.of("ISSUE", "SUPPLEMENT");
    private final ChatClient client;
    private final StructuredOutputInvoker invoker;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String prompt;
    public ResumeGradingService(ChatModel model, StructuredOutputInvoker invoker, ObjectMapper json,
            ResumeProperties properties, @Value("${interviewmirror.model.enabled:false}") boolean enabled) throws java.io.IOException {
        client = ChatClient.builder(model).defaultOptions(OpenAiChatOptions.builder()
                .timeout(properties.modelTimeout()).maxRetries(0).temperature(0.1)).build();
        this.invoker = invoker;
        this.json = json;
        this.enabled = enabled;
        prompt = new ClassPathResource("prompts/resume-analysis-system.st").getContentAsString(StandardCharsets.UTF_8);
    }
    public Result analyze(ResumeRecord resume) {
        if (!enabled) throw new IllegalStateException("Resume model is not configured");
        ResumeEvidenceCatalog catalog = new ResumeEvidenceCatalog(resume.id(), resume.resumeText());
        String context = json.writeValueAsString(java.util.Map.of("rubricVersion", RUBRIC_VERSION,
                "evidenceCandidates", catalog.promptEntries()));
        return invoker.invoke(client, prompt, "以下证据目录按原文顺序完整覆盖简历。\n<context>\n" + context + "\n</context>",
                new BeanOutputConverter<>(Output.class), output -> assemble(output, catalog, resume.id().toString()));
    }
    static Result assemble(Output output, ResumeEvidenceCatalog catalog) {
        return assemble(output, catalog, "unknown");
    }
    private static Result assemble(Output output, ResumeEvidenceCatalog catalog, String resumeId) {
        Scores s = output.scoreDetail();
        if (s == null) throw new IllegalArgumentException("Missing scoreDetail");
        range(s.contentScore(), 15); range(s.structureScore(), 15); range(s.skillMatchScore(), 20);
        range(s.expressionScore(), 10); range(s.projectScore(), 40);
        if (output.strengths() == null || output.suggestions() == null) throw new IllegalArgumentException("Missing insights");
        var strengths = output.strengths().stream().map(item -> {
            required(item.text());
            return new Strength(item.text(), catalog.resolve(item.evidenceIds(), true));
        }).toList();
        List<Suggestion> suggestions = new ArrayList<>();
        for (int index = 0; index < output.suggestions().size(); index++) {
            RawSuggestion item = output.suggestions().get(index);
            if (item == null) throw new IllegalArgumentException("Missing suggestion at index " + index);
            required(item.issue()); required(item.recommendation());
            boolean validEnums = true;
            if (!CATEGORIES.contains(item.category())) {
                logInvalidEnum(resumeId, index, "category", item.category(), CATEGORIES);
                validEnums = false;
            }
            if (!PRIORITIES.contains(item.priority())) {
                logInvalidEnum(resumeId, index, "priority", item.priority(), PRIORITIES);
                validEnums = false;
            }
            if (!SUGGESTION_TYPES.contains(item.type())) {
                logInvalidEnum(resumeId, index, "type", item.type(), SUGGESTION_TYPES);
                validEnums = false;
            }
            if (!validEnums) continue;
            suggestions.add(new Suggestion(item.category(), item.priority(), item.type(), item.issue(), item.recommendation(),
                    catalog.resolve(item.evidenceIds(), "ISSUE".equals(item.type()))));
        }
        return new Result(s, strengths, suggestions);
    }
    private static void logInvalidEnum(String resumeId, int index, String field, String value, List<String> allowed) {
        log.warn("resume suggestion discarded resume={} index={} reason=invalid_enum field={} value={} allowed={}",
                resumeId, index + 1, field, safeLogValue(value), allowed);
    }
    private static String safeLogValue(String value) {
        if (value == null) return "<null>";
        String sanitized = value.replaceAll("[\\r\\n\\t]", " ");
        return sanitized.length() <= 80 ? sanitized : sanitized.substring(0, 80) + "…";
    }
    private static void range(Integer score, int maximum) {
        if (score == null || score < 0 || score > maximum) throw new IllegalArgumentException("Score outside rubric");
    }
    private static void required(String value) {
        if (value == null || value.isBlank() || value.length() > 10000) throw new IllegalArgumentException("Invalid insight text");
    }
    public record Output(Scores scoreDetail, List<RawStrength> strengths, List<RawSuggestion> suggestions) {}
    public record Scores(Integer contentScore, Integer structureScore, Integer skillMatchScore,
            Integer expressionScore, Integer projectScore) {
        public int total() { return contentScore + structureScore + skillMatchScore + expressionScore + projectScore; }
    }
    public record RawStrength(String text, List<String> evidenceIds) {}
    public record RawSuggestion(String category, String priority, String type, String issue,
            String recommendation, List<String> evidenceIds) {}
    public record Strength(String text, List<ResumeEvidenceCatalog.Evidence> evidence) {}
    public record Suggestion(String category, String priority, String type, String issue,
            String recommendation, List<ResumeEvidenceCatalog.Evidence> evidence) {}
    public record Result(Scores scores, List<Strength> strengths, List<Suggestion> suggestions) {}
}
