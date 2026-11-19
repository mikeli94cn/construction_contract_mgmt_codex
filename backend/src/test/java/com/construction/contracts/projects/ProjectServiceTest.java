package com.construction.contracts.projects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.construction.contracts.audit.application.AuditWriter;
import com.construction.contracts.identity.application.OrganizationAccess;
import com.construction.contracts.identity.application.OrganizationAccess.MemberOption;
import com.construction.contracts.identity.domain.OrganizationRole;
import com.construction.contracts.projects.application.ProjectProblem;
import com.construction.contracts.projects.application.ProjectService;
import com.construction.contracts.projects.application.ProjectStore;
import com.construction.contracts.projects.domain.Project;
import com.construction.contracts.projects.domain.Project.Role;
import com.construction.contracts.projects.domain.Project.Status;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectServiceTest {
    private static final long ORG = 3;
    private static final long ACTOR = 5;
    private static final long PROJECT = 7;
    private static final Project ACTIVE = new Project(PROJECT, "SITE-01", "Site works", Status.ACTIVE, 2);

    private final ProjectStore projects = mock(ProjectStore.class);
    private final OrganizationAccess organizations = mock(OrganizationAccess.class);
    private final AuditWriter audit = mock(AuditWriter.class);
    private final ProjectService service = new ProjectService(projects, organizations, audit);

    @Test
    void nonAdminDirectoryQueriesAreScopedToActorAndOrganization() {
        when(organizations.roles(ACTOR, ORG)).thenReturn(Set.of(OrganizationRole.MEMBER));
        when(projects.list(ORG, ACTOR, false, 25, 25)).thenReturn(List.of(ACTIVE));
        when(projects.count(ORG, ACTOR, false)).thenReturn(26L);

        var page = service.list(ORG, ACTOR, 25, 25);

        assertEquals(List.of(ACTIVE), page.items());
        assertEquals(26, page.total());
        assertFalse(page.canCreate());
    }

    @Test
    void unassignedUserCannotReadProject() {
        when(organizations.roles(ACTOR, ORG)).thenReturn(Set.of(OrganizationRole.MEMBER));
        when(projects.find(ORG, PROJECT)).thenReturn(Optional.of(ACTIVE));

        assertEquals(404, assertThrows(ProjectProblem.class, () -> service.get(ORG, ACTOR, PROJECT)).status());
    }

    @Test
    void administratorLookupStillRequiresProjectInOrganization() {
        when(organizations.roles(ACTOR, ORG)).thenReturn(Set.of(OrganizationRole.ORG_ADMIN));
        assertEquals(404, assertThrows(ProjectProblem.class, () -> service.get(ORG, ACTOR, PROJECT)).status());
        verify(projects).find(ORG, PROJECT);
    }

    @Test
    void viewerCannotManageAssignments() {
        when(organizations.roles(ACTOR, ORG)).thenReturn(Set.of(OrganizationRole.MEMBER));
        when(projects.find(ORG, PROJECT)).thenReturn(Optional.of(ACTIVE));
        when(projects.roles(ORG, PROJECT, ACTOR)).thenReturn(Set.of(Role.PROJECT_VIEWER));

        assertEquals(403, assertThrows(ProjectProblem.class, () -> service.members(ORG, ACTOR, PROJECT)).status());
    }

    @Test
    void duplicateNormalizedCodeIsRejected() {
        when(organizations.lockAndGetRoles(ACTOR, ORG)).thenReturn(Set.of(OrganizationRole.ORG_ADMIN));
        when(projects.codeExists(ORG, "SITE-01", 0)).thenReturn(true);

        assertEquals(409, assertThrows(ProjectProblem.class, () -> service.create(ORG, ACTOR, "site-01", "Site")).status());
        verifyNoInteractions(audit);
    }

    @Test
    void staleProjectVersionCannotReplaceAssignments() {
        when(organizations.lockAndGetRoles(ACTOR, ORG)).thenReturn(Set.of(OrganizationRole.ORG_ADMIN));
        when(projects.find(ORG, PROJECT)).thenReturn(Optional.of(ACTIVE));

        assertEquals(412, assertThrows(ProjectProblem.class,
            () -> service.replaceMembers(ORG, ACTOR, PROJECT, 1, Map.of())).status());
        verify(projects, never()).replaceAssignments(anyLong(), anyLong(), anyMap());
    }

    @Test
    void crossOrganizationOrInactiveUserCannotReceiveNewRoles() {
        when(organizations.lockAndGetRoles(ACTOR, ORG)).thenReturn(Set.of(OrganizationRole.ORG_ADMIN));
        when(projects.find(ORG, PROJECT)).thenReturn(Optional.of(ACTIVE));

        assertEquals("ineligible_project_member", assertThrows(ProjectProblem.class,
            () -> service.replaceMembers(ORG, ACTOR, PROJECT, 2, Map.of(99L, Set.of(Role.PROJECT_VIEWER)))).code());
        verify(projects, never()).replaceAssignments(anyLong(), anyLong(), anyMap());
    }

    @Test
    void replacementBumpsVersionAndAuditsChangesWithoutRemovingUnchangedInactiveAssignments() {
        var dormantRoles = Set.of(Role.PROJECT_VIEWER);
        var next = Map.of(9L, Set.of(Role.CONTRACT_EDITOR), 11L, dormantRoles);
        when(organizations.lockAndGetRoles(ACTOR, ORG)).thenReturn(Set.of(OrganizationRole.ORG_ADMIN));
        when(organizations.activeMembers(ORG)).thenReturn(List.of(new MemberOption(9, "Member", "member@example.test")));
        when(projects.find(ORG, PROJECT)).thenReturn(Optional.of(ACTIVE));
        when(projects.assignments(ORG, PROJECT)).thenReturn(Map.of(11L, dormantRoles));
        when(projects.update(ORG, ACTIVE)).thenReturn(true);

        assertEquals(3, service.replaceMembers(ORG, ACTOR, PROJECT, 2, next).project().version());

        var order = inOrder(projects, audit);
        order.verify(projects).update(ORG, ACTIVE);
        order.verify(projects).replaceAssignments(ORG, PROJECT, next);
        order.verify(audit).record(ORG, ACTOR, "PROJECT_MEMBER_ROLES_CHANGED", "PROJECT", PROJECT,
            "userId=9, roles=[] -> [CONTRACT_EDITOR]");
        verifyNoMoreInteractions(audit);
    }

    @Test
    void managerCannotArchiveProjectOrRemoveOwnManagerRole() {
        when(organizations.lockAndGetRoles(ACTOR, ORG)).thenReturn(Set.of(OrganizationRole.MEMBER));
        when(projects.find(ORG, PROJECT)).thenReturn(Optional.of(ACTIVE));
        when(projects.roles(ORG, PROJECT, ACTOR)).thenReturn(Set.of(Role.PROJECT_MANAGER));

        assertEquals(403, assertThrows(ProjectProblem.class,
            () -> service.update(ORG, ACTOR, PROJECT, 2, null, null, Status.ARCHIVED)).status());
        assertEquals("self_manager_removal", assertThrows(ProjectProblem.class,
            () -> service.replaceMembers(ORG, ACTOR, PROJECT, 2, Map.of())).code());
        verifyNoInteractions(audit);
    }
}
