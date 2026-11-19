package com.construction.contracts.projects.application;

import com.construction.contracts.audit.application.AuditWriter;
import com.construction.contracts.identity.application.OrganizationAccess;
import com.construction.contracts.identity.application.OrganizationAccess.MemberOption;
import com.construction.contracts.identity.domain.OrganizationRole;
import com.construction.contracts.projects.domain.Project;
import com.construction.contracts.projects.domain.Project.Role;
import com.construction.contracts.projects.domain.Project.Status;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    public record Page(List<Project> items, long total, boolean canCreate) {}
    public record Detail(Project project, boolean canEdit, boolean canManageMembers) {}
    public record Members(Project project, Map<Long, Set<Role>> assignments, List<MemberOption> eligible) {}

    private final ProjectStore projects;
    private final OrganizationAccess organizations;
    private final AuditWriter audit;

    public ProjectService(ProjectStore projects, OrganizationAccess organizations, AuditWriter audit) {
        this.projects = projects;
        this.organizations = organizations;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page list(long orgId, long actorId, int offset, int size) {
        boolean admin = isAdmin(organizations.roles(actorId, orgId));
        return new Page(projects.list(orgId, actorId, admin, offset, size), projects.count(orgId, actorId, admin), admin);
    }

    @Transactional(readOnly = true)
    public Detail get(long orgId, long actorId, long projectId) {
        return access(orgId, actorId, projectId, isAdmin(organizations.roles(actorId, orgId)));
    }

    @Transactional(readOnly = true)
    public void requireContractAuthor(long orgId, long actorId, long projectId) {
        organizations.roles(actorId, orgId);
        Project project = projects.find(orgId, projectId).orElseThrow(ProjectService::notFound);
        Set<Role> roles = projects.roles(orgId, projectId, actorId);
        if (roles.isEmpty()) {
            throw notFound();
        }
        if (!roles.contains(Role.PROJECT_MANAGER) && !roles.contains(Role.CONTRACT_EDITOR)) {
            throw new ProjectProblem(403, "contract_author_required", "Project contract author permission required");
        }
        if (project.status() != Status.ACTIVE) {
            throw new ProjectProblem(409, "project_archived", "Project is archived");
        }
    }

    @Transactional
    public Detail create(long orgId, long actorId, String code, String name) {
        if (!isAdmin(organizations.lockAndGetRoles(actorId, orgId))) {
            throw new ProjectProblem(403, "project_creation_denied", "Organization administrator permission required");
        }
        String normalized = Project.normalizeCode(code);
        verifyCode(orgId, normalized, 0);
        Project project = projects.create(orgId, normalized, name.strip());
        record(orgId, actorId, project.id(), "PROJECT_CREATED", "Project created");
        return new Detail(project, true, true);
    }

    @Transactional
    public Detail update(long orgId, long actorId, long projectId, long version, String code, String name, Status status) {
        boolean admin = isAdmin(organizations.lockAndGetRoles(actorId, orgId));
        Detail detail = access(orgId, actorId, projectId, admin);
        require(detail.canEdit());
        Project current = detail.project();
        checkVersion(current, version);
        Status nextStatus = status == null ? current.status() : status;
        if (!admin && nextStatus != current.status()) {
            throw new ProjectProblem(403, "project_status_denied", "Only organization administrators can change project status");
        }
        String normalized = code == null ? current.code() : Project.normalizeCode(code);
        verifyCode(orgId, normalized, projectId);
        String nextName = name == null ? current.name() : name.strip();
        save(orgId, new Project(projectId, normalized, nextName, nextStatus, version));
        record(orgId, actorId, projectId, "PROJECT_UPDATED",
            "code=" + current.code() + " -> " + normalized + ", status=" + current.status() + " -> " + nextStatus
                + ", nameChanged=" + !current.name().equals(nextName));
        return access(orgId, actorId, projectId, admin);
    }

    @Transactional(readOnly = true)
    public Members members(long orgId, long actorId, long projectId) {
        Detail detail = access(orgId, actorId, projectId, isAdmin(organizations.roles(actorId, orgId)));
        require(detail.canManageMembers());
        return membershipSnapshot(orgId, detail.project());
    }

    @Transactional
    public Members replaceMembers(long orgId, long actorId, long projectId, long version, Map<Long, Set<Role>> assignments) {
        boolean admin = isAdmin(organizations.lockAndGetRoles(actorId, orgId));
        Detail detail = access(orgId, actorId, projectId, admin);
        require(detail.canManageMembers());
        Project current = detail.project();
        checkVersion(current, version);
        List<MemberOption> eligible = organizations.activeMembers(orgId);
        Set<Long> eligibleIds = eligible.stream().map(MemberOption::userId).collect(Collectors.toSet());
        Map<Long, Set<Role>> previous = projects.assignments(orgId, projectId);
        boolean invalid = assignments.entrySet().stream().anyMatch(entry -> !eligibleIds.contains(entry.getKey())
            && !entry.getValue().equals(previous.get(entry.getKey())));
        if (invalid) {
            throw new ProjectProblem(400, "ineligible_project_member", "Assign only active members of this organization");
        }
        if (!admin && !assignments.getOrDefault(actorId, Set.of()).contains(Role.PROJECT_MANAGER)) {
            throw new ProjectProblem(409, "self_manager_removal", "A project manager cannot remove their own manager role");
        }
        save(orgId, current);
        projects.replaceAssignments(orgId, projectId, assignments);
        var changedIds = new java.util.TreeSet<>(previous.keySet());
        changedIds.addAll(assignments.keySet());
        for (long userId : changedIds) {
            Set<Role> oldRoles = previous.getOrDefault(userId, Set.of());
            Set<Role> newRoles = assignments.getOrDefault(userId, Set.of());
            if (!oldRoles.equals(newRoles)) {
                record(orgId, actorId, projectId, "PROJECT_MEMBER_ROLES_CHANGED",
                    "userId=" + userId + ", roles=" + oldRoles + " -> " + newRoles);
            }
        }
        var updated = new Project(current.id(), current.code(), current.name(), current.status(), version + 1);
        return new Members(updated, assignments, eligible);
    }

    private Members membershipSnapshot(long orgId, Project project) {
        return new Members(project, projects.assignments(orgId, project.id()), organizations.activeMembers(orgId));
    }

    private Detail access(long orgId, long actorId, long projectId, boolean admin) {
        Project project = projects.find(orgId, projectId).orElseThrow(ProjectService::notFound);
        Set<Role> roles = admin ? Set.of() : projects.roles(orgId, projectId, actorId);
        if (!admin && roles.isEmpty()) {
            throw notFound();
        }
        boolean manage = admin || roles.contains(Role.PROJECT_MANAGER);
        return new Detail(project, manage, manage);
    }

    private boolean isAdmin(Set<OrganizationRole> roles) {
        return roles.contains(OrganizationRole.ORG_ADMIN);
    }

    private static ProjectProblem notFound() {
        return new ProjectProblem(404, "project_unavailable", "Project unavailable");
    }

    private void require(boolean allowed) {
        if (!allowed) {
            throw new ProjectProblem(403, "project_management_denied", "Project manager permission required");
        }
    }

    private void verifyCode(long orgId, String code, long exceptProjectId) {
        if (projects.codeExists(orgId, code, exceptProjectId)) {
            throw new ProjectProblem(409, "project_code_exists", "This project code is already in use");
        }
    }

    private void checkVersion(Project current, long expected) {
        if (current.version() != expected) {
            throw new ProjectProblem(412, "stale_version", "Reload the project before saving");
        }
    }

    private void save(long orgId, Project project) {
        if (!projects.update(orgId, project)) {
            throw new ProjectProblem(412, "stale_version", "Reload the project before saving");
        }
    }

    private void record(long orgId, long actorId, long projectId, String action, String summary) {
        audit.record(orgId, actorId, action, "PROJECT", projectId, summary);
    }
}
