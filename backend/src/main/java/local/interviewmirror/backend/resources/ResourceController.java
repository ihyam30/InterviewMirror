package local.interviewmirror.backend.resources;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiResponse;
import local.interviewmirror.backend.security.AccountPrincipal;
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

@RestController
@RequestMapping("/api/v1/resources")
public class ResourceController {
    private final ResourceService resources;

    public ResourceController(ResourceService resources) {
        this.resources = resources;
    }

    @GetMapping
    ApiResponse<List<DemoResourceView>> list(Authentication authentication, @RequestParam(required = false) String type) {
        return ApiResponse.of(resources.list(principal(authentication).id(), type).stream().map(DemoResourceView::from).toList());
    }

    @GetMapping("/{id}")
    ApiResponse<DemoResourceView> get(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.of(DemoResourceView.from(resources.get(principal(authentication).id(), id)));
    }

    @PostMapping
    ApiResponse<DemoResourceView> create(Authentication authentication, @Valid @RequestBody ResourceRequests.Create body) {
        return ApiResponse.of(DemoResourceView.from(resources.create(principal(authentication).id(), body)));
    }

    @PutMapping("/{id}")
    ApiResponse<DemoResourceView> update(Authentication authentication, @PathVariable UUID id,
            @Valid @RequestBody ResourceRequests.Update body) {
        return ApiResponse.of(DemoResourceView.from(resources.update(principal(authentication).id(), id, body)));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(Authentication authentication, @PathVariable UUID id) {
        resources.delete(principal(authentication).id(), id);
        return ApiResponse.of(null);
    }

    private static AccountPrincipal principal(Authentication authentication) {
        return (AccountPrincipal) authentication.getPrincipal();
    }
}
