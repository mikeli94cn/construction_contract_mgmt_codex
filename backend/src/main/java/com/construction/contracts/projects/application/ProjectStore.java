package com.construction.contracts.projects.application;

import com.construction.contracts.projects.domain.Project;
import com.construction.contracts.projects.domain.Project.Role;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface ProjectStore {
    List<Project> list(long orgId, long actorId, boolean administrator, int offset, int size);
    long count(long orgId, long actorId, boolean administrator);
    Optional<Project> find(long orgId, long projectId);
    boolean codeExists(long orgId, String code, long exceptProjectId);
    Project create(long orgId, String code, String name);
    boolean update(long orgId, Project updated);
    Map<Long, Set<Role>> assignments(long orgId, long projectId);
    Set<Role> roles(long orgId, long projectId, long actorId);
    void replaceAssignments(long orgId, long projectId, Map<Long, Set<Role>> assignments);
}
