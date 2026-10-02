package local.interviewmirror.backend.resources;

import java.util.List;
import java.util.UUID;
import local.interviewmirror.backend.files.FileRepository;
import local.interviewmirror.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceService {
    private final ResourceRepository resources;
    private final FileRepository files;

    public ResourceService(ResourceRepository resources, FileRepository files) {
        this.resources = resources;
        this.files = files;
    }

    public List<DemoResource> list(UUID ownerId, String resourceType) {
        return resources.findAll(ownerId, resourceType);
    }

    public DemoResource get(UUID ownerId, UUID id) {
        return resources.findByIdAndOwner(id, ownerId).orElseThrow(ResourceService::notFound);
    }

    @Transactional
    public DemoResource create(UUID ownerId, ResourceRequests.Create body) {
        validateFileOwnership(ownerId, body.fileId());
        return resources.insert(UUID.randomUUID(), ownerId, body);
    }

    @Transactional
    public DemoResource update(UUID ownerId, UUID id, ResourceRequests.Update body) {
        validateFileOwnership(ownerId, body.fileId());
        return resources.update(id, ownerId, body).orElseThrow(ResourceService::notFound);
    }

    @Transactional
    public void delete(UUID ownerId, UUID id) {
        if (!resources.delete(id, ownerId)) throw notFound();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource was not found");
    }

    private void validateFileOwnership(UUID ownerId, UUID fileId) {
        if (fileId != null && files.findByIdAndOwner(fileId, ownerId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File was not found");
        }
    }
}
