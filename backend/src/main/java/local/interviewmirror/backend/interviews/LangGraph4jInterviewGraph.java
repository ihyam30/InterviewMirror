package local.interviewmirror.backend.interviews;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.AgentState;

public class LangGraph4jInterviewGraph implements InterviewGraphRuntime {
    private final CompiledGraph<AgentState> graph;

    public LangGraph4jInterviewGraph(CompiledGraph<AgentState> graph) { this.graph = graph; }

    @Override
    public GraphSnapshot begin(UUID interviewId, UUID ownerId, InterviewMode mode,
            UUID resumeId, UUID questionBankId, List<InterviewModel.PlannedQuestion> plan) throws Exception {
        List<Map<String, Object>> questions = plan.stream().map(this::questionMap).toList();
        Map<String, Object> input = new HashMap<>();
        input.put("interviewId", interviewId.toString());
        input.put("userId", ownerId.toString());
        input.put("mode", mode.name());
        input.put("resumeId", resumeId == null ? "" : resumeId.toString());
        input.put("questionBankId", questionBankId == null ? "" : questionBankId.toString());
        input.put("usedQuestionIds", plan.stream().map(InterviewModel.PlannedQuestion::sourceQuestionId)
                .filter(java.util.Objects::nonNull).filter(value -> !value.isBlank()).distinct().toList());
        input.put("schemaVersion", InterviewModeValidator.SCHEMA_VERSION);
        input.put("questionPlan", questions);
        input.put("mainQuestionTarget", InterviewModeValidator.MAIN_QUESTION_TARGET);
        input.put("mainQuestionIndex", 0);
        input.put("followupCount", 0);
        input.put("replaceCount", 0);
        graph.invoke(GraphInput.args(input), config(interviewId));
        return snapshot(interviewId);
    }

    @Override
    public GraphSnapshot answer(UUID interviewId, UUID turnId, String answer) throws Exception {
        Map<String, Object> input = Map.of("action", "ANSWER", "answer", answer,
                "answeredTurnId", turnId.toString(), "lastAnswer", answer);
        graph.invoke(GraphInput.resume(input), config(interviewId));
        return snapshot(interviewId);
    }

    @Override
    public GraphSnapshot replace(UUID interviewId, UUID turnId, InterviewModel.PlannedQuestion replacement,
            List<InterviewModel.PlannedQuestion> replacementPlan) throws Exception {
        Map<String, Object> input = Map.of("action", "REPLACE", "replacedTurnId", turnId.toString(),
                "replacementQuestion", questionMap(replacement), "replacementPlan",
                replacementPlan.stream().map(this::questionMap).toList());
        graph.invoke(GraphInput.resume(input), config(interviewId));
        return snapshot(interviewId);
    }

    @Override
    public GraphSnapshot end(UUID interviewId) throws Exception {
        graph.invoke(GraphInput.resume(Map.of("action", "END", "completionReason", "USER_ENDED")), config(interviewId));
        return snapshot(interviewId);
    }

    @Override
    public GraphSnapshot snapshot(UUID interviewId) {
        var state = graph.lastStateOf(config(interviewId)).orElseThrow(
                () -> new IllegalStateException("INTERVIEW_GRAPH_CHECKPOINT_NOT_FOUND")).state();
        return new GraphSnapshot(value(state, "phase", ""), value(state, "completionReason", ""),
                value(state, "mainQuestionIndex", 0), value(state, "followupCount", 0),
                value(state, "replaceCount", 0), value(state, "currentTurnType", "MAIN"),
                value(state, "currentQuestionId", ""), value(state, "sourceQuestionId", ""),
                value(state, "currentQuestion", ""), value(state, "currentCategory", ""),
                value(state, "lastAnsweredTurnId", ""), value(state, "lastAnswer", ""),
                Boolean.toString(value(state, "fallbackUsed", false)),
                value(state, "questionPlan", List.<Map<String, Object>>of()));
    }

    public List<String> checkpointTrace(UUID interviewId) {
        return graph.getStateHistory(config(interviewId)).stream()
                .map(snapshot -> snapshot.node()).toList();
    }

    private static RunnableConfig config(UUID interviewId) {
        return RunnableConfig.builder().threadId(interviewId.toString()).recursionLimit(80).build();
    }

    private Map<String, Object> questionMap(InterviewModel.PlannedQuestion question) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", question.id());
        map.put("stem", question.stem());
        map.put("category", question.category() == null ? "" : question.category());
        map.put("rationale", question.rationale() == null ? "" : question.rationale());
        map.put("sourceQuestionId", question.sourceQuestionId() == null ? "" : question.sourceQuestionId());
        return map;
    }

    private static <T> T value(AgentState state, String key, T fallback) {
        return state.<T>value(key).orElse(fallback);
    }
}
