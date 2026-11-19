package com.construction.contracts.identity.infrastructure;

import com.construction.contracts.identity.domain.OrganizationRole;
import com.construction.contracts.identity.application.MembershipStore;
import com.construction.contracts.identity.application.OrganizationAccess.MemberOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OrganizationStore implements MembershipStore {

    private static final String MEMBERS = """
        SELECT o.id organization_id, o.name organization_name, u.id user_id, u.email,
               u.display_name, u.status account_status, m.status, m.version
        FROM organization_memberships m
        JOIN organizations o ON o.id = m.organization_id
        JOIN users u ON u.id = m.user_id
        WHERE o.status = 'ACTIVE'
        """;
    private final JdbcClient jdbc;

    public OrganizationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Membership> activeMemberships(long userId) {
        return members(MEMBERS + " AND m.user_id = ? AND m.status = 'ACTIVE' AND u.status = 'ACTIVE' ORDER BY o.id", userId);
    }

    public List<Membership> members(long organizationId, int offset, int size) {
        return members(MEMBERS + " AND o.id = ? ORDER BY u.id LIMIT ? OFFSET ?", organizationId, size, offset);
    }

    @Override
    public List<MemberOption> activeMembers(long organizationId) {
        return jdbc.sql(MEMBERS + """
            AND o.id = ? AND m.status = 'ACTIVE' AND u.status = 'ACTIVE'
            ORDER BY u.display_name, u.id
            """).param(organizationId).query((rs, row) -> new MemberOption(
                rs.getLong("user_id"), rs.getString("display_name"), rs.getString("email"))).list();
    }

    public long count(long organizationId) {
        return jdbc.sql("SELECT COUNT(*) FROM organization_memberships WHERE organization_id = ?")
            .param(organizationId).query(Long.class).single();
    }

    public Membership member(long organizationId, long userId) {
        return members(MEMBERS + " AND o.id = ? AND u.id = ?", organizationId, userId)
            .stream().findFirst().orElse(null);
    }

    private List<Membership> members(String sql, Object... parameters) {
        List<Membership> rows = jdbc.sql(sql).params(parameters).query((rs, row) ->
            new Membership(rs.getLong("organization_id"), rs.getString("organization_name"),
                rs.getLong("user_id"), rs.getString("email"), rs.getString("display_name"),
                rs.getString("account_status"), "ACTIVE".equals(rs.getString("status")),
                rs.getLong("version"), Set.of())).list();
        return rows.stream().map(m -> new Membership(m.organizationId(), m.organizationName(),
            m.userId(), m.email(), m.displayName(), m.accountStatus(), m.enabled(), m.version(),
            roles(m.organizationId(), m.userId()))).toList();
    }

    private Set<OrganizationRole> roles(long organizationId, long userId) {
        return new TreeSet<>(jdbc.sql("""
            SELECT role_code FROM organization_membership_roles WHERE organization_id = ? AND user_id = ?
            """).params(organizationId, userId).query(String.class).list().stream()
            .map(OrganizationRole::valueOf).toList());
    }

    public void lock(long organizationId) {
        jdbc.sql("SELECT id FROM organizations WHERE id = ? FOR UPDATE")
            .param(organizationId).query(Long.class).optional();
    }

    public void addMember(long organizationId, long userId, Set<OrganizationRole> roles) {
        jdbc.sql("""
            INSERT INTO organization_memberships (organization_id, user_id, status) VALUES (?, ?, 'ACTIVE')
            """).params(organizationId, userId).update();
        replaceRoles(organizationId, userId, roles);
    }

    public boolean updateMember(long organizationId, long userId, long version,
                                boolean enabled, Set<OrganizationRole> roles) {
        int changed = jdbc.sql("""
            UPDATE organization_memberships SET status = ?, version = version + 1
            WHERE organization_id = ? AND user_id = ? AND version = ?
            """).params(enabled ? "ACTIVE" : "DISABLED", organizationId, userId, version).update();
        if (changed == 0) {
            return false;
        }
        replaceRoles(organizationId, userId, roles);
        return true;
    }

    private void replaceRoles(long organizationId, long userId, Set<OrganizationRole> roles) {
        jdbc.sql("DELETE FROM organization_membership_roles WHERE organization_id = ? AND user_id = ?")
            .params(organizationId, userId).update();
        for (OrganizationRole role : roles) {
            jdbc.sql("""
                INSERT INTO organization_membership_roles (organization_id, user_id, role_code) VALUES (?, ?, ?)
                """).params(organizationId, userId, role.name()).update();
        }
    }

    public void createToken(String digest, long userId, Instant expiresAt) {
        jdbc.sql("INSERT INTO account_activation_tokens (token_digest, user_id, expires_at) VALUES (?, ?, ?)")
            .params(digest, userId, LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)).update();
    }

    public Long consumeToken(String digest, Instant now) {
        LocalDateTime utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        int consumed = jdbc.sql("""
            UPDATE account_activation_tokens SET consumed_at = ?
            WHERE token_digest = ? AND consumed_at IS NULL AND expires_at > ?
            """).params(utcNow, digest, utcNow).update();
        if (consumed == 0) {
            return null;
        }
        return jdbc.sql("SELECT user_id FROM account_activation_tokens WHERE token_digest = ?")
            .param(digest).query(Long.class).single();
    }

    public long createOrganization(String code, String name) {
        jdbc.sql("INSERT INTO organizations (code, name, status) VALUES (?, ?, 'ACTIVE')")
            .params(code, name).update();
        return organizationId(code);
    }

    public Long organizationId(String code) {
        return jdbc.sql("SELECT id FROM organizations WHERE code = ?")
            .param(code).query(Long.class).optional().orElse(null);
    }
}
