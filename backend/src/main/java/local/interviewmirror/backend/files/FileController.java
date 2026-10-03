package local.interviewmirror.backend.files;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.common.ApiResponse;
import local.interviewmirror.backend.documents.DocumentRepository;
import local.interviewmirror.backend.security.AccountPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService files;
    private final DocumentRepository documents;

    public FileController(FileService files, DocumentRepository documents) {
        this.files = files;
        this.documents = documents;
    }

    @GetMapping
    ApiResponse<List<FileView>> list(Authentication authentication) {
        return ApiResponse.of(files.list(principal(authentication).id()).stream().map(StoredFile::toView).toList());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<FileView> upload(Authentication authentication, @RequestParam("file") MultipartFile file) {
        return ApiResponse.of(files.upload(principal(authentication).id(), file).toView());
    }

    @GetMapping("/{id}")
    ApiResponse<FileView> metadata(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.of(files.metadata(principal(authentication).id(), id).toView());
    }

    @GetMapping("/{id}/content")
    ResponseEntity<InputStreamResource> download(Authentication authentication, @PathVariable UUID id) {
        StoredFile metadata = files.metadata(principal(authentication).id(), id);
        InputStream content = files.download(principal(authentication).id(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(metadata.contentType()))
                .contentLength(metadata.sizeBytes()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(metadata.originalFilename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(content));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(Authentication authentication, @PathVariable UUID id) {
        UUID ownerId = principal(authentication).id();
        if (documents.existsForFile(id, ownerId)) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_IN_USE", "该文件属于简历或题库，请从资料页面删除。");
        }
        files.delete(ownerId, id);
        return ApiResponse.of(null);
    }

    private static AccountPrincipal principal(Authentication authentication) {
        return (AccountPrincipal) authentication.getPrincipal();
    }
}
