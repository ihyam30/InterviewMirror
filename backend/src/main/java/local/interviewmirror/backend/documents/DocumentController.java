package local.interviewmirror.backend.documents;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiResponse;
import local.interviewmirror.backend.security.AccountPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class DocumentController {
    private final DocumentService documents;

    public DocumentController(DocumentService documents) { this.documents = documents; }

    @GetMapping("/api/v1/resumes")
    ApiResponse<List<ManagedDocumentView>> listResumes(Authentication auth,
            @RequestParam(defaultValue = "false") boolean usableOnly) {
        return ApiResponse.of(documents.list(userId(auth), DocumentType.RESUME, usableOnly));
    }

    @PostMapping(path = "/api/v1/resumes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<ManagedDocumentView> uploadResume(Authentication auth, @RequestParam("file") MultipartFile file) {
        return ApiResponse.of(documents.createUpload(userId(auth), DocumentType.RESUME, file));
    }

    @GetMapping("/api/v1/resumes/{id}")
    ApiResponse<ManagedDocumentView> getResume(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(documents.get(userId(auth), DocumentType.RESUME, id));
    }

    @PutMapping("/api/v1/resumes/{id}")
    ApiResponse<ManagedDocumentView> updateResume(Authentication auth, @PathVariable UUID id,
            @Valid @RequestBody DocumentRequests.Update body) {
        return ApiResponse.of(documents.save(userId(auth), DocumentType.RESUME, id, body.contentVersion(), body.title(), body.content()));
    }

    @PostMapping("/api/v1/resumes/{id}/confirm")
    ApiResponse<ManagedDocumentView> confirmResume(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(documents.confirm(userId(auth), DocumentType.RESUME, id));
    }

    @PostMapping("/api/v1/resumes/{id}/retry")
    ApiResponse<ManagedDocumentView> retryResume(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(documents.retry(userId(auth), DocumentType.RESUME, id));
    }

    @DeleteMapping("/api/v1/resumes/{id}")
    ApiResponse<Void> deleteResume(Authentication auth, @PathVariable UUID id) {
        documents.delete(userId(auth), DocumentType.RESUME, id);
        return ApiResponse.of(null);
    }

    @GetMapping("/api/v1/question-banks")
    ApiResponse<List<ManagedDocumentView>> listQuestionBanks(Authentication auth,
            @RequestParam(defaultValue = "false") boolean usableOnly) {
        return ApiResponse.of(documents.list(userId(auth), DocumentType.QUESTION_BANK, usableOnly));
    }

    @PostMapping(path = "/api/v1/question-banks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<ManagedDocumentView> uploadQuestionBank(Authentication auth, @RequestParam("file") MultipartFile file) {
        return ApiResponse.of(documents.createUpload(userId(auth), DocumentType.QUESTION_BANK, file));
    }

    @PostMapping("/api/v1/question-banks/manual")
    ApiResponse<ManagedDocumentView> createQuestionBank(Authentication auth,
            @Valid @RequestBody DocumentRequests.CreateQuestionBank body) {
        return ApiResponse.of(documents.createManualQuestionBank(userId(auth), body.title()));
    }

    @GetMapping("/api/v1/question-banks/{id}")
    ApiResponse<ManagedDocumentView> getQuestionBank(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(documents.get(userId(auth), DocumentType.QUESTION_BANK, id));
    }

    @PutMapping("/api/v1/question-banks/{id}")
    ApiResponse<ManagedDocumentView> updateQuestionBank(Authentication auth, @PathVariable UUID id,
            @Valid @RequestBody DocumentRequests.Update body) {
        return ApiResponse.of(documents.save(userId(auth), DocumentType.QUESTION_BANK, id, body.contentVersion(), body.title(), body.content()));
    }

    @PostMapping("/api/v1/question-banks/{id}/confirm")
    ApiResponse<ManagedDocumentView> confirmQuestionBank(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(documents.confirm(userId(auth), DocumentType.QUESTION_BANK, id));
    }

    @PostMapping("/api/v1/question-banks/{id}/retry")
    ApiResponse<ManagedDocumentView> retryQuestionBank(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(documents.retry(userId(auth), DocumentType.QUESTION_BANK, id));
    }

    @DeleteMapping("/api/v1/question-banks/{id}")
    ApiResponse<Void> deleteQuestionBank(Authentication auth, @PathVariable UUID id) {
        documents.delete(userId(auth), DocumentType.QUESTION_BANK, id);
        return ApiResponse.of(null);
    }

    @GetMapping("/api/v1/parse-tasks/{id}")
    ApiResponse<ParseTaskView> getTask(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(documents.getTask(userId(auth), id));
    }

    @GetMapping("/api/v1/interview-sources/resumes/{id}")
    ApiResponse<ManagedDocumentView> resumeInterviewSource(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(documents.requireUsableForInterview(userId(auth), DocumentType.RESUME, id));
    }

    @GetMapping("/api/v1/interview-sources/question-banks/{id}")
    ApiResponse<ManagedDocumentView> questionBankInterviewSource(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.of(documents.requireUsableForInterview(userId(auth), DocumentType.QUESTION_BANK, id));
    }

    private static UUID userId(Authentication auth) { return ((AccountPrincipal) auth.getPrincipal()).id(); }
}
