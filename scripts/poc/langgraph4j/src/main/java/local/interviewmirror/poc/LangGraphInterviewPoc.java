package local.interviewmirror.poc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.sql.SQLException;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;

/** Small deterministic graph. It uses synthetic inputs only and never calls a model. */
public final class LangGraphInterviewPoc {

    private static final int MAX_GRAPH_STEPS = 80;

    private static final String DB_HOST = env("LANGGRAPH_POC_DB_HOST", "127.0.0.1");
    private static final int DB_PORT = Integer.parseInt(env("LANGGRAPH_POC_DB_PORT", "55432"));
    private static final String DB_NAME = env("LANGGRAPH_POC_DB_NAME", "interviewmirror_poc");
    private static final String DB_USER = env("LANGGRAPH_POC_DB_USER", "poc");
    private static final String DB_PASSWORD = env("LANGGRAPH_POC_DB_PASSWORD", "poc-only");

    private LangGraphInterviewPoc() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("usage: routing | crash <thread-id> | resume <thread-id>");
        switch (args[0]) {
            case "routing" -> routingScenarios();
            case "crash" -> crash(args);
            case "resume" -> resume(args);
            default -> throw new IllegalArgumentException("unknown command: " + args[0]);
        }
    }

    private static PostgresSaver newSaver() throws SQLException {
        return PostgresSaver.builder()
                .host(DB_HOST).port(DB_PORT).user(DB_USER).password(DB_PASSWORD).database(DB_NAME)
                .stateSerializer(new ObjectStreamStateSerializer<>(AgentState::new))
                .createTables(true).build();
    }

    private static CompiledGraph<AgentState> newGraph(PostgresSaver saver, AtomicBoolean crashAtFirstEvaluation)
            throws Exception {
        var graph = new StateGraph<>(AgentState::new);
        graph.addNode("prepare", AsyncNodeAction.node_async(state -> {
            String mode = value(state, "mode", "");
            boolean hasResume = value(state, "resumeConfirmed", false);
            boolean hasBank = value(state, "questionBankConfirmed", false);
            boolean hasJd = value(state, "jdProvided", false);
            boolean valid = ModeRules.isValid(mode, hasResume, hasBank, hasJd);
            return append(state, "prepare", Map.of("valid", valid, "stepCount", 1));
        }));
        graph.addNode("reject", AsyncNodeAction.node_async(state -> append(state, "reject", Map.of("terminalReason", "MODE_INPUT_INVALID"))));
        graph.addNode("select_question", AsyncNodeAction.node_async(state -> {
            List<String> questions = value(state, "questions", List.of());
            int index = value(state, "mainQuestionIndex", 0);
            if (index >= questions.size()) return append(state, "select_question", Map.of("sessionDone", true));
            return append(state, "select_question", Map.of("sessionDone", false,
                    "currentQuestion", questions.get(index), "followUpCount", 0));
        }));
        graph.addNode("ask", AsyncNodeAction.node_async(state -> append(state, "ask", Map.of("questionAsked", true))));
        graph.addNode("evaluate", AsyncNodeAction.node_async(state -> {
            if (crashAtFirstEvaluation.getAndSet(false)) {
                throw new IllegalStateException("POC simulated process stop before evaluator; resume from last committed checkpoint");
            }
            int index = value(state, "mainQuestionIndex", 0);
            int followUps = value(state, "followUpCount", 0);
            boolean askFollowUp = index == 0 && followUps == 0;
            int nextMainIndex = askFollowUp ? index : index + 1;
            return append(state, "evaluate", Map.of("needsFollowUp", askFollowUp,
                    "mainQuestionIndex", nextMainIndex, "evaluationSaved", true));
        }));
        graph.addNode("follow_up", AsyncNodeAction.node_async(state -> append(state, "follow_up", Map.of(
                "followUpCount", value(state, "followUpCount", 0) + 1,
                "currentQuestion", "请给出一个具体结果和你做过的取舍。"))));
        graph.addNode("report", AsyncNodeAction.node_async(state -> append(state, "report", Map.of("reportReady", true))));

        graph.addEdge(StateGraph.START, "prepare");
        graph.addConditionalEdges("prepare", AsyncEdgeAction.edge_async(state -> value(state, "valid", false) ? "valid" : "invalid"),
                Map.of("valid", "select_question", "invalid", "reject"));
        graph.addConditionalEdges("select_question", AsyncEdgeAction.edge_async(state -> value(state, "sessionDone", false) ? "done" : "ask"),
                Map.of("done", "report", "ask", "ask"));
        graph.addEdge("ask", "evaluate");
        graph.addConditionalEdges("evaluate", AsyncEdgeAction.edge_async(state -> value(state, "needsFollowUp", false) ? "follow_up" : "next"),
                Map.of("follow_up", "follow_up", "next", "select_question"));
        graph.addEdge("follow_up", "evaluate");
        graph.addEdge("report", StateGraph.END);
        graph.addEdge("reject", StateGraph.END);
        return graph.compile(CompileConfig.builder().checkpointSaver(saver).releaseThread(false).build());
    }

    private static void routingScenarios() throws Exception {
        runScenario("valid-comprehensive", Map.of("mode", "COMPREHENSIVE", "resumeConfirmed", true,
                "questionBankConfirmed", false, "jdProvided", true));
        runScenario("valid-question-bank", Map.of("mode", "QUESTION_BANK", "resumeConfirmed", false,
                "questionBankConfirmed", true, "jdProvided", false));
        runScenario("invalid-comprehensive-without-resume", Map.of("mode", "COMPREHENSIVE", "resumeConfirmed", false,
                "questionBankConfirmed", false, "jdProvided", false));
    }

    private static void runScenario(String label, Map<String, Object> input) throws Exception {
        var saver = newSaver();
        String threadId = "routing-" + label + "-" + System.nanoTime();
        var config = RunnableConfig.builder().threadId(threadId).build();
        Map<String, Object> initial = new HashMap<>(input);
        initial.put("questions", List.of("介绍一个 RAG 项目。", "怎样设计模型评估？"));
        newGraph(saver, new AtomicBoolean(false)).invoke(initial, config);
        var last = newGraph(saver, new AtomicBoolean(false)).lastStateOf(config).orElseThrow();
        boolean rejected = "MODE_INPUT_INVALID".equals(value(last.state(), "terminalReason", ""));
        boolean expectedReject = label.contains("invalid");
        if (rejected != expectedReject || value(last.state(), "reportReady", false) == expectedReject) {
            throw new IllegalStateException("route assertion failed for " + label + ": " + last.node());
        }
        System.out.println("scenario=" + label + " finalNode=" + last.node()
                + " visited=" + value(last.state(), "visited", List.of())
                + " reportReady=" + value(last.state(), "reportReady", false)
                + " rejected=" + "MODE_INPUT_INVALID".equals(value(last.state(), "terminalReason", "")));
        saver.release(config);
    }

    private static void crash(String[] args) throws Exception {
        String threadId = requireThreadId(args);
        var saver = newSaver();
        var config = RunnableConfig.builder().threadId(threadId).build();
        Map<String, Object> initial = Map.of("mode", "COMPREHENSIVE", "resumeConfirmed", true,
                "questionBankConfirmed", false, "jdProvided", true,
                "questions", List.of("介绍一个 RAG 项目。", "怎样设计模型评估？"));
        try {
            newGraph(saver, new AtomicBoolean(true)).invoke(initial, config);
            throw new IllegalStateException("crash injection did not fire");
        } catch (RuntimeException expected) {
            if (!hasCauseMessage(expected, "POC simulated process stop")) throw expected;
            var checkpoint = newGraph(saver, new AtomicBoolean(false)).lastStateOf(config).orElseThrow();
            if (!"ask".equals(checkpoint.node())) throw new IllegalStateException("expected saved ask checkpoint, got " + checkpoint.node());
            if (!"evaluate".equals(checkpoint.next())) throw new IllegalStateException("expected saved next node evaluate, got " + checkpoint.next());
            System.out.println("crashCheckpoint threadId=" + threadId + " lastNode=" + checkpoint.node()
                    + " nextNode=" + checkpoint.next()
                    + " visited=" + value(checkpoint.state(), "visited", List.of()));
        }
    }

    private static void resume(String[] args) throws Exception {
        String threadId = requireThreadId(args);
        var saver = newSaver();
        var config = RunnableConfig.builder().threadId(threadId).build();
        var graph = newGraph(saver, new AtomicBoolean(false));
        var prior = graph.lastStateOf(config).orElseThrow(() -> new IllegalStateException("no saved checkpoint"));
        if (!"ask".equals(prior.node())) throw new IllegalStateException("resume must start at saved ask node, got " + prior.node());
        List<String> priorVisited = value(prior.state(), "visited", List.of());
        graph.invoke(GraphInput.resume(), config);
        var restored = graph.lastStateOf(config).orElseThrow();
        boolean complete = value(restored.state(), "reportReady", false);
        if (!complete) throw new IllegalStateException("restored graph did not reach report node");
        List<String> restoredVisited = value(restored.state(), "visited", List.of());
        if (restoredVisited.size() <= priorVisited.size()
                || !restoredVisited.subList(0, priorVisited.size()).equals(priorVisited)
                || restoredVisited.stream().filter("prepare"::equals).count() != 1) {
            throw new IllegalStateException("resume replayed completed nodes instead of continuing the saved checkpoint: " + restoredVisited);
        }
        System.out.println("resumeOk threadId=" + threadId + " beforeNode=" + prior.node()
                + " nextNode=" + prior.next() + " afterNode=" + restored.node() + " visited=" + restoredVisited
                + " reportReady=" + complete);
        saver.release(config);
    }

    private static String requireThreadId(String[] args) {
        if (args.length < 2 || args[1].isBlank()) throw new IllegalArgumentException("thread-id required");
        return args[1];
    }

    private static boolean hasCauseMessage(Throwable failure, String prefix) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().startsWith(prefix)) return true;
        }
        return false;
    }

    private static Map<String, Object> append(AgentState state, String node, Map<String, Object> updates) {
        int steps = value(state, "stepCount", 0) + 1;
        if (steps > MAX_GRAPH_STEPS) throw new IllegalStateException("POC_GRAPH_STEP_LIMIT_EXCEEDED");
        List<String> visited = new ArrayList<>(value(state, "visited", List.of()));
        visited.add(node);
        Map<String, Object> result = new HashMap<>(updates);
        result.put("visited", visited);
        result.put("stepCount", steps);
        return result;
    }

    private static <T> T value(AgentState state, String key, T fallback) {
        return state.<T>value(key).orElse(fallback);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
