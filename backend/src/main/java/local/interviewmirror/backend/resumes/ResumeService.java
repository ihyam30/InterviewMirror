package local.interviewmirror.backend.resumes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.documents.DocumentType;
import local.interviewmirror.backend.documents.StructuredDocumentParser;
import local.interviewmirror.backend.files.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

@Service
public class ResumeService {
    private final ResumeRepository repository;
    private final ResumeParser parser;
    private final StructuredDocumentParser structured;
    private final ObjectStorage storage;
    private final ObjectMapper json;
    private final boolean structuredContentEnabled;
    public ResumeService(ResumeRepository repository, ResumeParser parser, StructuredDocumentParser structured,
            ObjectStorage storage, ObjectMapper json,
            @Value("${interviewmirror.resume.structured-content-enabled:false}") boolean structuredContentEnabled) {
        this.repository = repository; this.parser = parser; this.structured = structured; this.storage = storage; this.json = json;
        this.structuredContentEnabled = structuredContentEnabled;
    }
    public ResumeViews.Resume upload(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "请选择非空简历。");
        if (file.getSize() > 10L * 1024 * 1024) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "简历不能超过 10 MB。");
        String filename = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename().replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_").strip();
        if (filename.isBlank() || filename.length() > 255) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "简历文件名无效。");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (java.io.IOException error) { throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_READ_FAILED", "无法读取简历。"); }
        String type = parser.detect(bytes, filename), hash = ResumeEvidenceCatalog.sha256(bytes);
        var existing = repository.duplicate(userId, hash);
        if (existing.isPresent()) return view(repository.access(userId, existing.get().id()), true);
        String text = parser.parse(bytes, filename, type);
        String content = structured(text);
        UUID id = UUID.randomUUID();
        String key = "users/" + userId + "/resumes/" + id + "/original";
        // Register compensation BEFORE the remote write, covering partial writes and process crashes.
        repository.cleanup(null, key);
        try (var input = new ByteArrayInputStream(bytes)) { storage.put(key, input, bytes.length, type); }
        catch (Exception error) { throw new ApiException(HttpStatus.BAD_GATEWAY, "FILE_STORAGE_FAILED", "简历文件保存失败。"); }
        try {
            ResumeRecord row = repository.create(id, userId, hash, filename, type, bytes.length, key, text, content);
            return view(row, true);
        } catch (DuplicateKeyException race) {
            var winner = repository.duplicate(userId, hash).orElseThrow(() -> race);
            return view(repository.access(userId, winner.id()), true);
        }
    }
    public ResumeViews.Page<ResumeViews.Resume> list(UUID userId, int page, int size, boolean usableOnly) {
        validatePage(page, size);
        List<ResumeRecord> rows = repository.list(userId, page, size, usableOnly);
        var ids = rows.stream().map(ResumeRecord::id).toList();
        var latest = repository.latest(userId, ids);
        var counts = repository.interviewCounts(userId, ids);
        return new ResumeViews.Page<>(rows.stream().map(r -> view(r, false, latest.get(r.id()), counts.getOrDefault(r.id(), 0L))).toList(),
                repository.count(userId, usableOnly), page, size);
    }
    public ResumeRecord require(UUID userId, UUID id) { return repository.find(userId, id).orElseThrow(ResumeRepository::notFound); }
    public ResumeViews.Resume get(UUID userId, UUID id) { return view(require(userId, id), true); }
    public ResumeViews.Detail detail(UUID userId, UUID id, int analysisPage, int interviewPage, int size) {
        validatePage(analysisPage, size); validatePage(interviewPage, size);
        return new ResumeViews.Detail(get(userId, id), repository.analyses(userId, id, analysisPage, size), repository.interviews(userId, id, interviewPage, size));
    }
    public ResumeViews.Resume usable(UUID userId, UUID id) {
        var row = require(userId, id);
        requireActive(row);
        if (row.resumeText().isBlank()) throw new ApiException(HttpStatus.CONFLICT, "RESUME_TEXT_MISSING", "简历正文尚未准备完成。");
        return view(row, true);
    }
    public InputStream download(UUID userId, UUID id) {
        var row = require(userId, id); requireActive(row);
        try { return storage.get(row.storageKey()); }
        catch (Exception error) { throw new ApiException(HttpStatus.BAD_GATEWAY, "FILE_READ_FAILED", "简历原文件暂时无法读取。"); }
    }
    public ResumeViews.Resume reanalyze(UUID userId, UUID id) {
        ResumeRecord row = require(userId, id);
        String content = structuredContentEnabled && !row.resumeText().isBlank() ? structured(row.resumeText()) : null;
        repository.reanalyze(userId, id, content);
        return get(userId, id);
    }
    public void delete(UUID userId, UUID id) { repository.beginDelete(userId, id); }
    public String structured(String text) {
        if (!structuredContentEnabled) return disabledStructuredContent();
        return json.writeValueAsString(structured.parse(DocumentType.RESUME, text));
    }
    private ResumeViews.Resume view(ResumeRecord row, boolean full) {
        return view(row, full, repository.latest(row.userId(), List.of(row.id())).get(row.id()),
                repository.interviewCounts(row.userId(), List.of(row.id())).getOrDefault(row.id(), 0L));
    }
    private ResumeViews.Resume view(ResumeRecord row, boolean full, ResumeViews.Analysis latest, long interviews) {
        return new ResumeViews.Resume(row.id(), "RESUME", row.title(), row.originalFilename(), row.fileSize(), row.contentType(),
                row.uploadedAt(), row.analyzeUpdatedAt(), row.accessCount(), row.analyzeStatus(), row.analyzeError(), row.deleteStatus(),
                "ACTIVE".equals(row.deleteStatus()) && !row.resumeText().isBlank(),
                json.readTree(structuredContentEnabled ? row.structuredContent() : disabledStructuredContent()),
                full ? row.resumeText() : null, latest, interviews);
    }
    private static String disabledStructuredContent() {
        return "{\"schemaVersion\":\"interviewmirror.resume-content.disabled.v1\"}";
    }
    static void requireActive(ResumeRecord row) {
        if (!"ACTIVE".equals(row.deleteStatus())) throw new ApiException(HttpStatus.CONFLICT, "RESUME_DELETING", "简历正在删除，暂不可用。");
    }
    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页大小须为 1–100，页码从 0 开始。");
    }
}
