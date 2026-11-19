package com.construction.contracts.identity.application;

import com.construction.contracts.identity.domain.OrganizationRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface MembershipStore {
    record Membership(long organizationId, String organizationName,
                      long userId, String email, String displayName,
                      String accountStatus, boolean enabled, long version,
                      Set<OrganizationRole> roles) {}

    List<Membership> activeMemberships(long userId);

    List<Membership> members(long organizationId, int offset, int size);

    List<OrganizationAccess.MemberOption> activeMembers(long organizationId);

    long count(long organizationId);

    Membership member(long organizationId, long userId);

    void lock(long organizationId);

    void addMember(long organizationId, long userId, Set<OrganizationRole> roles);

    boolean updateMember(long organizationId, long userId, long version, boolean enabled, Set<OrganizationRole> roles);

    void createToken(String digest, long userId, Instant expiresAt);

    Long consumeToken(String digest, Instant now);

    long createOrganization(String code, String name);

    Long organizationId(String code);
}
