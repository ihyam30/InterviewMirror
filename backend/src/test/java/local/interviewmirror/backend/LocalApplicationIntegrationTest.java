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

    @BeforeEach
    void seedAccounts() {
        jdbc.update("DELETE FROM demo_resources");
        jdbc.update("DELETE FROM stored_files");
        jdbc.update("DELETE FROM app_users");
        storage.clear();
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
    }

    static class MemoryStorage implements ObjectStorage {
        private final Map<String, byte[]> values = new ConcurrentHashMap<>();
        @Override public void put(String key, java.io.InputStream content, long size, String contentType) throws Exception {
            values.put(key, content.readAllBytes());
        }
        @Override public java.io.InputStream get(String key) {
            byte[] value = values.get(key);
            if (value == null) throw new IllegalStateException("missing object");
            return new java.io.ByteArrayInputStream(value);
        }
        @Override public void delete(String key) { values.remove(key); }
        void clear() { values.clear(); }
        boolean contains(String key) { return values.containsKey(key); }
        java.util.Set<String> keys() { return java.util.Set.copyOf(values.keySet()); }
    }
}
