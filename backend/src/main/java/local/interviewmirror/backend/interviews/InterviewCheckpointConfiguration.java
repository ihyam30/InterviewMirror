package local.interviewmirror.backend.interviews;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "interviewmirror.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InterviewCheckpointConfiguration {

    @Bean
    PostgresSaver interviewPostgresSaver(DataSource dataSource) throws SQLException {
        return PostgresSaver.builder().datasource(dataSource)
                .stateSerializer(new ObjectStreamStateSerializer<>(AgentState::new))
                .createTables(true).build();
    }

    @Bean
    CompiledGraph<AgentState> interviewGraph(PostgresSaver saver, InterviewModel model) throws Exception {
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
        graph.addNode("prepare", AsyncNodeAction.node_async(state -> {
            var plan = value(state, "questionPlan", java.util.List.<java.util.Map<String, Object>>of());
            int target = value(state, "mainQuestionTarget", InterviewModeValidator.MAIN_QUESTION_TARGET);
            if (plan.size() < target || plan.size() > 8 || target < 5 || target > 8
                    || !InterviewModeValidator.SCHEMA_VERSION.equals(value(state, "schemaVersion", ""))) {
                throw new IllegalStateException("INTERVIEW_GRAPH_INVALID_PLAN");
            }
            return java.util.Map.of("mainQuestionIndex", 0, "followupCount", 0,
                    "replaceCount", 0, "phase", "RUNNING");
        }));
        graph.addNode("select_main", AsyncNodeAction.node_async(state -> {
            int index = value(state, "mainQuestionIndex", 0);
            int target = value(state, "mainQuestionTarget", InterviewModeValidator.MAIN_QUESTION_TARGET);
            var plan = value(state, "questionPlan", java.util.List.<java.util.Map<String, Object>>of());
            if (index >= target) return java.util.Map.of("phase", "COMPLETE", "completionReason", "QUESTION_LIMIT");
            var question = plan.get(index);
            return java.util.Map.of("currentQuestion", question.get("stem"),
                    "currentCategory", question.getOrDefault("category", ""),
                    "currentQuestionId", question.getOrDefault("id", ""),
                    "sourceQuestionId", question.getOrDefault("sourceQuestionId", ""),
                    "currentTurnType", "MAIN", "followupCount", 0, "replaceCount", 0,
                    "phase", "WAITING_FOR_ANSWER");
        }));
        graph.addNode("ask", AsyncNodeAction.node_async(state -> java.util.Map.of("questionEmitted", true)));
        graph.addNode("wait_for_answer", AsyncNodeAction.node_async(state -> java.util.Map.of("awaitingAnswer", true)));
        graph.addNode("evaluate_answer", AsyncNodeAction.node_async(state -> {
            String answer = value(state, "answer", "");
            String currentQuestion = value(state, "currentQuestion", "");
            int count = value(state, "followupCount", 0);
            int mainIndex = value(state, "mainQuestionIndex", 0);
            var plan = value(state, "questionPlan", java.util.List.<java.util.Map<String, Object>>of());
            String mainQuestion = mainIndex >= 0 && mainIndex < plan.size()
                    ? String.valueOf(plan.get(mainIndex).getOrDefault("stem", currentQuestion)) : currentQuestion;
            if (count >= 2) return java.util.Map.of("needsFollowup", false,
                    "followupQuestion", "", "lastAnsweredTurnId", value(state, "answeredTurnId", ""),
                    "fallbackUsed", false, "transitionReason", "FOLLOWUP_LIMIT_REACHED");
            try {
                var decision = model.evaluateAnswer(mainQuestion, currentQuestion, answer);
                boolean allowed = decision.shouldFollowUp() && count < 2;
                return java.util.Map.of("needsFollowup", allowed,
                        "followupQuestion", allowed ? decision.question() : "",
                        "lastAnsweredTurnId", value(state, "answeredTurnId", ""),
                        "fallbackUsed", false,
                        "transitionReason", allowed ? decision.rationale() : "ANSWER_COMPLETE");
            } catch (RuntimeException failure) {
                return java.util.Map.of("needsFollowup", false, "followupQuestion", "",
                        "lastAnsweredTurnId", value(state, "answeredTurnId", ""),
                        "fallbackUsed", true, "transitionReason", "FOLLOWUP_GENERATION_FAILED");
            }
        }));
        graph.addNode("follow_up", AsyncNodeAction.node_async(state -> {
            int count = value(state, "followupCount", 0) + 1;
            if (count > 2) throw new IllegalStateException("INTERVIEW_FOLLOWUP_LIMIT_EXCEEDED");
            return java.util.Map.of("currentQuestion", value(state, "followupQuestion", ""),
                    "currentQuestionId", java.util.UUID.randomUUID().toString(),
                    "sourceQuestionId", "", "currentTurnType", "FOLLOW_UP",
                    "followupCount", count, "currentFollowupIndex", count,
                    "phase", "WAITING_FOR_ANSWER", "action", "");
        }));
        graph.addNode("replace_question", AsyncNodeAction.node_async(state -> {
            var replacement = value(state, "replacementQuestion", java.util.Map.<String, Object>of());
            var updatedPlan = value(state, "replacementPlan", value(state, "questionPlan", java.util.List.<java.util.Map<String, Object>>of()));
            String stem = String.valueOf(replacement.getOrDefault("stem", ""));
            int replaceCount = value(state, "replaceCount", 0) + 1;
            if (stem.isBlank() || replaceCount > 3) throw new IllegalStateException("INTERVIEW_REPLACE_LIMIT_OR_INPUT_INVALID");
            return java.util.Map.of("currentQuestion", stem,
                    "currentCategory", replacement.getOrDefault("category", ""),
                    "currentQuestionId", replacement.getOrDefault("id", java.util.UUID.randomUUID().toString()),
                    "sourceQuestionId", replacement.getOrDefault("sourceQuestionId", ""),
                    "currentTurnType", "MAIN", "followupCount", 0,
                    "replaceCount", replaceCount, "phase", "WAITING_FOR_ANSWER", "action", "",
                    "questionPlan", updatedPlan);
        }));
        graph.addNode("next_main", AsyncNodeAction.node_async(state -> java.util.Map.of(
                "mainQuestionIndex", value(state, "mainQuestionIndex", 0) + 1,
                "followupCount", 0, "phase", "PREPARING_NEXT")));
        graph.addNode("complete", AsyncNodeAction.node_async(state -> java.util.Map.of(
                "phase", "COMPLETE",
                "completionReason", value(state, "completionReason", "QUESTION_LIMIT"),
                "awaitingAnswer", false)));

        graph.addEdge(StateGraph.START, "prepare");
        graph.addEdge("prepare", "select_main");
        graph.addEdge("select_main", "ask");
        graph.addEdge("ask", "wait_for_answer");
        graph.addConditionalEdges("wait_for_answer", AsyncEdgeAction.edge_async(state -> {
            String action = value(state, "action", "ANSWER");
            return switch (action) {
                case "REPLACE" -> "replace";
                case "END" -> "end";
                default -> "answer";
            };
        }), java.util.Map.of("replace", "replace_question", "end", "complete", "answer", "evaluate_answer"));
        graph.addConditionalEdges("evaluate_answer", AsyncEdgeAction.edge_async(state ->
                value(state, "needsFollowup", false) && value(state, "followupCount", 0) < 2 ? "followup" : "next"),
                java.util.Map.of("followup", "follow_up", "next", "next_main"));
        graph.addEdge("follow_up", "ask");
        graph.addConditionalEdges("next_main", AsyncEdgeAction.edge_async(state -> {
            int index = value(state, "mainQuestionIndex", 0);
            int target = value(state, "mainQuestionTarget", InterviewModeValidator.MAIN_QUESTION_TARGET);
            return index >= target ? "complete" : "continue";
        }), java.util.Map.of("complete", "complete", "continue", "select_main"));
        graph.addEdge("replace_question", "ask");
        graph.addEdge("complete", StateGraph.END);
        return graph.compile(CompileConfig.builder().checkpointSaver(saver)
                .interruptBefore("wait_for_answer").recursionLimit(80).releaseThread(false).build());
    }

    @Bean
    InterviewGraphRuntime interviewGraphRuntime(CompiledGraph<AgentState> interviewGraph) {
        return new LangGraph4jInterviewGraph(interviewGraph);
    }

    private static <T> T value(AgentState state, String key, T fallback) {
        return state.<T>value(key).orElse(fallback);
    }
}
