package com.construction.contracts.identity.api;

import com.construction.contracts.identity.application.IdentityProblem;
import com.construction.contracts.identity.application.IdentityService;
import com.construction.contracts.identity.domain.OrganizationRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    record Login(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}
    record Activation(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 72) String password) {}
    record OrganizationSelection(@NotBlank @Size(max = 19) @Pattern(regexp = "[1-9][0-9]*") String organizationId) {}
    record Organization(String id, String name, Set<OrganizationRole> roles) {}
    record Profile(String id, String email, String displayName, String activeOrganizationId,
                   List<Organization> organizations) {}
    record Csrf(String headerName, String token) {}

    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;
    private final IdentityService identity;

    AuthController(AuthenticationManager authentication, SecurityContextRepository contexts,
                   CsrfTokenRepository csrfTokens, IdentityService identity) {
        this.authentication = authentication;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
        this.identity = identity;
    }

    @GetMapping("/csrf")
    ResponseEntity<Csrf> csrf(CsrfToken token) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(new Csrf(token.getHeaderName(), token.getToken()));
    }

    @PostMapping("/login")
    ResponseEntity<Void> login(@Valid @RequestBody Login input, HttpServletRequest request,
                               HttpServletResponse response) {
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IdentityProblem(401, "invalid_credentials", "Email or password is incorrect");
        }
        var authenticated = authentication.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(input.email(), input.password()));
        var profile = identity.profile(Long.parseLong(authenticated.getName()));
        if (profile.organizations().isEmpty()) {
            throw new IdentityProblem(403, "no_active_membership", "No active organization membership");
        }
        var session = request.getSession();
        request.changeSessionId();
        session.removeAttribute(RequestIdentity.ACTIVE_ORGANIZATION);
        if (profile.organizations().size() == 1) {
            session.setAttribute(RequestIdentity.ACTIVE_ORGANIZATION, profile.organizations().getFirst().organizationId());
        }
        csrfTokens.saveToken(null, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    Profile profile(Principal principal, HttpServletRequest request) {
        var profile = identity.profile(RequestIdentity.userId(principal));
        Object selected = request.getSession().getAttribute(RequestIdentity.ACTIVE_ORGANIZATION);
        String active = profile.organizations().stream().map(org -> org.organizationId())
            .filter(id -> Long.valueOf(id).equals(selected)).map(id -> Long.toString(id)).findFirst().orElse(null);
        return new Profile(Long.toString(profile.userId()), profile.email(), profile.displayName(), active,
            profile.organizations().stream().map(m -> new Organization(Long.toString(m.organizationId()),
                m.organizationName(), m.roles())).toList());
    }

    @PutMapping("/organization")
    ResponseEntity<Void> select(@Valid @RequestBody OrganizationSelection input, Principal principal,
                                HttpServletRequest request) {
        long organizationId;
        try {
            organizationId = Long.parseLong(input.organizationId());
        } catch (NumberFormatException failure) {
            throw new IdentityProblem(400, "invalid_organization", "Invalid organization ID");
        }
        identity.checkOrganization(RequestIdentity.userId(principal), organizationId);
        request.getSession().setAttribute(RequestIdentity.ACTIVE_ORGANIZATION, organizationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/activation")
    ResponseEntity<Void> activate(@Valid @RequestBody Activation input) {
        identity.activate(input.token(), input.password());
        return ResponseEntity.noContent().build();
    }
}
