package local.interviewmirror.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @BeforeEach
    void seedAccounts() {
        jdbc.update("DELETE FROM managed_documents");
        jdbc.update("DELETE FROM demo_resources");
        jdbc.update("DELETE FROM stored_files");
        jdbc.update("DELETE FROM app_users");
        storage.clear();
        parser.clearFailures();
        jdbc.update("INSERT INTO app_users(id, username, email, display_name, password_hash) VALUES (?, 'demo1', 'demo1@local.interviewmirror', '用户一', ?)", UUID.fromString("00000000-0000-0000-0000-000000000001"), encoder.encode("MirrorDemo1!"));
        jdbc.update("INSERT INTO app_users(id, username, email, display_name, password_hash) VALUES (?, 'demo2', 'demo2@local.interviewmirror', '用户二', ?)", UUID.fromString("00000000-0000-0000-0000-000000000002"), encoder.encode("MirrorDemo2!"));
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
