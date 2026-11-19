package com.construction.contracts.identity.api;

import com.construction.contracts.identity.application.IdentityProblem;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;

public final class RequestIdentity {
    static final String ACTIVE_ORGANIZATION = RequestIdentity.class.getName() + ".organization";

    private RequestIdentity() {}

    public static long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }

    public static long selectedOrganization(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(ACTIVE_ORGANIZATION) instanceof Long organizationId) {
            return organizationId;
        }
        throw new IdentityProblem(404, "organization_unavailable", "Select an active organization");
    }

    static void requireSelected(HttpServletRequest request, long organizationId) {
        if (request.getSession(false) == null
                || !Long.valueOf(organizationId).equals(request.getSession(false).getAttribute(ACTIVE_ORGANIZATION))) {
            throw new IdentityProblem(404, "organization_unavailable", "Organization unavailable");
        }
    }
}
