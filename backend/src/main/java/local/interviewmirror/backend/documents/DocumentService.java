package local.interviewmirror.backend.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.files.FileRepository;
import local.interviewmirror.backend.files.FileService;
import local.interviewmirror.backend.files.StoredFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class DocumentService {
    private final DocumentRepository documents;
    private final FileRepository files;
    private final FileService fileService;
    private final DocumentContentRules contentRules;
    private final ObjectMapper json;

    public DocumentService(DocumentRepository documents, FileRepository files, FileService fileService,
                           DocumentContentRules contentRules, ObjectMapper json) {
        this.documents = documents;
        this.files = files;
        this.fileService = fileService;
        this.contentRules = contentRules;
        this.json = json;
    }

    public List<ManagedDocumentView> list(UUID ownerId, DocumentType type, boolean usableOnly) {
        List<ManagedDocumentRecord> rows = usableOnly ? documents.findConfirmed(ownerId, type) : documents.findAll(ownerId, type);
        return rows.stream().map(this::view).toList();
    }

    public ManagedDocumentView createUpload(UUID ownerId, DocumentType type, MultipartFile upload) {
        validateExtension(type, upload == null ? null : upload.getOriginalFilename());
        StoredFile stored = fileService.upload(ownerId, upload);
        try {
            String title = removeExtension(stored.originalFilename());
            JsonNode blank = blankContent(type);
            ManagedDocumentRecord record = documents.create(UUID.randomUUID(), ownerId, stored.id(), type,
                    title, DocumentStatus.PENDING.name(), json.writeValueAsString(blank), true);
            return view(record);
        } catch (Exception error) {
            try { fileService.delete(ownerId, stored.id()); } catch (RuntimeException compensationFailure) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "UPLOAD_COMPENSATION_FAILED",
                        "资料记录创建失败，原文件清理未完成；请在文件列表中重试删除。");
            }
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT_CREATE_FAILED", "资料记录创建失败。");
        }
    }

    public ManagedDocumentView createManualQuestionBank(UUID ownerId, String title) {
        String safeTitle = requiredTitle(title);
        try {
            JsonNode blank = blankContent(DocumentType.QUESTION_BANK);
            ManagedDocumentRecord record = documents.create(UUID.randomUUID(), ownerId, null,
                    DocumentType.QUESTION_BANK, safeTitle, DocumentStatus.PARSED.name(),
                    json.writeValueAsString(blank), false);
            return view(record);
        } catch (Exception error) {
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT_CREATE_FAILED", "题库创建失败。");
        }
    }

    public ManagedDocumentView get(UUID ownerId, DocumentType type, UUID id) {
        return view(requireDocument(ownerId, type, id));
    }

    public ParseTaskView getTask(UUID ownerId, UUID taskId) {
        ParseTaskRecord task = documents.findTaskByIdAndOwner(taskId, ownerId)
                .orElseThrow(DocumentService::notFound);
        return ParseTaskView.from(task);
    }

    public ManagedDocumentView save(UUID ownerId, DocumentType type, UUID id, int expectedVersion,
                                     String title, JsonNode content) {
        ManagedDocumentRecord current = requireDocument(ownerId, type, id);
        if (current.status() != DocumentStatus.PARSED && current.status() != DocumentStatus.CONFIRMED) {
            throw stateConflict("只有解析完成的资料可以编辑。");
        }
        String safeTitle = requiredTitle(title);
        contentRules.validateShape(type, content);
        try {
            String serialized = json.writeValueAsString(content);
            if (!documents.saveContent(id, ownerId, expectedVersion, safeTitle, serialized)) {
                if (documents.findByIdAndOwner(id, ownerId).isEmpty()) throw notFound();
                throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_VERSION_CONFLICT", "资料已在其他请求中更新，请刷新后再保存。");
            }
            return get(ownerId, type, id);
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_CONTENT", "资料内容无法保存。");
        }
    }

    public ManagedDocumentView confirm(UUID ownerId, DocumentType type, UUID id) {
        ManagedDocumentRecord current = requireDocument(ownerId, type, id);
        if (current.status() == DocumentStatus.CONFIRMED) return view(current);
        if (current.status() != DocumentStatus.PARSED) throw stateConflict("只有解析完成并可预览的资料才能确认。");
        JsonNode content = read(current.content());
        contentRules.validateConfirm(type, content, current.title());
        ManagedDocumentRecord confirmed = documents.confirm(id, ownerId)
                .orElseThrow(() -> stateConflict("资料状态已变化，请刷新后再确认。"));
        return view(confirmed);
    }

    public ManagedDocumentView retry(UUID ownerId, DocumentType type, UUID id) {
        ManagedDocumentRecord current = requireDocument(ownerId, type, id);
        if (current.status() != DocumentStatus.FAILED) throw stateConflict("只有解析失败的资料可以重试。");
        if (!documents.retry(id, ownerId)) throw stateConflict("解析任务状态已变化，请刷新后再试。");
        return get(ownerId, type, id);
    }

    public void delete(UUID ownerId, DocumentType type, UUID id) {
        ManagedDocumentRecord before = requireDocument(ownerId, type, id);
        if (before.status() == DocumentStatus.PROCESSING) throw stateConflict("资料正在解析，解析结束或失败后才能删除。");
        ManagedDocumentRecord current = documents.beginDelete(id, ownerId).orElseThrow(DocumentService::notFound);
        if (current.type() != type) throw notFound();
        if (current.status() == DocumentStatus.PROCESSING) throw stateConflict("资料正在解析，解析结束或失败后才能删除。");
        try {
            if (current.fileId() == null) {
                if (!documents.deleteManual(id, ownerId)) throw stateConflict("题库删除状态已变化，请重试。");
            } else {
                fileService.delete(ownerId, current.fileId());
            }
        } catch (RuntimeException error) {
            documents.markDeleteFailed(id, ownerId);
            if (error instanceof ApiException apiError) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DOCUMENT_DELETE_FAILED",
                        "原始文件删除失败，资料仍保留；可以再次点击删除重试。");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DOCUMENT_DELETE_FAILED",
                    "资料删除未完成；资料仍保留，可以再次点击删除重试。");
        }
    }

    public ManagedDocumentView requireUsableForInterview(UUID ownerId, DocumentType type, UUID id) {
        ManagedDocumentRecord document = requireDocument(ownerId, type, id);
        if (document.status() != DocumentStatus.CONFIRMED
                || document.confirmedVersion() == null
                || document.confirmedVersion() != document.contentVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_NOT_CONFIRMED", "请先检查并确认资料后再用于面试。");
        }
        contentRules.validateConfirm(type, read(document.content()), document.title());
        return view(document);
    }

    private ManagedDocumentRecord requireDocument(UUID ownerId, DocumentType type, UUID id) {
        ManagedDocumentRecord document = documents.findByIdAndOwner(id, ownerId).orElseThrow(DocumentService::notFound);
        if (document.type() != type) throw notFound();
        return document;
    }

    private ManagedDocumentView view(ManagedDocumentRecord document) {
        StoredFile file = document.fileId() == null ? null : files.findByIdAndOwner(document.fileId(), document.ownerId()).orElse(null);
        boolean usable = document.status() == DocumentStatus.CONFIRMED
                && document.confirmedVersion() != null && document.confirmedVersion() == document.contentVersion();
        return new ManagedDocumentView(document.id(), document.fileId(), document.type(), document.title(),
                file == null ? null : file.originalFilename(), file == null ? null : file.contentType(),
                file == null ? 0 : file.sizeBytes(), document.status(), read(document.parsedContent()),
                read(document.content()), document.contentVersion(), document.confirmedVersion(),
                document.confirmedAt(), document.createdAt(), document.updatedAt(),
                ParseTaskView.from(document.task()), usable);
    }

    private JsonNode read(String content) {
        try { return json.readTree(content == null || content.isBlank() ? "{}" : content); }
        catch (Exception error) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT_DATA_INVALID", "资料数据无法读取。"); }
    }

    private static JsonNode blankContent(DocumentType type) {
        if (type == DocumentType.RESUME) {
            return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("schemaVersion", "interviewmirror.resume-content.v1")
                    .set("personalInfo", tools.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        }
        return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", "interviewmirror.question-bank-content.v1")
                .set("questions", tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode());
    }

    private static void validateExtension(DocumentType type, String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
        boolean allowed = type == DocumentType.RESUME
                ? lower.endsWith(".pdf") || lower.endsWith(".docx")
                : lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".txt")
                || lower.endsWith(".md") || lower.endsWith(".markdown");
        if (!allowed) throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_DOCUMENT_TYPE",
                type == DocumentType.RESUME ? "简历仅支持 PDF 和 DOCX。" : "题库支持 PDF、DOCX、TXT 和 Markdown。");
    }

    private static String requiredTitle(String title) {
        String result = title == null ? "" : title.trim();
        if (result.isBlank() || result.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_TITLE", "资料名称不能为空且不能超过 255 字。");
        }
        return result;
    }

    private static String removeExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "资料未找到。"); }
    private static ApiException stateConflict(String message) { return new ApiException(HttpStatus.CONFLICT, "INVALID_DOCUMENT_STATE", message); }
}
