package local.interviewmirror.poc;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

@SpringBootApplication
public class ModelBenchmarkApplication {
    public static void main(String[] args) {
        SpringApplication.run(ModelBenchmarkApplication.class, args);
    }

    @Bean
    @ConditionalOnProperty(prefix = "interviewmirror.model", name = "run-enabled", havingValue = "true", matchIfMissing = true)
    CommandLineRunner benchmark(ChatModel model, JsonMapper mapper,
            @Value("${interviewmirror.model.provider}") String provider,
            @Value("${interviewmirror.model.id}") String modelId,
            @Value("${interviewmirror.model.price-input-cny-per-million}") double inputPrice,
            @Value("${interviewmirror.model.price-output-cny-per-million}") double outputPrice,
            @Value("${interviewmirror.model.cases-path}") String casesPath,
            @Value("${interviewmirror.model.output-path}") String outputPath,
            @Value("${spring.ai.openai.timeout:60s}") Duration requestTimeout,
            @Value("${interviewmirror.model.reasoning-effort:default}") String reasoningEffort) {
        return args -> {
            String normalizedEffort = normalizeReasoningEffort(reasoningEffort);
            System.out.printf("effectiveChatClientRequestTimeout=%s reasoningEffort=%s%n", requestTimeout, normalizedEffort);
            runBenchmark(validationClient(model, EvaluationWire.class, requestTimeout, normalizedEffort),
                    validationClient(model, ReportDraft.class, requestTimeout, normalizedEffort),
                    mapper, provider, modelId, inputPrice, outputPrice, requestTimeout, normalizedEffort,
                    Path.of(casesPath).toAbsolutePath().normalize(), Path.of(outputPath).toAbsolutePath().normalize());
        };
    }

    private static ChatClient validationClient(ChatModel model, Class<?> outputType, Duration requestTimeout,
            String reasoningEffort) {
        var validator = StructuredOutputValidationAdvisor.builder()
                .outputType(outputType)
                .maxRepeatAttempts(1)
                .build();
        return ChatClient.builder(model)
                .defaultOptions(requestTimeoutOptions(requestTimeout, reasoningEffort))
                .defaultAdvisors(validator)
                .build();
    }

    static OpenAiChatOptions.Builder requestTimeoutOptions(Duration requestTimeout) {
        return requestTimeoutOptions(requestTimeout, "default");
    }

    static OpenAiChatOptions.Builder requestTimeoutOptions(Duration requestTimeout, String reasoningEffort) {
        String normalized = normalizeReasoningEffort(reasoningEffort);
        var builder = OpenAiChatOptions.builder().timeout(requestTimeout);
        if (!"default".equals(normalized)) builder.reasoningEffort(normalized);
        return builder;
    }

    static String normalizeReasoningEffort(String reasoningEffort) {
        String normalized = Objects.requireNonNullElse(reasoningEffort, "default").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("default", "low", "high", "max").contains(normalized)) {
            throw new IllegalArgumentException("reasoningEffort must be default, low, high, or max");
        }
        return normalized;
    }

    private static void runBenchmark(ChatClient evaluationClient, ChatClient reportClient,
            JsonMapper mapper,
            String provider, String modelId, double inputPrice, double outputPrice,
            Duration requestTimeout, String reasoningEffort,
            Path casesFile, Path outputFile) throws Exception {
        byte[] datasetBytes = Files.readAllBytes(casesFile);
        BenchmarkDataset dataset = mapper.readValue(datasetBytes, BenchmarkDataset.class);
        List<CallResult> evalResults = new ArrayList<>();
        List<CallResult> reportResults = new ArrayList<>();
        int structuredPass = 0;
        int semanticPass = 0;

        for (BenchmarkCase item : dataset.cases()) {
            long started = System.nanoTime();
            String context = nonblank(item.context()) ? "Interview context / role requirements: " + item.context() + "\n" : "";
            String prompt = "Mode: " + item.mode() + "\nDimension: " + item.dimension() + "\n" + context
                    + "Question: " + item.question() + "\nCandidate answer: " + item.answer()
                    + "\nEvaluate only the supplied answer. Every evidence string must be an exact substring of it."
                    + (dataset.schemaVersion().startsWith("interviewmirror.followup-benchmark.")
                            ? " Decide whether this exact answer needs one follow-up. Ask only to resolve a material, answerable gap; if the answer is sufficient for the question, do not ask a redundant follow-up. When asking, return one concise, specific question grounded in the supplied answer and interview context. Do not invent facts or assume experience not stated."
                            : "")
                    + " If evidence is inadequate, use status UNASSESSED and score string UNASSESSED."
                    + " If the criterion does not apply, use status NOT_APPLICABLE and score string NOT_APPLICABLE."
                    + " For ASSESSED, score must be the string 1, 2, 3, 4, or 5."
                    + " Always return rationale, evidence array, followUpRequired boolean, and followUpQuestion string;"
                    + " use an empty string for followUpQuestion when no follow-up is required. Do not infer missing experience.";
            try {
                ResponseEntity<ChatResponse, EvaluationWire> response = evaluationClient.prompt()
                        .system("You are an interview practice evaluator. Return only the requested structured object.")
                        .user(prompt)
                        .call()
                        .responseEntity(EvaluationWire.class);
                EvaluationWire value = response.entity();
                long elapsed = elapsedMs(started);
                List<String> validationErrors = evaluationValidationErrors(item, value);
                boolean valid = validationErrors.isEmpty();
                if (value != null) structuredPass++;
                if (valid) semanticPass++;
                TokenUse use = tokenUse(response.response(), mapper);
                evalResults.add(new CallResult(item.id(), "EVALUATION", value != null, valid, validationErrors,
                        elapsed, use.input(), use.output(), use.nativeUsageType(), use.nativeUsage(), value, null));
            } catch (Exception failure) {
                evalResults.add(new CallResult(item.id(), "EVALUATION", false, false,
                        List.of("EVALUATION_CALL_FAILED"), elapsedMs(started), null, null, null, null, null,
                        failure.getClass().getSimpleName() + ": " + safeMessage(failure)));
            }

            if (!dataset.schemaVersion().startsWith("interviewmirror.followup-benchmark.")) {
                long reportStart = System.nanoTime();
                try {
                    String reportPrompt = "Create a compact, evidence-grounded practice report from this synthetic Q/A. "
                            + "Use only the answer quote provided; if uncertain, mark unassessed.\n" + prompt;
                    ResponseEntity<ChatResponse, ReportDraft> response = reportClient.prompt()
                            .system("You create structured interview practice reports, not hiring decisions.")
                            .user(reportPrompt)
                            .call()
                            .responseEntity(ReportDraft.class);
                    ReportDraft draft = response.entity();
                    List<String> validationErrors = reportValidationErrors(item, draft);
                    boolean valid = validationErrors.isEmpty();
                    TokenUse use = tokenUse(response.response(), mapper);
                    if (draft != null) structuredPass++;
                    if (valid) semanticPass++;
                    reportResults.add(new CallResult(item.id(), "REPORT", draft != null, valid, validationErrors,
                            elapsedMs(reportStart), use.input(), use.output(), use.nativeUsageType(), use.nativeUsage(), draft, null));
                } catch (Exception failure) {
                    reportResults.add(new CallResult(item.id(), "REPORT", false, false, List.of("REPORT_CALL_FAILED"),
                            elapsedMs(reportStart), null, null, null, null, null,
                            failure.getClass().getSimpleName() + ": " + safeMessage(failure)));
                }
            }
        }

        List<CallResult> all = new ArrayList<>(evalResults);
        all.addAll(reportResults);
        List<Long> reportLatencies = reportResults.stream().map(CallResult::elapsedMs).sorted().toList();
        long inTokens = all.stream().map(CallResult::inputTokens).filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        long outTokens = all.stream().map(CallResult::outputTokens).filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        double cost = (inTokens * inputPrice + outTokens * outputPrice) / 1_000_000.0;
        RunResult result = new RunResult("interviewmirror.spring-ai-run.v1.2.0", Instant.now().toString(),
                provider, modelId, dataset.schemaVersion(), dataset.promptVersion(), sha256(datasetBytes),
                System.getenv().getOrDefault("GIT_HEAD", "unknown"),
                Boolean.parseBoolean(System.getenv().getOrDefault("GIT_DIRTY", "false")), evalResults.size(),
                all.size(), structuredPass, semanticPass, percentile95(reportLatencies), inTokens, outTokens,
                inputPrice, outputPrice, cost, null, requestTimeout.toSeconds(), reasoningEffort, all);
        Files.createDirectories(outputFile.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), result);
        System.out.printf("provider=%s model=%s structured=%d/%d semantic=%d/%d reportP95Ms=%d inputTokens=%d outputTokens=%d costCny=%.6f result=%s%n",
                provider, modelId, structuredPass, all.size(), semanticPass, all.size(), result.reportP95Ms(), inTokens, outTokens, cost, outputFile);
    }

    private static TokenUse tokenUse(ChatResponse response, JsonMapper mapper) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return new TokenUse(null, null, null, null);
        }
        var usage = response.getMetadata().getUsage();
        Object nativeUsage = usage.getNativeUsage();
        JsonNode nativeUsageNode = null;
        if (nativeUsage != null) {
            try {
                nativeUsageNode = mapper.valueToTree(nativeUsage);
            } catch (Exception ignored) {
                // Preserve the benchmark result even if a provider SDK usage object cannot be serialized.
            }
        }
        return new TokenUse(readLong(usage, "getPromptTokens"), readLong(usage, "getCompletionTokens"),
                nativeUsage == null ? null : nativeUsage.getClass().getName(), nativeUsageNode);
    }

    private static Long readLong(Object target, String methodName) {
        try {
            Object result = target.getClass().getMethod(methodName).invoke(target);
            return result instanceof Number number ? number.longValue() : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static long percentile95(List<Long> sorted) {
        if (sorted.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(sorted.size() * .95) - 1);
        return sorted.get(index);
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    static Evaluation normalizeEvaluation(EvaluationWire wire) {
        Integer score = null;
        if (wire != null && "ASSESSED".equals(wire.status()) && wire.score() != null
                && wire.score().matches("[1-5]")) {
            score = Integer.valueOf(wire.score());
        }
        return wire == null ? null : new Evaluation(wire.status(), score, wire.rationale(), wire.evidence(),
                wire.followUpRequired(), wire.followUpQuestion());
    }

    static List<String> evaluationValidationErrors(BenchmarkCase item, EvaluationWire value) {
        List<String> errors = new ArrayList<>();
        if (value == null) return List.of("EVALUATION_NULL_ENTITY");
        String status = Objects.requireNonNullElse(value.status(), "");
        if (!"ASSESSED".equals(status) && !"UNASSESSED".equals(status) && !"NOT_APPLICABLE".equals(status)) {
            errors.add("EVALUATION_STATUS_INVALID");
        }
        boolean scoreMatchesStatus = switch (status) {
            case "ASSESSED" -> value.score() != null && value.score().matches("[1-5]");
            case "UNASSESSED" -> "UNASSESSED".equals(value.score());
            case "NOT_APPLICABLE" -> "NOT_APPLICABLE".equals(value.score());
            default -> false;
        };
        if (!scoreMatchesStatus) errors.add("EVALUATION_SCORE_STATUS_MISMATCH");
        if (!nonblank(value.rationale())) errors.add("EVALUATION_RATIONALE_MISSING");
        if (value.evidence() == null) {
            errors.add("EVALUATION_EVIDENCE_MISSING");
        } else {
            if ("ASSESSED".equals(value.status()) && value.evidence().isEmpty()) {
                errors.add("EVALUATION_ASSESSED_WITHOUT_EVIDENCE");
            }
            if (value.evidence().stream().anyMatch(e -> !nonblank(e))) {
                errors.add("EVALUATION_EVIDENCE_BLANK");
            }
            if (value.evidence().stream().filter(Objects::nonNull).anyMatch(e -> !item.answer().contains(e))) {
                errors.add("EVALUATION_EVIDENCE_NOT_IN_ANSWER");
            }
        }
        if (value.followUpRequired() && !nonblank(value.followUpQuestion())) {
            errors.add("EVALUATION_FOLLOW_UP_MISSING");
        }
        return List.copyOf(errors);
    }

    static List<String> reportValidationErrors(BenchmarkCase item, ReportDraft draft) {
        List<String> errors = new ArrayList<>();
        if (draft == null) return List.of("REPORT_NULL_ENTITY");
        if (!nonblank(draft.overallReview())) errors.add("REPORT_OVERALL_REVIEW_MISSING");
        if (draft.strengths() == null) errors.add("REPORT_STRENGTHS_MISSING");
        if (draft.risks() == null) errors.add("REPORT_RISKS_MISSING");
        if (draft.recommendations() == null) errors.add("REPORT_RECOMMENDATIONS_MISSING");
        if (draft.learningPath() == null) errors.add("REPORT_LEARNING_PATH_MISSING");
        if (draft.evidence() == null) {
            errors.add("REPORT_EVIDENCE_MISSING");
        } else {
            if (Boolean.TRUE.equals(item.shouldAssess()) && draft.evidence().isEmpty()) errors.add("REPORT_EVIDENCE_EMPTY");
            if (draft.evidence().stream().anyMatch(e -> !nonblank(e))) errors.add("REPORT_EVIDENCE_BLANK");
            if (draft.evidence().stream().filter(Objects::nonNull).anyMatch(e -> !item.answer().contains(e))) {
                errors.add("REPORT_EVIDENCE_NOT_IN_ANSWER");
            }
        }
        return List.copyOf(errors);
    }

    static String safeMessage(Exception failure) {
        StringBuilder summary = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 4) {
            if (summary.length() > 0) summary.append(" <- ");
            summary.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            boolean safeIoCause = current instanceof IOException
                    || current instanceof java.util.concurrent.TimeoutException;
            if (message != null && (depth == 0 || safeIoCause)) {
                String scrubbed = redactSensitiveText(message);
                summary.append(": ").append(scrubbed, 0, Math.min(180, scrubbed.length()));
            }
            current = current.getCause();
            depth++;
        }
        return summary.substring(0, Math.min(500, summary.length()));
    }

    private static String redactSensitiveText(String value) {
        return value
                .replaceAll("(?i)(authorization\\s*[:=]\\s*bearer\\s+|bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[_ -]?key\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)\\bsk-[a-z0-9._-]{8,}", "[REDACTED]")
                .replaceAll("(?i)\\b[0-9a-f]{32}\\.[a-z0-9._-]{8,}", "[REDACTED]");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public record BenchmarkDataset(String schemaVersion, String promptVersion, List<BenchmarkCase> cases) {}
    public record BenchmarkCase(String id, String mode, String dimension, String question, String answer,
            List<String> expectedSignals, Boolean shouldAssess, String context, String expectedAction,
            String probeObjective) {}
    public record EvaluationWire(String status, String score, String rationale, List<String> evidence,
            boolean followUpRequired, String followUpQuestion) {}
    public record Evaluation(String status, Integer score, String rationale, List<String> evidence,
            boolean followUpRequired, String followUpQuestion) {}
    public record ReportDraft(String overallReview, List<String> strengths, List<String> risks,
            List<String> recommendations, List<String> learningPath, List<String> evidence) {}
    public record CallResult(String caseId, String kind, boolean structured, boolean semanticValid,
            List<String> validationErrors, long elapsedMs, Long inputTokens, Long outputTokens,
            String providerNativeUsageType, JsonNode providerNativeUsage, Object structuredOutput, String error) {}
    public record RunResult(String schemaVersion, String createdAt, String provider, String modelId,
            String datasetVersion, String promptVersion, String datasetSha256, String gitHead, boolean gitDirty,
            int evaluationCases, int totalStructuredAttempts, int structuredSuccesses, int semanticSuccesses,
            long reportP95Ms, long inputTokens, long outputTokens, double inputPriceCnyPerMillion,
            double outputPriceCnyPerMillion, double measuredCostCny, Double humanQualityMean,
            long requestTimeoutSeconds, String reasoningEffort,
            List<CallResult> calls) {}
    public record TokenUse(Long input, Long output, String nativeUsageType, JsonNode nativeUsage) {}
}
