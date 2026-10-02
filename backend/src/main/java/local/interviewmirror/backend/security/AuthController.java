package local.interviewmirror.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    public AuthController(AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @GetMapping("/csrf")
    ApiResponse<CsrfPayload> csrf(CsrfToken csrfToken) {
        return ApiResponse.of(new CsrfPayload(csrfToken.getToken()));
    }

    @PostMapping("/login")
    ResponseEntity<ApiResponse<CurrentUserResponse>> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.identifier(), body.password()));
        } catch (AuthenticationException failure) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Username or password is incorrect");
        }

        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        csrfTokenRepository.saveToken(null, request, response);
        return ResponseEntity.ok(ApiResponse.of(toResponse((AccountPrincipal) authentication.getPrincipal())));
    }

    @GetMapping("/me")
    ApiResponse<CurrentUserResponse> me(Authentication authentication) {
        return ApiResponse.of(toResponse((AccountPrincipal) authentication.getPrincipal()));
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        csrfTokenRepository.saveToken(null, request, response);
        return ApiResponse.of(null);
    }

    private CurrentUserResponse toResponse(AccountPrincipal principal) {
        return new CurrentUserResponse(principal.id(), principal.getUsername(), principal.displayName());
    }

    public record LoginRequest(@NotBlank @Size(max = 160) String identifier,
                               @NotBlank @Size(max = 200) String password) {}
    public record CsrfPayload(String token) {}
    public record CurrentUserResponse(java.util.UUID id, String username, String displayName) {}
}
