package com.construction.contracts.identity.application;

import com.construction.contracts.audit.application.AuditWriter;
import com.construction.contracts.identity.domain.Account;
import com.construction.contracts.identity.domain.OrganizationRole;
import com.construction.contracts.identity.application.MembershipStore.Membership;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService implements OrganizationAccess {
    public record Profile(long userId, String email, String displayName, List<Membership> organizations) {}
    public record Members(List<Membership> items, long total) {}
    public record Provisioned(Membership membership, String activationToken) {}

    private final AccountStore accounts;
    private final MembershipStore store;
    private final AuditWriter audit;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();

    public IdentityService(AccountStore accounts, MembershipStore store,
                           AuditWriter audit, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.store = store;
        this.audit = audit;
        this.passwords = passwords;
    }

    @Transactional(readOnly = true)
    public Profile profile(long userId) {
        Account account = accounts.findById(userId).filter(Account::active)
            .orElseThrow(() -> new IdentityProblem(401, "account_inactive", "Sign in again"));
        return new Profile(account.id(), account.email(), account.displayName(), store.activeMemberships(userId));
    }

    @Transactional(readOnly = true)
    public void checkOrganization(long userId, long organizationId) {
        activeMembership(userId, organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<OrganizationRole> roles(long userId, long organizationId) {
        return activeMembership(userId, organizationId).roles();
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Set<OrganizationRole> lockAndGetRoles(long userId, long organizationId) {
        store.lock(organizationId);
        return activeMembership(userId, organizationId).roles();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberOption> activeMembers(long organizationId) {
        return store.activeMembers(organizationId);
    }

    @Transactional(readOnly = true)
    public Members members(long actorId, long organizationId, int offset, int size) {
        requireAdmin(actorId, organizationId);
        return new Members(store.members(organizationId, offset, size), store.count(organizationId));
    }

    @Transactional(readOnly = true)
    public Membership getMember(long actorId, long organizationId, long memberId) {
        requireAdmin(actorId, organizationId);
        Membership membership = store.member(organizationId, memberId);
        if (membership == null) {
            throw new IdentityProblem(404, "member_not_found", "Member unavailable");
        }
        return membership;
    }

    @Transactional
    public Provisioned provision(long actorId, long organizationId, String email, String name,
                                 Set<OrganizationRole> roles) {
        store.lock(organizationId);
        requireAdmin(actorId, organizationId);
        Account existing = accounts.findByEmail(Account.normalize(email)).orElse(null);
        Account account = existing == null ? accounts.saveAndFlush(new Account(email, name)) : existing;
        if (store.member(organizationId, account.id()) != null) {
            throw new IdentityProblem(409, "membership_exists", "This user is already a member");
        }
        store.addMember(organizationId, account.id(), roles);
        String token = existing == null ? createToken(account.id()) : null;
        record(organizationId, actorId, "MEMBER_ADDED", account.id(), "roles=" + roles);
        return new Provisioned(store.member(organizationId, account.id()), token);
    }

    @Transactional
    public Membership update(long actorId, long organizationId, long memberId,
                             long version, boolean enabled, Set<OrganizationRole> roles) {
        store.lock(organizationId);
        requireAdmin(actorId, organizationId);
        Membership current = store.member(organizationId, memberId);
        if (current == null) {
            throw new IdentityProblem(404, "member_not_found", "Member unavailable");
        }
        if (memberId == actorId && (!enabled || !roles.contains(OrganizationRole.ORG_ADMIN))) {
            throw new IdentityProblem(409, "self_admin_removal", "An administrator cannot remove their own access");
        }
        if (!store.updateMember(organizationId, memberId, version, enabled, roles)) {
            throw new IdentityProblem(412, "stale_version", "Reload the member before saving");
        }
        record(organizationId, actorId, "MEMBER_UPDATED", memberId,
            "enabled=" + current.enabled() + " -> " + enabled + "; roles=" + current.roles() + " -> " + roles);
        return store.member(organizationId, memberId);
    }

    @Transactional
    public void activate(String token, String password) {
        validatePassword(password);
        Long userId = store.consumeToken(digest(token), Instant.now());
        if (userId == null) {
            throw invalidToken();
        }
        Account account = accounts.findById(userId).filter(Account::pending).orElseThrow(this::invalidToken);
        account.activate(passwords.encode(password));
        audit.record(null, userId, "ACCOUNT_ACTIVATED", "USER", userId, "Account activated");
    }

    @Transactional
    public void bootstrap(String code, String name, String email, String password) {
        if (!code.matches("[A-Za-z0-9_-]{1,40}") || name.isBlank() || name.length() > 160
                || email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+")) {
            throw new IllegalArgumentException("Bootstrap requires a valid organization code, name and administrator email");
        }
        if (store.organizationId(code) != null) {
            return;
        }
        validatePassword(password);
        Account admin = new Account(email, "Organization administrator");
        admin.activate(passwords.encode(password));
        accounts.saveAndFlush(admin);
        long organizationId = store.createOrganization(code, name);
        store.addMember(organizationId, admin.id(), Set.of(OrganizationRole.ORG_ADMIN));
        record(organizationId, admin.id(), "ORG_BOOTSTRAPPED", admin.id(), "Initial administrator created");
    }

    private String createToken(long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.createToken(digest(token), userId, Instant.now().plus(Duration.ofDays(1)));
        return token;
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void validatePassword(String password) {
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IdentityProblem(400, "invalid_password", "Use at least 12 characters and at most 72 UTF-8 bytes");
        }
    }

    private IdentityProblem invalidToken() {
        return new IdentityProblem(400, "invalid_activation", "Activation link is invalid or expired");
    }

    private Membership activeMembership(long userId, long organizationId) {
        Membership membership = store.member(organizationId, userId);
        if (membership == null || !membership.enabled() || !"ACTIVE".equals(membership.accountStatus())) {
            throw new IdentityProblem(404, "organization_unavailable", "Organization unavailable");
        }
        return membership;
    }

    private void requireAdmin(long actorId, long organizationId) {
        if (!activeMembership(actorId, organizationId).roles().contains(OrganizationRole.ORG_ADMIN)) {
            throw new IdentityProblem(403, "admin_required", "Organization administrator permission required");
        }
    }

    private void record(long organizationId, long actorId, String action, long userId, String summary) {
        audit.record(organizationId, actorId, action, "ORGANIZATION_MEMBER", userId, summary);
    }
}
