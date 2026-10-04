package local.interviewmirror.backend.interviews;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import local.interviewmirror.backend.InterviewMirrorApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Optional full-backend PostgreSQL restart smoke. The parent test starts two independent JVMs:
 * the first uses the real Spring Boot API to persist a user answer and is forcibly terminated;
 * the second starts the backend again and verifies scheduled recovery advances the graph and API.
 */
class InterviewBackendRestartRecoveryTest {
    private static final String CHILD_CLASS = InterviewBackendRestartRecoveryTest.class.getName();
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    @EnabledIfEnvironmentVariable(named = "PHASE3_BACKEND_POSTGRES_URL", matches = ".+")
    void backendRestartRecoversPendingAnswerThroughPostgresAndInterviewApi() throws Exception {
        String createOutput = child("save-answer");
        assertTrue(createOutput.contains("ANSWER_DURABLY_SAVED"), createOutput);
        String interviewId = markerValue(createOutput, "interviewId=");
        String turnId = markerValue(createOutput, "turnId=");

        String recoveredOutput = child("recover-answer", interviewId, turnId);
        assertTrue(recoveredOutput.contains("BACKEND_RESTART_RECOVERY_PASSED"), recoveredOutput);
    }

    private static String child(String action, String... args) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, CHILD_CLASS, action));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        if (!process.waitFor(Duration.ofSeconds(150).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Full backend restart child JVM timed out: " + action);
        }
        String text = output.get(10, TimeUnit.SECONDS);
        assertEquals(0, process.exitValue(), action + " child JVM failed:\n" + text);
        return text;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || System.getenv("PHASE3_BACKEND_POSTGRES_URL") == null) {
            throw new IllegalArgumentException("Expected action and PHASE3_BACKEND_POSTGRES_URL.");
        }
        switch (args[0]) {
            case "save-answer" -> saveAnswerThenSimulateProcessCrash();
            case "recover-answer" -> recoverAnswerAfterRestart(args[1], args[2]);
            default -> throw new IllegalArgumentException("Unknown restart smoke action.");
        }
    }

    private static void saveAnswerThenSimulateProcessCrash() throws Exception {
        int port = availablePort();
        try (ConfigurableApplicationContext context = startBackend("PT1H", port)) {
            HttpClient client = authenticatedClient(port);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            ObjectMapper json = context.getBean(ObjectMapper.class);
            String ownerId = jdbc.queryForObject("SELECT id FROM app_users WHERE username='demo1'", String.class);
            String content = "{\"schemaVersion\":\"interviewmirror.resume-content.v1\","
                    + "\"personalInfo\":{\"name\":\"Stage 3 restart smoke\"},"
                    + "\"education\":[],\"experiences\":[],\"projects\":[],\"skills\":[],"
                    + "\"awards\":[],\"metrics\":[]}";
            var documentId = java.util.UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO managed_documents(id, owner_id, file_id, document_type, title, status,
                      parsed_content, content, content_version, confirmed_version, confirmed_at)
                    VALUES (?, ?, NULL, 'RESUME', 'Stage 3 restart smoke', 'CONFIRMED', ?, ?, 1, 1, CURRENT_TIMESTAMP)
                    """, documentId, java.util.UUID.fromString(ownerId), content, content);

            String createJson = "{\"schemaVersion\":\"1.1.0\",\"clientRequestId\":\"restart-smoke-"
                    + java.util.UUID.randomUUID() + "\",\"mode\":\"COMPREHENSIVE\",\"resumeId\":\""
                    + documentId + "\",\"locale\":\"zh-CN\",\"modelDataConsent\":true}";
            JsonNode created = responseData(send(client, "POST", "/api/v1/interviews", createJson, csrf(client, port)), 200, "create interview");
            String interviewId = created.path("id").asString();
            JsonNode started = responseData(send(client, "POST", "/api/v1/interviews/" + interviewId + "/start",
                    "", csrf(client, port)), 200, "start interview");
            String turnId = started.path("activeTurn").path("id").asString();
            assertTrue(!turnId.isBlank(), "start API must return the current turn");

            String answerJson = "{\"turnId\":\"" + turnId + "\",\"clientRequestId\":\"restart-answer-"
                    + java.util.UUID.randomUUID() + "\",\"answer\":\"answer persisted before backend restart\"}";
            responseData(send(client, "POST", "/api/v1/interviews/" + interviewId + "/answers",
                    answerJson, csrf(client, port)), 200, "save answer");
            String transition = jdbc.queryForObject("SELECT transition_state FROM interviews WHERE id=?",
                    String.class, java.util.UUID.fromString(interviewId));
            assertEquals("PENDING", transition, "the initial backend must leave recovery to the restarted process");
            System.out.println("ANSWER_DURABLY_SAVED interviewId=" + interviewId + " turnId=" + turnId);
            System.out.flush();
            // Simulate abrupt JVM termination after the API transaction committed but before recovery ran.
            Runtime.getRuntime().halt(0);
        }
    }

    private static void recoverAnswerAfterRestart(String interviewId, String answeredTurnId) throws Exception {
        int port = availablePort();
        try (ConfigurableApplicationContext context = startBackend("PT1S", port)) {
            HttpClient client = authenticatedClient(port);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            JsonNode interview = null;
            while (System.nanoTime() < deadline) {
                HttpResponse<String> response = send(client, "GET", "/api/v1/interviews/" + interviewId, null, null);
                if (response.statusCode() == 200) {
                    interview = JSON.readTree(response.body()).path("data");
                    if ("RUNNING".equals(interview.path("status").asString())
                            && "重启恢复后的主问题 2".equals(interview.path("activeTurn").path("question").asString())
                            && !interview.path("transitionPending").asBoolean(true)) break;
                }
                Thread.sleep(500);
            }
            assertTrue(interview != null, "interview GET must return the recovered persisted interview");
            assertEquals("RUNNING", interview.path("status").asString());
            assertEquals("重启恢复后的主问题 2", interview.path("activeTurn").path("question").asString());
            assertEquals(false, interview.path("transitionPending").asBoolean());

            JsonNode turns = responseData(send(client, "GET", "/api/v1/interviews/" + interviewId + "/turns", null, null),
                    200, "get interview turns").path("turns");
            assertEquals(2, turns.size());
            assertEquals(answeredTurnId, turns.get(0).path("id").asString());
            assertEquals("ANSWERED", turns.get(0).path("status").asString());
            assertEquals("answer persisted before backend restart", turns.get(0).path("answer").asString());
            assertEquals("重启恢复后的主问题 2", turns.get(1).path("question").asString());
            System.out.println("BACKEND_RESTART_RECOVERY_PASSED interviewId=" + interviewId
                    + " answeredTurnId=" + answeredTurnId + " activeQuestion=2");
        }
    }

    private static ConfigurableApplicationContext startBackend(String recoveryInterval, int port) {
        var application = new SpringApplicationBuilder(InterviewMirrorApplication.class, RestartSmokeConfiguration.class)
                .web(WebApplicationType.SERVLET);
        return application.run(
                "--spring.profiles.active=local",
                "--server.port=" + port,
                "--server.address=127.0.0.1",
                "--spring.datasource.url=" + requiredEnv("PHASE3_BACKEND_POSTGRES_URL"),
                "--spring.datasource.username=" + requiredEnv("PHASE3_BACKEND_POSTGRES_USER"),
                "--spring.datasource.password=" + requiredEnv("PHASE3_BACKEND_POSTGRES_PASSWORD"),
                "--interviewmirror.storage.endpoint=" + requiredEnv("PHASE3_BACKEND_S3_ENDPOINT"),
                "--interviewmirror.storage.access-key=" + requiredEnv("PHASE3_BACKEND_S3_ACCESS_KEY"),
                "--interviewmirror.storage.secret-key=" + requiredEnv("PHASE3_BACKEND_S3_SECRET_KEY"),
                "--interviewmirror.storage.bucket=" + requiredEnv("PHASE3_BACKEND_S3_BUCKET"),
                "--interviewmirror.graph.enabled=true",
                "--interviewmirror.graph.recovery-interval=" + recoveryInterval,
                "--interviewmirror.mineru.poll-interval=PT1H",
                "--interviewmirror.model.enabled=false");
    }

    private static HttpClient authenticatedClient(int port) throws Exception {
        CURRENT_PORT.set(port);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build();
        String csrf = csrf(client, port);
        HttpResponse<String> login = send(client, "POST", "/api/v1/auth/login",
                "{\"identifier\":\"demo1\",\"password\":\"MirrorDemo1!\"}", csrf);
        responseData(login, 200, "login");
        csrf(client, port); // Login rotates the session and CSRF cookie.
        return client;
    }

    private static String csrf(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = send(client, "GET", "/api/v1/auth/csrf", null, null);
        JsonNode data = responseData(response, 200, "get CSRF token");
        return data.path("token").asString();
    }

    private static HttpResponse<String> send(HttpClient client, String method, String path,
            String body, String csrf) throws Exception {
        int port = CURRENT_PORT.get();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static final ThreadLocal<Integer> CURRENT_PORT = new ThreadLocal<>();

    private static JsonNode responseData(HttpResponse<String> response, int expectedStatus, String action) throws Exception {
        assertEquals(expectedStatus, response.statusCode(), action + " API failed: " + response.body());
        return JSON.readTree(response.body()).path("data");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing smoke environment variable " + name);
        return value;
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(false);
            return socket.getLocalPort();
        }
    }

    private static String markerValue(String output, String marker) {
        int start = output.indexOf(marker);
        assertTrue(start >= 0, "child output is missing " + marker + ":\n" + output);
        int valueStart = start + marker.length();
        int end = valueStart;
        while (end < output.length() && !Character.isWhitespace(output.charAt(end))) end++;
        return output.substring(valueStart, end);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RestartSmokeConfiguration {
        @Bean
        @Primary
        InterviewModel restartSmokeInterviewModel() {
            return new InterviewModel() {
                @Override public List<PlannedQuestion> generateComprehensivePlan(String resume, String jd, int target) {
                    return IntStream.rangeClosed(1, target).mapToObj(index -> new PlannedQuestion(
                            "restart-q-" + index, index == 1 ? "重启恢复前的主问题 1" : "重启恢复后的主问题 " + index,
                            "工程", "仅用于本地重启恢复 smoke", "restart-q-" + index)).toList();
                }
                @Override public FollowupDecision evaluateAnswer(String question, String answer) {
                    return new FollowupDecision(false, "本地 smoke 不追问", "");
                }
                @Override public PlannedQuestion generateReplacement(String resume, String jd, String replaced,
                        List<String> used) {
                    return new PlannedQuestion("restart-replacement", "本地 smoke 替换题", "工程", "smoke", null);
                }
                @Override public String provider() { return "local-restart-smoke"; }
                @Override public String modelId() { return "deterministic-test-model"; }
            };
        }
    }
}
