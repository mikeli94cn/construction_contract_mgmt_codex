package com.construction.contracts.identity.api;

import com.construction.contracts.identity.application.IdentityProblem;
import com.construction.contracts.identity.application.IdentityService;
import com.construction.contracts.identity.domain.OrganizationRole;
import com.construction.contracts.identity.application.MembershipStore.Membership;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/members")
class MembershipController {
    record Provision(@NotBlank @Email @Size(max = 254) String email,
                     @NotBlank @Size(max = 160) String displayName,
                     @NotEmpty Set<@NotNull OrganizationRole> roles) {}
    record Update(@NotNull Boolean enabled, @NotEmpty Set<@NotNull OrganizationRole> roles) {}
    record Member(String userId, String email, String displayName, String accountStatus, boolean enabled,
                  Set<OrganizationRole> roles, String version) {}
    record Page(List<Member> items, int page, int size, long total) {}
    record Created(Member member, String activationToken) {}

    private final IdentityService identity;

    MembershipController(IdentityService identity) {
        this.identity = identity;
    }

    @GetMapping
    Page list(@PathVariable long orgId, @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
              @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
              Principal principal, HttpServletRequest request) {
        RequestIdentity.requireSelected(request, orgId);
        var members = identity.members(RequestIdentity.userId(principal), orgId, page * size, size);
        return new Page(members.items().stream().map(MembershipController::dto).toList(), page, size, members.total());
    }

    @GetMapping("/{userId}")
    ResponseEntity<Member> get(@PathVariable long orgId, @PathVariable long userId, Principal principal,
                               HttpServletRequest request) {
        RequestIdentity.requireSelected(request, orgId);
        var member = identity.getMember(RequestIdentity.userId(principal), orgId, userId);
        return ResponseEntity.ok().eTag(etag(member.version())).body(dto(member));
    }

    @PostMapping
    ResponseEntity<Created> provision(@PathVariable long orgId, @Valid @RequestBody Provision input,
                                      Principal principal, HttpServletRequest request) {
        RequestIdentity.requireSelected(request, orgId);
        var created = identity.provision(RequestIdentity.userId(principal), orgId, input.email(),
            input.displayName(), input.roles());
        var member = created.membership();
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + orgId + "/members/" + member.userId()))
            .header("Cache-Control", "no-store").eTag(etag(member.version()))
            .body(new Created(dto(member), created.activationToken()));
    }

    @PatchMapping("/{userId}")
    ResponseEntity<Member> update(@PathVariable long orgId, @PathVariable long userId,
                                  @RequestHeader(value = "If-Match", required = false) String expected,
                                  @Valid @RequestBody Update input, Principal principal, HttpServletRequest request) {
        RequestIdentity.requireSelected(request, orgId);
        long version = parseVersion(expected);
        var member = identity.update(RequestIdentity.userId(principal), orgId, userId, version, input.enabled(),
            input.roles());
        return ResponseEntity.ok().eTag(etag(member.version())).body(dto(member));
    }

    private static long parseVersion(String expected) {
        if (expected == null) {
            throw new IdentityProblem(428, "version_required", "If-Match is required");
        }
        try {
            if (expected.matches("\"[0-9]+\"")) {
                return Long.parseLong(expected.substring(1, expected.length() - 1));
            }
        } catch (NumberFormatException ignored) {
            // Out-of-range versions cannot match.
        }
        throw new IdentityProblem(412, "stale_version", "Reload the member before saving");
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }

    private static Member dto(Membership m) {
        return new Member(Long.toString(m.userId()), m.email(), m.displayName(), m.accountStatus(), m.enabled(),
            m.roles(), Long.toString(m.version()));
    }
}
