package local.interviewmirror.backend.files;

import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {
    private static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final Map<String, FileType> ALLOWED_TYPES = Map.of(
            ".pdf", new FileType("application/pdf", Set.of("application/pdf"), Signature.PDF),
            ".docx", new FileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"), Signature.ZIP),
            ".txt", new FileType("text/plain", Set.of("text/plain"), Signature.NONE),
            ".md", new FileType("text/markdown", Set.of("text/markdown", "text/plain", "text/x-markdown"), Signature.NONE),
            ".markdown", new FileType("text/markdown", Set.of("text/markdown", "text/plain", "text/x-markdown"), Signature.NONE)
    );
    private final FileRepository files;
    private final ObjectStorage storage;

    public FileService(FileRepository files, ObjectStorage storage) {
        this.files = files;
        this.storage = storage;
    }

    public List<StoredFile> list(UUID ownerId) { return files.findAll(ownerId); }

    public StoredFile metadata(UUID ownerId, UUID id) { return owned(ownerId, id); }

    public StoredFile upload(UUID ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Choose a non-empty file");
        if (file.getSize() > MAX_SIZE) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Files are limited to 20 MB");
        String filename = sanitizeFilename(file.getOriginalFilename());
        FileType fileType = allowedFileType(filename);
        String suppliedContentType = normalizeContentType(file.getContentType());
        if (!fileType.acceptedContentTypes().contains(suppliedContentType)) {
            throw unsupportedFileType("The file extension and MIME type do not match an allowed document format");
        }
        validateSignature(file, fileType.signature());
        String contentType = fileType.canonicalContentType();
        UUID id = UUID.randomUUID();
        String objectKey = "users/" + ownerId + "/" + id;
        try (InputStream content = file.getInputStream()) {
            storage.put(objectKey, content, file.getSize(), contentType);
        } catch (Exception error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "FILE_STORAGE_FAILED", "The file could not be stored");
        }
        try {
            return files.insert(id, ownerId, objectKey, filename, contentType, file.getSize());
        } catch (RuntimeException error) {
            try { storage.delete(objectKey); } catch (Exception ignored) { }
            throw error;
        }
    }

    public InputStream download(UUID ownerId, UUID id) {
        StoredFile file = owned(ownerId, id);
        try { return storage.get(file.objectKey()); }
        catch (Exception error) { throw new ApiException(HttpStatus.BAD_GATEWAY, "FILE_READ_FAILED", "The file could not be read"); }
    }

    public void delete(UUID ownerId, UUID id) {
        StoredFile file = owned(ownerId, id);
        try { storage.delete(file.objectKey()); }
        catch (Exception error) { throw new ApiException(HttpStatus.BAD_GATEWAY, "FILE_DELETE_FAILED", "The file could not be deleted"); }
        if (!files.delete(id, ownerId)) throw notFound();
    }

    private StoredFile owned(UUID ownerId, UUID id) {
        return files.findByIdAndOwner(id, ownerId).orElseThrow(FileService::notFound);
    }

    private static String sanitizeFilename(String raw) {
        String value = raw == null ? "upload" : raw.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_").trim();
        if (value.isBlank()) value = "upload";
        return value.substring(0, Math.min(255, value.length()));
    }

    private static FileType allowedFileType(String filename) {
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
        FileType fileType = ALLOWED_TYPES.get(extension);
        if (fileType == null) throw unsupportedFileType("Allowed file types are PDF, DOCX, TXT and Markdown");
        return fileType;
    }

    private static String normalizeContentType(String raw) {
        if (raw == null) return "";
        int parameters = raw.indexOf(';');
        String mediaType = parameters < 0 ? raw : raw.substring(0, parameters);
        return mediaType.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateSignature(MultipartFile file, Signature signature) {
        if (signature == Signature.NONE) return;
        try (InputStream content = file.getInputStream()) {
            byte[] prefix = content.readNBytes(signature == Signature.PDF ? 1024 : 4);
            boolean valid = switch (signature) {
                case PDF -> contains(prefix, new byte[]{'%', 'P', 'D', 'F', '-'});
                case ZIP -> prefix.length == 4 && prefix[0] == 'P' && prefix[1] == 'K'
                        && ((prefix[2] == 3 && prefix[3] == 4) || (prefix[2] == 5 && prefix[3] == 6)
                        || (prefix[2] == 7 && prefix[3] == 8));
                case NONE -> true;
            };
            if (!valid) throw unsupportedFileType("The file content does not match its declared document format");
        } catch (IOException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_READ_FAILED", "The uploaded file could not be read");
        }
    }

    private static boolean contains(byte[] content, byte[] marker) {
        for (int offset = 0; offset <= content.length - marker.length; offset++) {
            boolean matches = true;
            for (int i = 0; i < marker.length; i++) {
                if (content[offset + i] != marker[i]) { matches = false; break; }
            }
            if (matches) return true;
        }
        return false;
    }

    private static ApiException unsupportedFileType(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE", message);
    }

    private record FileType(String canonicalContentType, Set<String> acceptedContentTypes, Signature signature) {}
    private enum Signature { NONE, PDF, ZIP }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File was not found"); }
}
