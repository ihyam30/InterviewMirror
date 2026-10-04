package local.interviewmirror.backend.interviews;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;

/** Cross-JVM Stage 3 graph checkpoint smoke. Run only against a disposable PostgreSQL database. */
class InterviewPostgresCheckpointRecoveryTest {
    private static final String CHILD_CLASS = InterviewPostgresCheckpointRecoveryTest.class.getName();

    @Test
    @EnabledIfEnvironmentVariable(named = "PHASE3_POSTGRES_URL", matches = ".+")
    void actualInterviewGraphResumesCheckpointFromANewJvm() throws Exception {
        Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration").load().migrate();
        try (var connection = dataSource().getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*), MAX(version) FROM flyway_schema_history WHERE success")) {
            assertTrue(result.next());
            assertEquals(4, result.getInt(1), "fresh PostgreSQL must apply V1 through V4");
            assertEquals("4", result.getString(2));
        }
        UUID interviewId = UUID.randomUUID();
        UUID answeredTurnId = UUID.randomUUID();
        String begin = child("begin", interviewId, "");
        assertTrue(begin.contains("CHECKPOINT_CREATED"), begin);

        String answer = child("answer", interviewId, answeredTurnId.toString());
        assertTrue(answer.contains("ANSWER_TRANSITION_SAVED"), answer);

        String recovered = child("verify", interviewId, answeredTurnId.toString());
        assertTrue(recovered.contains("CHECKPOINT_RECOVERED"), recovered);

        UUID followupInterviewId = UUID.randomUUID();
        assertTrue(child("begin-followup", followupInterviewId, "").contains("FOLLOWUP_GRAPH_CREATED"));
        for (int index = 1; index <= 3; index++) {
            String transition = child("followup-answer", followupInterviewId, UUID.randomUUID().toString(),
                    "ask-more-" + index);
            if (index < 3) assertTrue(transition.contains("FOLLOWUP_COUNT_" + index), transition);
            else assertTrue(transition.contains("FOLLOWUP_CAP_ENFORCED"), transition);
        }
    }

    private static String child(String action, UUID interviewId, String turnId, String... extra) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, CHILD_CLASS, action, interviewId.toString(), turnId));
        command.addAll(List.of(extra));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        if (!process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Stage 3 graph child process timed out: " + action);
        }
        String text = output.get(5, TimeUnit.SECONDS);
        assertEquals(0, process.exitValue(), action + " child JVM failed:\n" + text);
        return text;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || System.getenv("PHASE3_POSTGRES_URL") == null) {
            throw new IllegalArgumentException("Expected action, interviewId, and PHASE3_POSTGRES_URL.");
        }
        UUID interviewId = UUID.fromString(args[1]);
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .stateSerializer(new ObjectStreamStateSerializer<>(AgentState::new)).createTables(true).build();
        var compiled = new InterviewCheckpointConfiguration().interviewGraph(saver, new DeterministicModel());
        LangGraph4jInterviewGraph graph = new LangGraph4jInterviewGraph(compiled);

        switch (args[0]) {
            case "begin" -> {
                graph.begin(interviewId, UUID.randomUUID(), InterviewMode.COMPREHENSIVE,
                        UUID.randomUUID(), null, plan());
                requireWaitingFor(graph.snapshot(interviewId), "主问题 1");
                System.out.println("CHECKPOINT_CREATED " + interviewId);
            }
            case "begin-followup" -> {
                graph.begin(interviewId, UUID.randomUUID(), InterviewMode.COMPREHENSIVE,
                        UUID.randomUUID(), null, plan());
                requireWaitingFor(graph.snapshot(interviewId), "主问题 1");
                System.out.println("FOLLOWUP_GRAPH_CREATED " + interviewId);
            }
            case "answer" -> {
                InterviewGraphRuntime.GraphSnapshot resumed = graph.answer(interviewId,
                        UUID.fromString(args[2]), "durably submitted answer");
                requireWaitingFor(resumed, "主问题 2");
                System.out.println("ANSWER_TRANSITION_SAVED " + resumed.lastAnsweredTurnId());
            }
            case "verify" -> {
                InterviewGraphRuntime.GraphSnapshot recovered = graph.snapshot(interviewId);
                requireWaitingFor(recovered, "主问题 2");
                assertEquals(args[2], recovered.lastAnsweredTurnId(), "saved answer turn must survive JVM restart");
                assertEquals(1, recovered.mainQuestionIndex());
                System.out.println("CHECKPOINT_RECOVERED " + recovered.lastAnsweredTurnId());
            }
            case "followup-answer" -> {
                InterviewGraphRuntime.GraphSnapshot next = graph.answer(interviewId,
                        UUID.fromString(args[2]), args[3]);
                if ("ask-more-1".equals(args[3])) {
                    assertEquals("FOLLOW_UP", next.currentTurnType());
                    assertEquals(1, next.followupCount());
                    System.out.println("FOLLOWUP_COUNT_1");
                } else if ("ask-more-2".equals(args[3])) {
                    assertEquals("FOLLOW_UP", next.currentTurnType());
                    assertEquals(2, next.followupCount());
                    System.out.println("FOLLOWUP_COUNT_2");
                } else {
                    assertEquals("MAIN", next.currentTurnType());
                    assertEquals(1, next.mainQuestionIndex());
                    assertEquals(0, next.followupCount());
                    System.out.println("FOLLOWUP_CAP_ENFORCED");
                }
            }
            default -> throw new IllegalArgumentException("Unknown action: " + args[0]);
        }
    }

    private static void requireWaitingFor(InterviewGraphRuntime.GraphSnapshot state, String question) {
        assertEquals("WAITING_FOR_ANSWER", state.phase());
        assertEquals(question, state.currentQuestion());
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(System.getenv("PHASE3_POSTGRES_URL"),
                System.getenv("PHASE3_POSTGRES_USER"), System.getenv("PHASE3_POSTGRES_PASSWORD"));
    }

    private static List<InterviewModel.PlannedQuestion> plan() {
        return java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> new InterviewModel.PlannedQuestion("q-" + index, "主问题 " + index,
                        "工程", "checkpoint smoke", "source-" + index)).toList();
    }

    private static final class DeterministicModel implements InterviewModel {
        @Override public List<PlannedQuestion> generateComprehensivePlan(String resume, String jd, int target) { return plan(); }
        @Override public FollowupDecision evaluateAnswer(String question, String answer) {
            return answer.startsWith("ask-more")
                    ? new FollowupDecision(true, "还需验证细节", "请补充一条可验证的信息：" + answer)
                    : new FollowupDecision(false, "充分", "");
        }
        @Override public PlannedQuestion generateReplacement(String resume, String jd, String replaced, List<String> used) {
            return new PlannedQuestion("replacement", "替换问题", "工程", "smoke", null);
        }
        @Override public String provider() { return "checkpoint-smoke"; }
        @Override public String modelId() { return "deterministic"; }
    }
}
