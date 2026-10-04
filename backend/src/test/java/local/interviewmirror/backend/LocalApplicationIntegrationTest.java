package local.interviewmirror.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import local.interviewmirror.backend.files.ObjectStorage;
import local.interviewmirror.backend.documents.DocumentParseWorker;
import local.interviewmirror.backend.documents.DocumentRepository;
import local.interviewmirror.backend.documents.DocumentParser;
import local.interviewmirror.backend.documents.DocumentContentRules;
import local.interviewmirror.backend.interviews.InterviewGraphRuntime;
import local.interviewmirror.backend.interviews.InterviewMode;
import local.interviewmirror.backend.interviews.InterviewModel;
import local.interviewmirror.backend.interviews.InterviewTurnType;
import local.interviewmirror.backend.interviews.InterviewService;
import local.interviewmirror.backend.interviews.InterviewRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalApplicationIntegrationTest.StorageTestConfiguration.class)
class LocalApplicationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired MemoryStorage storage;
    @Autowired DocumentParseWorker parseWorker;
    @Autowired DocumentRepository documents;
    @Autowired ControlledParser parser;
    @Autowired DeterministicInterviewGraph interviewGraph;
    @Autowired InterviewService interviewService;
    @Autowired InterviewRepository interviewRepository;

    @BeforeEach
    void seedAccounts() {
        jdbc.update("DELETE FROM interviews");
        jdbc.update("DELETE FROM managed_documents");
        jdbc.update("DELETE FROM demo_resources");
        jdbc.update("DELETE FROM stored_files");
        jdbc.update("DELETE FROM app_users");
        storage.clear();
        parser.clearFailures();
        interviewGraph.clear();
        jdbc.update("INSERT INTO app_users(id, username, email, display_name, password_hash) VALUES (?, 'demo1', 'demo1@local.interviewmirror', '用户一', ?)", UUID.fromString("00000000-0000-0000-0000-000000000001"), encoder.encode("MirrorDemo1!"));
        jdbc.update("INSERT INTO app_users(id, username, email, display_name, password_hash) VALUES (?, 'demo2', 'demo2@local.interviewmirror', '用户二', ?)", UUID.fromString("00000000-0000-0000-0000-000000000002"), encoder.encode("MirrorDemo2!"));
    }

    @Test
    void interviewLifecyclePersistsAnswersAndEnforcesOwnerIsolation() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        Session b = login("demo2", "MirrorDemo2!");
        JsonNode uploaded = uploadDocument("/api/v1/resumes", "interview-resume.pdf", "application/pdf", a);
        UUID resumeId = UUID.fromString(uploaded.path("id").asText());
        parseWorker.processOne();
        mvc.perform(post("/api/v1/resumes/" + resumeId + "/confirm").session(a.session())
                .cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())).andExpect(status().isOk());

        String invalidConsent = "{\"schemaVersion\":\"1.1.0\",\"clientRequestId\":\"consent-missing\",\"mode\":\"COMPREHENSIVE\",\"resumeId\":\"" + resumeId + "\",\"locale\":\"zh-CN\",\"modelDataConsent\":false}";
        mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(invalidConsent)).andExpect(status().isForbidden());

        String create = "{\"schemaVersion\":\"1.1.0\",\"clientRequestId\":\"comprehensive-create-1\",\"mode\":\"COMPREHENSIVE\",\"resumeId\":\"" + resumeId + "\",\"locale\":\"zh-CN\",\"modelDataConsent\":true}";
        MvcResult created = mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isOk()).andReturn();
        JsonNode view = json.readTree(created.getResponse().getContentAsString()).path("data");
        UUID interviewId = UUID.fromString(view.path("id").asText());
        mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"id\":\"" + interviewId + "\"}}", false));

        MvcResult started = mvc.perform(post("/api/v1/interviews/" + interviewId + "/start").session(a.session())
                .cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())).andExpect(status().isOk()).andReturn();
        JsonNode running = json.readTree(started.getResponse().getContentAsString()).path("data");
        org.junit.jupiter.api.Assertions.assertEquals("RUNNING", running.path("status").asText());
        org.junit.jupiter.api.Assertions.assertTrue(running.path("replacementAvailable").asBoolean());
        UUID turnId = UUID.fromString(running.path("activeTurn").path("id").asText());
        String replace = "{\"turnId\":\"" + turnId + "\",\"clientRequestId\":\"replace-idempotency-1\"}";
        mvc.perform(post("/api/v1/interviews/" + interviewId + "/replace-question").session(b.session()).cookie(b.csrfCookie())
                .header("X-XSRF-TOKEN", b.csrf()).contentType(MediaType.APPLICATION_JSON).content(replace))
                .andExpect(status().isNotFound());
        MvcResult replaced = mvc.perform(post("/api/v1/interviews/" + interviewId + "/replace-question").session(a.session())
                .cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON).content(replace))
                .andExpect(status().isOk()).andReturn();
        JsonNode replacedView = json.readTree(replaced.getResponse().getContentAsString()).path("data");
        org.junit.jupiter.api.Assertions.assertEquals("替换问题", replacedView.path("activeTurn").path("question").asText());
        turnId = UUID.fromString(replacedView.path("activeTurn").path("id").asText());
        mvc.perform(post("/api/v1/interviews/" + interviewId + "/replace-question").session(a.session())
                .cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON).content(replace))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(*) FROM interview_turns WHERE interview_id=?", Integer.class, interviewId));
        String answer = "{\"turnId\":\"" + turnId + "\",\"clientRequestId\":\"answer-idempotency-1\",\"answer\":\"具体回答\"}";
        mvc.perform(get("/api/v1/interviews/" + interviewId).session(b.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/interviews/" + interviewId + "/events").session(b.session())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/interviews/" + interviewId + "/answers").session(b.session()).cookie(b.csrfCookie())
                .header("X-XSRF-TOKEN", b.csrf()).contentType(MediaType.APPLICATION_JSON).content(answer))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/interviews/" + interviewId + "/answers").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON).content(answer)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/interviews/" + interviewId + "/answers").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON).content(answer)).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(json.readTree(mvc.perform(get("/api/v1/interviews/" + interviewId).session(a.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/transitionPending").asBoolean());
        interviewService.recover();
        JsonNode recoveredTurns = json.readTree(mvc.perform(get("/api/v1/interviews/" + interviewId + "/turns").session(a.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/turns");
        org.junit.jupiter.api.Assertions.assertEquals(3, recoveredTurns.size());
        org.junit.jupiter.api.Assertions.assertEquals("SKIPPED", recoveredTurns.get(0).path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals("具体回答", recoveredTurns.get(1).path("answer").asText());
        org.junit.jupiter.api.Assertions.assertEquals("后端问题 2", recoveredTurns.get(2).path("question").asText());
        org.junit.jupiter.api.Assertions.assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM interview_turns WHERE interview_id=?", Integer.class, interviewId));

        mvc.perform(post("/api/v1/interviews/" + interviewId + "/end").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"end-1\"}")).andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"status\":\"COMPLETE\",\"completionReason\":\"USER_ENDED\"}}", false));
        mvc.perform(post("/api/v1/interviews/" + interviewId + "/end").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"end-replay\"}")).andExpect(status().isOk());
    }

    @Test
    void completingInterviewIsRecoveredAfterBackendInterruption() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        JsonNode uploaded = uploadDocument("/api/v1/resumes", "recovery-resume.pdf", "application/pdf", a);
        UUID resumeId = UUID.fromString(uploaded.path("id").asText());
        parseWorker.processOne();
        mvc.perform(post("/api/v1/resumes/" + resumeId + "/confirm").session(a.session())
                .cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())).andExpect(status().isOk());
        String create = "{\"schemaVersion\":\"1.1.0\",\"clientRequestId\":\"completion-recovery-create\","
                + "\"mode\":\"COMPREHENSIVE\",\"resumeId\":\"" + resumeId
                + "\",\"locale\":\"zh-CN\",\"modelDataConsent\":true}";
        MvcResult created = mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isOk()).andReturn();
        UUID interviewId = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).at("/data/id").asText());
        mvc.perform(post("/api/v1/interviews/" + interviewId + "/start").session(a.session())
                .cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())).andExpect(status().isOk());

        InterviewRepository.EndClaim endClaim = interviewRepository.beginEarlyEnd(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), interviewId, "end-before-crash");
        org.junit.jupiter.api.Assertions.assertTrue(endClaim.claimed());
        org.junit.jupiter.api.Assertions.assertFalse(interviewRepository.beginEarlyEnd(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), interviewId, "concurrent-end").claimed());
        jdbc.update("UPDATE interviews SET updated_at=? WHERE id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(180)), interviewId);
        org.junit.jupiter.api.Assertions.assertEquals("COMPLETING", jdbc.queryForObject(
                "SELECT status FROM interviews WHERE id=?", String.class, interviewId));
        interviewService.recover();
        org.junit.jupiter.api.Assertions.assertEquals("COMPLETE", jdbc.queryForObject(
                "SELECT status FROM interviews WHERE id=?", String.class, interviewId));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM interview_turns WHERE interview_id=? AND status='SKIPPED'", Integer.class, interviewId));
    }

    @Test
    void unconfirmedResumeIsRejectedByInterviewApi() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        JsonNode uploaded = uploadDocument("/api/v1/resumes", "unconfirmed.pdf", "application/pdf", a);
        String create = "{\"schemaVersion\":\"1.1.0\",\"clientRequestId\":\"unconfirmed-create\",\"mode\":\"COMPREHENSIVE\",\"resumeId\":\"" + uploaded.path("id").asText() + "\",\"locale\":\"zh-CN\",\"modelDataConsent\":true}";
        mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM interviews", Integer.class));
    }

    @Test
    void createRejectsUnknownFieldsAndNullOptionalJdAgainstVersionedSchema() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        String base = "\"schemaVersion\":\"1.1.0\",\"clientRequestId\":\"strict-schema\","
                + "\"mode\":\"COMPREHENSIVE\",\"resumeId\":\"00000000-0000-0000-0000-000000000099\","
                + "\"locale\":\"zh-CN\",\"modelDataConsent\":true";
        mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{" + base + ",\"unexpectedUserId\":\"00000000-0000-0000-0000-000000000002\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{" + base + ",\"jdText\":null}"))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM interviews", Integer.class));
    }

    @Test
    void specializedInterviewRequiresConfirmedOwnedBankAndUsesBankQuestions() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        Session b = login("demo2", "MirrorDemo2!");
        MvcResult manual = mvc.perform(post("/api/v1/question-banks/manual").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Six question bank\"}"))
                .andExpect(status().isOk()).andReturn();
        UUID bankId = UUID.fromString(json.readTree(manual.getResponse().getContentAsString()).at("/data/id").asText());
        tools.jackson.databind.node.ObjectNode bank = json.createObjectNode()
                .put("schemaVersion", "interviewmirror.question-bank-content.v1");
        var questions = bank.putArray("questions");
        for (int i = 1; i <= 6; i++) questions.addObject().put("id", "bank-q-" + i)
                .put("position", i).put("stem", "专项问题 " + i).put("answer", "").put("category", "Java");
        var duplicateBank = (tools.jackson.databind.node.ObjectNode) bank.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) duplicateBank.path("questions").get(5))
                .put("stem", "专项问题，1！");
        org.junit.jupiter.api.Assertions.assertEquals(5,
                DocumentContentRules.countDistinctQuestionStems(duplicateBank.path("questions")));
        mvc.perform(put("/api/v1/question-banks/" + bankId).session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(1, "Six question bank", json.writeValueAsString(duplicateBank))))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":{\"code\":\"INVALID_DOCUMENT_CONTENT\"}}", false));
        mvc.perform(put("/api/v1/question-banks/" + bankId).session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(1, "Six question bank", json.writeValueAsString(bank)))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/question-banks/" + bankId + "/confirm").session(a.session())
                .cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())).andExpect(status().isOk());

        String create = "{\"schemaVersion\":\"1.1.0\",\"clientRequestId\":\"specialized-create-1\","
                + "\"mode\":\"QUESTION_BANK\",\"questionBankId\":\"" + bankId
                + "\",\"locale\":\"zh-CN\",\"modelDataConsent\":true}";
        MvcResult created = mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isOk()).andReturn();
        UUID interviewId = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).at("/data/id").asText());
        MvcResult specializedStarted = mvc.perform(post("/api/v1/interviews/" + interviewId + "/start").session(a.session())
                .cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())).andExpect(status().isOk()).andReturn();
        JsonNode specializedTurn = json.readTree(specializedStarted.getResponse().getContentAsString()).at("/data/activeTurn");
        org.junit.jupiter.api.Assertions.assertFalse(json.readTree(specializedStarted.getResponse().getContentAsString())
                .at("/data/replacementAvailable").asBoolean());
        String sourceQuestionId = specializedTurn.path("sourceQuestionId").asText();
        org.junit.jupiter.api.Assertions.assertTrue(sourceQuestionId.matches("bank-q-[1-6]"));
        org.junit.jupiter.api.Assertions.assertEquals("专项问题 " + sourceQuestionId.substring("bank-q-".length()),
                specializedTurn.path("question").asText());
        mvc.perform(get("/api/v1/interviews/" + interviewId + "/turns").session(b.session())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/interviews/" + interviewId + "/end").session(b.session()).cookie(b.csrfCookie())
                .header("X-XSRF-TOKEN", b.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"other-user-end\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(create.replace("specialized-create-1", "specialized-forbidden-jd")
                        .replace("}", ",\"jdText\":null}")))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE managed_documents SET content = ? WHERE id = ?", json.writeValueAsString(duplicateBank), bankId);
        mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(create.replace("specialized-create-1", "legacy-duplicate-bank")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":{\"code\":\"INVALID_DOCUMENT_CONTENT\"}}", false));
    }

    @Test
    void specializedInterviewShowsReplacementOnlyWhenAnUnusedReserveExists() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        UUID bankId = createConfirmedQuestionBank(a, "Seven question bank", 7);
        String create = "{\"schemaVersion\":\"1.1.0\",\"clientRequestId\":\"reserve-create\","
                + "\"mode\":\"QUESTION_BANK\",\"questionBankId\":\"" + bankId
                + "\",\"locale\":\"zh-CN\",\"modelDataConsent\":true}";
        MvcResult created = mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isOk()).andReturn();
        UUID interviewId = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).at("/data/id").asText());
        JsonNode started = json.readTree(mvc.perform(post("/api/v1/interviews/" + interviewId + "/start").session(a.session())
                .cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        org.junit.jupiter.api.Assertions.assertTrue(started.path("replacementAvailable").asBoolean());

        String replace = "{\"turnId\":\"" + started.at("/activeTurn/id").asText()
                + "\",\"clientRequestId\":\"reserve-replace\"}";
        JsonNode replaced = json.readTree(mvc.perform(post("/api/v1/interviews/" + interviewId + "/replace-question")
                .session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(replace)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        org.junit.jupiter.api.Assertions.assertFalse(replaced.path("replacementAvailable").asBoolean());
    }

    @Test
    void sseStreamsQuestionFollowupCompletionAndResumesFromLastEventId() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        Session b = login("demo2", "MirrorDemo2!");
        JsonNode uploaded = uploadDocument("/api/v1/resumes", "sse-resume.pdf", "application/pdf", a);
        UUID resumeId = UUID.fromString(uploaded.path("id").asText());
        parseWorker.processOne();
        mvc.perform(post("/api/v1/resumes/" + resumeId + "/confirm").session(a.session())
                .cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())).andExpect(status().isOk());
        String create = "{\"schemaVersion\":\"1.1.0\",\"clientRequestId\":\"sse-create\","
                + "\"mode\":\"COMPREHENSIVE\",\"resumeId\":\"" + resumeId
                + "\",\"locale\":\"zh-CN\",\"modelDataConsent\":true}";
        MvcResult created = mvc.perform(post("/api/v1/interviews").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isOk()).andReturn();
        UUID interviewId = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).at("/data/id").asText());
        JsonNode started = json.readTree(mvc.perform(post("/api/v1/interviews/" + interviewId + "/start").session(a.session())
                .cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        UUID mainTurnId = UUID.fromString(started.at("/activeTurn/id").asText());
        MvcResult stream = mvc.perform(get("/api/v1/interviews/" + interviewId + "/events").session(a.session())
                .header("Last-Event-ID", "0")).andExpect(request().asyncStarted()).andReturn();

        String mainAnswer = "{\"turnId\":\"" + mainTurnId + "\",\"clientRequestId\":\"sse-answer-main\",\"answer\":\"trigger-follow-up\"}";
        mvc.perform(post("/api/v1/interviews/" + interviewId + "/answers").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON).content(mainAnswer))
                .andExpect(status().isOk());
        interviewService.recover();
        JsonNode followup = json.readTree(mvc.perform(get("/api/v1/interviews/" + interviewId + "/turns").session(a.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/turns/1");
        org.junit.jupiter.api.Assertions.assertEquals("FOLLOW_UP", followup.path("type").asText());
        String followupAnswer = "{\"turnId\":\"" + followup.path("id").asText()
                + "\",\"clientRequestId\":\"sse-answer-followup\",\"answer\":\"metrics\"}";
        mvc.perform(post("/api/v1/interviews/" + interviewId + "/answers").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON).content(followupAnswer))
                .andExpect(status().isOk());
        interviewService.recover();

        mvc.perform(get("/api/v1/interviews/" + interviewId + "/events").session(b.session()))
                .andExpect(status().isNotFound());
        Long cursorBeforeCompletion = jdbc.queryForObject("SELECT MAX(event_id) FROM interview_events WHERE interview_id=?",
                Long.class, interviewId);
        mvc.perform(post("/api/v1/interviews/" + interviewId + "/end").session(a.session()).cookie(a.csrfCookie())
                .header("X-XSRF-TOKEN", a.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"sse-end\"}")).andExpect(status().isOk());

        // SseEmitter completes asynchronously after its polling batch drains. Wait for
        // Spring MVC to publish the async result before dispatching it in MockMvc.
        stream.getAsyncResult(10_000);
        String streamBody = mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(streamBody.contains("event:interview.question"));
        org.junit.jupiter.api.Assertions.assertTrue(streamBody.contains("event:interview.followup"));
        org.junit.jupiter.api.Assertions.assertTrue(streamBody.contains("event:interview.answer.saved"));
        org.junit.jupiter.api.Assertions.assertTrue(streamBody.contains("event:interview.completed"));

        MvcResult resumed = mvc.perform(get("/api/v1/interviews/" + interviewId + "/events").session(a.session())
                .header("Last-Event-ID", Long.toString(cursorBeforeCompletion))).andExpect(request().asyncStarted()).andReturn();
        resumed.getAsyncResult(10_000);
        String resumedBody = mvc.perform(asyncDispatch(resumed)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(resumedBody.contains("event:interview.completed"));
        org.junit.jupiter.api.Assertions.assertFalse(resumedBody.contains("event:interview.question"));
    }

    @Test
    void authenticationAndResourceOwnershipAreEnforced() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        Session a = login("demo1@local.interviewmirror", "MirrorDemo1!");
        Session b = login("demo2", "MirrorDemo2!");
        mvc.perform(get("/api/v1/auth/me").session(a.session())).andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"username\":\"demo1\"}}", false));
        String create = "{\"resourceType\":\"NOTE\",\"title\":\"private\",\"content\":\"owner A\"}";
        MvcResult created = mvc.perform(post("/api/v1/resources").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isOk()).andReturn();
        JsonNode resourceView = json.readTree(created.getResponse().getContentAsString()).path("data");
        org.junit.jupiter.api.Assertions.assertFalse(resourceView.has("ownerId"));
        UUID id = UUID.fromString(resourceView.path("id").asText());
        mvc.perform(put("/api/v1/resources/" + id).session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"updated\",\"content\":\"owner A changed it\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/resources").session(b.session())).andExpect(status().isOk())
                .andExpect(content().json("{\"data\":[]}"));
        mvc.perform(get("/api/v1/resources/" + id).session(b.session())).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/resources/" + id).session(b.session()).cookie(b.csrfCookie()).header("X-XSRF-TOKEN", b.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"hijack\",\"content\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/resources/" + id).session(b.session()).cookie(b.csrfCookie()).header("X-XSRF-TOKEN", b.csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/resources/" + id).session(a.session())).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/resources/" + id).session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void fileMetadataDownloadAndDeleteAreOwnerScoped() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        Session b = login("demo2", "MirrorDemo2!");
        MockMultipartFile payload = new MockMultipartFile("file", "resume.txt", "text/plain", "private resume data".getBytes());
        MvcResult uploaded = mvc.perform(multipart("/api/v1/files").file(payload).session(a.session()).cookie(a.csrfCookie())
                        .header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isOk()).andReturn();
        JsonNode metadata = json.readTree(uploaded.getResponse().getContentAsString()).path("data");
        UUID id = UUID.fromString(metadata.path("id").asText());
        org.junit.jupiter.api.Assertions.assertFalse(metadata.has("objectKey"));
        org.junit.jupiter.api.Assertions.assertFalse(metadata.has("ownerId"));
        String objectKey = storage.keys().stream()
                .filter(key -> key.startsWith("users/00000000-0000-0000-0000-000000000001/"))
                .findFirst().orElseThrow();

        org.junit.jupiter.api.Assertions.assertEquals(0, json.readTree(mvc.perform(get("/api/v1/files").session(b.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").size());
        mvc.perform(get("/api/v1/files/" + id).session(a.session())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/files/" + id).session(b.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/files/" + id + "/content").session(b.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/files/" + id + "/presigned-url").session(b.session())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/files/" + id).session(b.session()).cookie(b.csrfCookie()).header("X-XSRF-TOKEN", b.csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/files/" + id + "/content").session(a.session()))
                .andExpect(status().isOk()).andExpect(content().bytes("private resume data".getBytes()));
        mvc.perform(delete("/api/v1/files/" + id).session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertFalse(storage.contains(objectKey));
        mvc.perform(get("/api/v1/files/" + id).session(a.session())).andExpect(status().isNotFound());
    }

    @Test
    void uploadRejectsUnsupportedExtensionsMismatchedMimeAndInvalidSignatures() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        mvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("file", "payload.exe", "application/x-msdownload", "MZ executable".getBytes()))
                        .session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("file", "resume.pdf", "application/x-msdownload", "%PDF-1.7".getBytes()))
                        .session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("file", "resume.pdf", "application/pdf", "MZ executable".getBytes()))
                        .session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("file", "resume.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "not a DOCX package".getBytes()))
                        .session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertTrue(storage.keys().isEmpty(), "Rejected uploads must not reach object storage");
    }

    @Test
    void resumeLifecycleEnforcesOwnerConfirmationEditInvalidationAndDelete() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        Session b = login("demo2", "MirrorDemo2!");
        JsonNode uploaded = uploadDocument("/api/v1/resumes", "resume.pdf", "application/pdf", a);
        UUID id = UUID.fromString(uploaded.path("id").asText());
        UUID taskId = UUID.fromString(uploaded.path("parseTask").path("id").asText());
        org.junit.jupiter.api.Assertions.assertEquals("PENDING", uploaded.path("status").asText());
        mvc.perform(get("/api/v1/interview-sources/resumes/" + id).session(a.session())).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/resumes/" + id).session(b.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/parse-tasks/" + taskId).session(b.session())).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/resumes/" + id).session(b.session()).cookie(b.csrfCookie()).header("X-XSRF-TOKEN", b.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(updateBody(1, "hijack", resumeContent())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/resumes/" + id + "/confirm").session(b.session()).cookie(b.csrfCookie()).header("X-XSRF-TOKEN", b.csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/resumes/" + id + "/retry").session(b.session()).cookie(b.csrfCookie()).header("X-XSRF-TOKEN", b.csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/resumes/" + id).session(b.session()).cookie(b.csrfCookie()).header("X-XSRF-TOKEN", b.csrf()))
                .andExpect(status().isNotFound());

        org.junit.jupiter.api.Assertions.assertTrue(parseWorker.processOne());
        JsonNode parsed = json.readTree(mvc.perform(get("/api/v1/resumes/" + id).session(a.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        org.junit.jupiter.api.Assertions.assertEquals("PARSED", parsed.path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals("Lin", parsed.path("content").path("personalInfo").path("name").asText());
        mvc.perform(get("/api/v1/interview-sources/resumes/" + id).session(a.session())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/resumes/" + id + "/confirm").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isOk()).andExpect(content().json("{\"data\":{\"status\":\"CONFIRMED\",\"usableForInterview\":true}}", false));
        mvc.perform(get("/api/v1/interview-sources/resumes/" + id).session(a.session())).andExpect(status().isOk());

        mvc.perform(put("/api/v1/resumes/" + id).session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(updateBody(parsed.path("contentVersion").asInt(), "edited resume", resumeContent())))
                .andExpect(status().isOk()).andExpect(content().json("{\"data\":{\"status\":\"PARSED\",\"usableForInterview\":false}}", false));
        mvc.perform(get("/api/v1/interview-sources/resumes/" + id).session(a.session())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/resumes/" + id + "/confirm").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/files/" + uploaded.path("fileId").asText()).session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/resumes/" + id).session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/resumes/" + id).session(a.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/files/" + uploaded.path("fileId").asText()).session(a.session())).andExpect(status().isNotFound());
    }

    @Test
    void resumeWithExtractedTextButNoRecognizedSectionsFailsInsteadOfShowingEmptySuccess() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        JsonNode uploaded = uploadDocument("/api/v1/resumes", "empty-structure.pdf", "application/pdf", a);
        UUID id = UUID.fromString(uploaded.path("id").asText());
        UUID taskId = UUID.fromString(uploaded.path("parseTask").path("id").asText());

        org.junit.jupiter.api.Assertions.assertTrue(parseWorker.processOne());

        mvc.perform(get("/api/v1/resumes/" + id).session(a.session())).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"data":{"status":"FAILED","parseTask":{"status":"FAILED","errorCode":"RESUME_CONTENT_NOT_RECOGNIZED",
                        "errorMessage":"文件文字已提取，但没有识别到简历结构内容；请确认版式清晰，或重新上传 PDF/DOCX。"}}}
                        """, false));
        mvc.perform(get("/api/v1/parse-tasks/" + taskId).session(a.session())).andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"status\":\"FAILED\"}}", false));
    }

    @Test
    void questionBankLifecycleSupportsEditConfirmRetryAndRecovery() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        JsonNode uploaded = uploadDocument("/api/v1/question-banks", "bank.md", "text/markdown", a);
        UUID id = UUID.fromString(uploaded.path("id").asText());
        UUID taskId = UUID.fromString(uploaded.path("parseTask").path("id").asText());
        Session b = login("demo2", "MirrorDemo2!");
        mvc.perform(get("/api/v1/question-banks/" + id).session(b.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/parse-tasks/" + taskId).session(b.session())).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/question-banks/" + id).session(b.session()).cookie(b.csrfCookie()).header("X-XSRF-TOKEN", b.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(updateBody(1, "hijack", questionContent())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/question-banks/" + id + "/confirm").session(b.session()).cookie(b.csrfCookie()).header("X-XSRF-TOKEN", b.csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/question-banks/" + id + "/retry").session(b.session()).cookie(b.csrfCookie()).header("X-XSRF-TOKEN", b.csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/question-banks/" + id).session(b.session()).cookie(b.csrfCookie()).header("X-XSRF-TOKEN", b.csrf()))
                .andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertTrue(parseWorker.processOne());
        JsonNode parsed = json.readTree(mvc.perform(get("/api/v1/question-banks/" + id).session(a.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        org.junit.jupiter.api.Assertions.assertEquals("PARSED", parsed.path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1, parsed.path("content").path("questions").size());
        mvc.perform(get("/api/v1/interview-sources/question-banks/" + id).session(a.session())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/question-banks/" + id + "/confirm").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/question-banks/" + id).session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(updateBody(2, "edited bank", questionContent())))
                .andExpect(status().isOk()).andExpect(content().json("{\"data\":{\"status\":\"PARSED\",\"usableForInterview\":false}}", false));
        mvc.perform(post("/api/v1/question-banks/" + id + "/confirm").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/interview-sources/question-banks/" + id).session(a.session())).andExpect(status().isOk());
        JsonNode successfulTask = json.readTree(mvc.perform(get("/api/v1/parse-tasks/" + taskId).session(a.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED", successfulTask.path("status").asText());
        org.junit.jupiter.api.Assertions.assertTrue(successfulTask.path("durationMs").asLong() >= 0);

        MvcResult manual = mvc.perform(post("/api/v1/question-banks/manual").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Manual bank\"}"))
                .andExpect(status().isOk()).andReturn();
        UUID manualId = UUID.fromString(json.readTree(manual.getResponse().getContentAsString()).path("data").path("id").asText());
        mvc.perform(post("/api/v1/question-banks/" + manualId + "/confirm").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/question-banks/" + manualId).session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(updateBody(1, "Manual bank", questionContent())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/question-banks/" + manualId + "/confirm").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isOk());

        parser.failNext();
        JsonNode failed = uploadDocument("/api/v1/resumes", "retry.pdf", "application/pdf", a);
        UUID failedId = UUID.fromString(failed.path("id").asText());
        UUID retryTaskId = UUID.fromString(failed.path("parseTask").path("id").asText());
        parseWorker.processOne();
        mvc.perform(get("/api/v1/resumes/" + failedId).session(a.session())).andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"status\":\"FAILED\",\"parseTask\":{\"errorCode\":\"TEST_FAILURE\"}}}", false));
        mvc.perform(post("/api/v1/resumes/" + failedId + "/retry").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isOk()).andExpect(content().json("{\"data\":{\"status\":\"PENDING\",\"parseTask\":{\"retryCount\":1}}}", false));
        mvc.perform(post("/api/v1/resumes/" + failedId + "/retry").session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isConflict());
        parseWorker.processOne();
        mvc.perform(get("/api/v1/parse-tasks/" + retryTaskId).session(a.session())).andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"status\":\"SUCCEEDED\",\"retryCount\":1,\"attemptCount\":2}}", false));

        JsonNode processing = uploadDocument("/api/v1/resumes", "processing.pdf", "application/pdf", a);
        UUID processingId = UUID.fromString(processing.path("id").asText());
        var task = documents.claimNext("test-worker", java.time.Duration.ofMinutes(3)).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(processingId, task.documentId());
        mvc.perform(delete("/api/v1/resumes/" + processingId).session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(1, documents.recoverExpired(java.time.Instant.now().plus(java.time.Duration.ofMinutes(4))));
        mvc.perform(get("/api/v1/resumes/" + processingId).session(a.session())).andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"status\":\"PENDING\",\"parseTask\":{\"errorCode\":\"WORKER_INTERRUPTED\"}}}", false));
        var replacement = documents.claimNext("replacement-worker", java.time.Duration.ofMinutes(3)).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(2, replacement.attemptCount());
        org.junit.jupiter.api.Assertions.assertFalse(documents.complete(task.id(), task.documentId(), task.ownerId(),
                resumeContent(), task.startedAt(), task.workerId(), task.attemptCount()));
        org.junit.jupiter.api.Assertions.assertFalse(documents.fail(task.id(), task.documentId(), task.ownerId(),
                "STALE_FAILURE", "must be ignored", task.startedAt(), task.workerId(), task.attemptCount()));
        org.junit.jupiter.api.Assertions.assertTrue(documents.complete(replacement.id(), replacement.documentId(),
                replacement.ownerId(), resumeContent(), replacement.startedAt(), replacement.workerId(),
                replacement.attemptCount()));
        mvc.perform(get("/api/v1/resumes/" + processingId).session(a.session())).andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"status\":\"PARSED\",\"parseTask\":{\"status\":\"SUCCEEDED\",\"attemptCount\":2}}}", false));
    }

    @Test
    void failedObjectDeletionKeepsDocumentRetryable() throws Exception {
        Session a = login("demo1", "MirrorDemo1!");
        JsonNode uploaded = uploadDocument("/api/v1/resumes", "delete-retry.pdf", "application/pdf", a);
        UUID id = UUID.fromString(uploaded.path("id").asText());
        UUID fileId = UUID.fromString(uploaded.path("fileId").asText());
        org.junit.jupiter.api.Assertions.assertTrue(parseWorker.processOne());
        storage.failDeletes = true;

        mvc.perform(delete("/api/v1/resumes/" + id).session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isBadGateway());
        mvc.perform(get("/api/v1/resumes/" + id).session(a.session())).andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"status\":\"DELETE_FAILED\"}}", false));
        mvc.perform(get("/api/v1/files/" + fileId).session(a.session())).andExpect(status().isOk());

        storage.failDeletes = false;
        mvc.perform(delete("/api/v1/resumes/" + id).session(a.session()).cookie(a.csrfCookie()).header("X-XSRF-TOKEN", a.csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/resumes/" + id).session(a.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/files/" + fileId).session(a.session())).andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertTrue(storage.keys().isEmpty());
    }

    private JsonNode uploadDocument(String endpoint, String filename, String contentType, Session session) throws Exception {
        byte[] data = filename.endsWith(".pdf") ? "%PDF-1.7\nfixture".getBytes() : "synthetic fixture".getBytes();
        MvcResult result = mvc.perform(multipart(endpoint).file(new MockMultipartFile("file", filename, contentType, data))
                        .session(session.session()).cookie(session.csrfCookie()).header("X-XSRF-TOKEN", session.csrf()))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private UUID createConfirmedQuestionBank(Session session, String title, int questionCount) throws Exception {
        MvcResult manual = mvc.perform(post("/api/v1/question-banks/manual").session(session.session()).cookie(session.csrfCookie())
                .header("X-XSRF-TOKEN", session.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", title))))
                .andExpect(status().isOk()).andReturn();
        UUID bankId = UUID.fromString(json.readTree(manual.getResponse().getContentAsString()).at("/data/id").asText());
        var content = json.createObjectNode().put("schemaVersion", "interviewmirror.question-bank-content.v1");
        var questions = content.putArray("questions");
        for (int i = 1; i <= questionCount; i++) {
            questions.addObject().put("id", "reserve-q-" + i).put("position", i)
                    .put("stem", "备用测试问题 " + i).put("answer", "").put("category", "Java");
        }
        mvc.perform(put("/api/v1/question-banks/" + bankId).session(session.session()).cookie(session.csrfCookie())
                .header("X-XSRF-TOKEN", session.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(1, title, json.writeValueAsString(content)))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/question-banks/" + bankId + "/confirm").session(session.session())
                .cookie(session.csrfCookie()).header("X-XSRF-TOKEN", session.csrf())).andExpect(status().isOk());
        return bankId;
    }

    private static String updateBody(int version, String title, String content) {
        return "{\"contentVersion\":" + version + ",\"title\":\"" + title + "\",\"content\":" + content + "}";
    }

    private static String resumeContent() {
        return "{\"schemaVersion\":\"interviewmirror.resume-content.v1\",\"personalInfo\":{\"name\":\"Lin\"},\"education\":[],\"experiences\":[],\"projects\":[],\"skills\":[\"Java\"],\"awards\":[],\"metrics\":[]}";
    }

    private static String questionContent() {
        return "{\"schemaVersion\":\"interviewmirror.question-bank-content.v1\",\"questions\":[{\"position\":1,\"stem\":\"How do you validate model output quality?\",\"answer\":\"\"},{\"position\":2,\"stem\":\"How do you handle a failed parsing task?\",\"answer\":\"\"}]}";
    }

    private Session login(String username, String password) throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        String csrf = json.readTree(csrfResult.getResponse().getContentAsString()).at("/data/token").asText();
        jakarta.servlet.http.Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").cookie(csrfCookie).header("X-XSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("identifier", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        MvcResult refreshedCsrf = mvc.perform(get("/api/v1/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        String freshToken = json.readTree(refreshedCsrf.getResponse().getContentAsString()).at("/data/token").asText();
        jakarta.servlet.http.Cookie freshCookie = refreshedCsrf.getResponse().getCookie("XSRF-TOKEN");
        return new Session(session, freshCookie, freshToken);
    }

    private record Session(MockHttpSession session, jakarta.servlet.http.Cookie csrfCookie, String csrf) {}

    @TestConfiguration
    static class StorageTestConfiguration {
        @Bean @Primary MemoryStorage memoryStorage() { return new MemoryStorage(); }
        @Bean @Primary ControlledParser controlledParser() { return new ControlledParser(); }
        @Bean @Primary DeterministicInterviewGraph deterministicInterviewGraph() { return new DeterministicInterviewGraph(); }
        @Bean @Primary InterviewModel deterministicInterviewModel() {
            return new InterviewModel() {
                @Override public java.util.List<PlannedQuestion> generateComprehensivePlan(String resume, String jd, int count) {
                    return java.util.stream.IntStream.rangeClosed(1, count).mapToObj(i ->
                            new PlannedQuestion("plan-" + i, "后端问题 " + i, "工程", "测试计划", null)).toList();
                }
                @Override public FollowupDecision evaluateAnswer(String question, String answer) {
                    return new FollowupDecision(false, "test", "");
                }
                @Override public PlannedQuestion generateReplacement(String resume, String jd, String replaced, java.util.List<String> used) {
                    return new PlannedQuestion("replacement", "替换问题", "工程", "测试", null);
                }
                @Override public String provider() { return "TEST"; }
                @Override public String modelId() { return "test-model"; }
            };
        }
    }

    static class DeterministicInterviewGraph implements InterviewGraphRuntime {
        private final Map<UUID, GraphSnapshot> states = new ConcurrentHashMap<>();
        void clear() { states.clear(); }
        @Override public GraphSnapshot begin(UUID id, UUID owner, InterviewMode mode, UUID resumeId, UUID questionBankId,
                java.util.List<InterviewModel.PlannedQuestion> plan) {
            var mapped = plan.stream().map(q -> Map.<String, Object>of("id", q.id(), "stem", q.stem(),
                    "category", q.category() == null ? "" : q.category(), "rationale", q.rationale() == null ? "" : q.rationale(),
                    "sourceQuestionId", q.sourceQuestionId() == null ? "" : q.sourceQuestionId())).toList();
            var first = plan.getFirst();
            GraphSnapshot state = new GraphSnapshot("WAITING_FOR_ANSWER", "", 0, 0, 0, "MAIN",
                    first.id(), first.sourceQuestionId(), first.stem(), first.category(), "", "", "false", mapped);
            states.put(id, state); return state;
        }
        @Override public GraphSnapshot answer(UUID id, UUID turnId, String answer) {
            GraphSnapshot current = states.get(id);
            if ("trigger-follow-up".equals(answer)) {
                GraphSnapshot followup = new GraphSnapshot("WAITING_FOR_ANSWER", "", current.mainQuestionIndex(),
                        current.followupCount() + 1, 0, "FOLLOW_UP", "followup-test-id", "",
                        "针对你的回答，请说明一个具体验证指标？", "工程", turnId.toString(), answer, "false", current.questionPlan());
                states.put(id, followup); return followup;
            }
            int next = current.mainQuestionIndex() + 1;
            Map<String, Object> selected = next < current.questionPlan().size()
                    ? current.questionPlan().get(next) : Map.of("id", "", "stem", "");
            GraphSnapshot result = next >= 6
                    ? new GraphSnapshot("COMPLETE", "QUESTION_LIMIT", 6, 0, 0, "MAIN", "", "", "", "", turnId.toString(), answer, "false", current.questionPlan())
                    : new GraphSnapshot("WAITING_FOR_ANSWER", "", next, 0, 0, "MAIN",
                            String.valueOf(selected.getOrDefault("id", "")),
                            String.valueOf(selected.getOrDefault("sourceQuestionId", "")),
                            String.valueOf(selected.getOrDefault("stem", "")),
                            String.valueOf(selected.getOrDefault("category", "")),
                            turnId.toString(), answer, "false", current.questionPlan());
            states.put(id, result); return result;
        }
        @Override public GraphSnapshot replace(UUID id, UUID turnId, InterviewModel.PlannedQuestion replacement,
                java.util.List<InterviewModel.PlannedQuestion> plan) {
            GraphSnapshot current = states.get(id);
            GraphSnapshot result = new GraphSnapshot("WAITING_FOR_ANSWER", "", current.mainQuestionIndex(), 0,
                    current.replaceCount() + 1, "MAIN", replacement.id(), replacement.sourceQuestionId(), replacement.stem(),
                    replacement.category(), current.lastAnsweredTurnId(), current.lastAnswer(), "false",
                    plan.stream().map(q -> Map.<String, Object>of("id", q.id(), "stem", q.stem(),
                            "category", q.category() == null ? "" : q.category(), "rationale", q.rationale() == null ? "" : q.rationale(),
                            "sourceQuestionId", q.sourceQuestionId() == null ? "" : q.sourceQuestionId())).toList());
            states.put(id, result); return result;
        }
        @Override public GraphSnapshot end(UUID id) {
            GraphSnapshot current = states.get(id);
            GraphSnapshot result = new GraphSnapshot("COMPLETE", "USER_ENDED", current.mainQuestionIndex(),
                    current.followupCount(), current.replaceCount(), current.currentTurnType(), current.currentQuestionId(),
                    current.sourceQuestionId(), current.currentQuestion(), current.currentCategory(), current.lastAnsweredTurnId(),
                    current.lastAnswer(), "false", current.questionPlan());
            states.put(id, result); return result;
        }
        @Override public GraphSnapshot snapshot(UUID id) {
            GraphSnapshot state = states.get(id);
            if (state == null) throw new IllegalStateException("checkpoint missing");
            return state;
        }
    }

    static class ControlledParser implements DocumentParser {
        private final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        void failNext() { failures.incrementAndGet(); }
        void clearFailures() { failures.set(0); }
        @Override public String parse(String filename, String contentType, byte[] content) {
            if (failures.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0) {
                throw new local.interviewmirror.backend.documents.DocumentParseException("TEST_FAILURE", "测试解析失败");
            }
            if (filename.contains("empty-structure")) return "# Resume\nNo recognized resume fields in this fixture.";
            if (filename.contains("bank")) {
                return "# Questions\n| # | Question |\n|---|---|\n| 1 | How do you validate model output quality? |";
            }
            return "Name: Lin\nEducation: BS Computer Science\nSkills: Java, RAG\nProject: InterviewMirror document parser\nMetric: Recall 0.92";
        }
    }

    static class MemoryStorage implements ObjectStorage {
        private final Map<String, byte[]> values = new ConcurrentHashMap<>();
        volatile boolean failDeletes;
        @Override public void put(String key, java.io.InputStream content, long size, String contentType) throws Exception {
            values.put(key, content.readAllBytes());
        }
        @Override public java.io.InputStream get(String key) {
            byte[] value = values.get(key);
            if (value == null) throw new IllegalStateException("missing object");
            return new java.io.ByteArrayInputStream(value);
        }
        @Override public void delete(String key) {
            if (failDeletes) throw new IllegalStateException("simulated storage deletion failure");
            values.remove(key);
        }
        void clear() { values.clear(); failDeletes = false; }
        boolean contains(String key) { return values.containsKey(key); }
        java.util.Set<String> keys() { return java.util.Set.copyOf(values.keySet()); }
    }
}
