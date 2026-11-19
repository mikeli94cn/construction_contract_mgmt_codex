package com.construction.contracts.identity.application;

import java.util.List;
import java.util.Set;
import com.construction.contracts.identity.domain.OrganizationRole;

public interface OrganizationAccess {
    record MemberOption(long userId, String displayName, String email) {}

    Set<OrganizationRole> roles(long userId, long organizationId);

    Set<OrganizationRole> lockAndGetRoles(long userId, long organizationId);

    List<MemberOption> activeMembers(long organizationId);
}
