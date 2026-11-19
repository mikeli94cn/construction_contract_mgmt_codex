package com.construction.contracts.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.construction.contracts.audit.application.AuditWriter;
import com.construction.contracts.identity.application.IdentityProblem;
import com.construction.contracts.identity.application.IdentityService;
import com.construction.contracts.identity.domain.Account;
import com.construction.contracts.identity.domain.OrganizationRole;
import com.construction.contracts.identity.application.AccountStore;
import com.construction.contracts.identity.application.MembershipStore;
import com.construction.contracts.identity.application.MembershipStore.Membership;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class IdentityServiceTest {
    private final AccountStore accounts = mock(AccountStore.class);
    private final MembershipStore store = mock(MembershipStore.class);
    private final AuditWriter audit = mock(AuditWriter.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final IdentityService service = new IdentityService(accounts, store, audit, passwords);

    @Test
    void revokedMembershipCannotReadDirectory() {
        when(store.member(10, 1)).thenReturn(membership(1, false, OrganizationRole.ORG_ADMIN));
        assertEquals(404, assertThrows(IdentityProblem.class, () -> service.members(1, 10, 0, 25)).status());
        verify(store, never()).members(anyLong(), anyInt(), anyInt());
    }

    @Test
    void memberCannotManageAnotherOrganization() {
        assertEquals(404, assertThrows(IdentityProblem.class, () -> service.members(1, 20, 0, 25)).status());
        verifyNoInteractions(accounts, audit);
    }

    @Test
    void activeNonAdminCannotReadMemberDirectory() {
        when(store.member(10, 1)).thenReturn(membership(1, true, OrganizationRole.MEMBER));
        assertEquals(403, assertThrows(IdentityProblem.class, () -> service.members(1, 10, 0, 25)).status());
    }

    @Test
    void administratorCannotRemoveOwnRole() {
        when(store.member(10, 1)).thenReturn(membership(1, true, OrganizationRole.ORG_ADMIN));
        assertEquals(409, assertThrows(IdentityProblem.class,
            () -> service.update(1, 10, 1, 0, true, Set.of(OrganizationRole.MEMBER))).status());
        verify(store, never()).updateMember(anyLong(), anyLong(), anyLong(), anyBoolean(), anySet());
    }

    @Test
    void staleChangeDoesNotCreateAuditEvent() {
        when(store.member(10, 1)).thenReturn(membership(1, true, OrganizationRole.ORG_ADMIN));
        when(store.member(10, 2)).thenReturn(membership(2, true, OrganizationRole.MEMBER));
        assertEquals(412, assertThrows(IdentityProblem.class,
            () -> service.update(1, 10, 2, 0, false, Set.of(OrganizationRole.MEMBER))).status());
        verifyNoInteractions(audit);
    }

    @Test
    void existingAccountMembershipDoesNotIssueAnActivationToken() {
        when(store.member(10, 1)).thenReturn(membership(1, true, OrganizationRole.ORG_ADMIN));
        Account account = mock(Account.class);
        when(account.id()).thenReturn(2L);
        when(accounts.findByEmail("member@example.test")).thenReturn(Optional.of(account));
        when(store.member(10, 2)).thenReturn(null, membership(2, true, OrganizationRole.MEMBER));
        var result = service.provision(1, 10, "MEMBER@example.test", "Existing", Set.of(OrganizationRole.MEMBER));
        assertNull(result.activationToken());
        verify(store, never()).createToken(anyString(), anyLong(), any());
        verifyNoInteractions(passwords);
    }

    @Test
    void activationRejectsExpiredOrUsedTokenWithoutChangingPassword() {
        when(store.consumeToken(anyString(), any(Instant.class))).thenReturn(null);
        assertEquals("invalid_activation", assertThrows(IdentityProblem.class,
            () -> service.activate("expired", "example test password")).code());
        verify(store).consumeToken(argThat(digest -> digest.matches("[0-9a-f]{64}")), any(Instant.class));
        verifyNoInteractions(accounts, passwords, audit);
    }

    private Membership membership(long userId, boolean enabled, OrganizationRole role) {
        return new Membership(10, "Test organization", userId, "member@example.test", "Member",
            "ACTIVE", enabled, 1, Set.of(role));
    }
}
