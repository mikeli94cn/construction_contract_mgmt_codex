package com.construction.contracts.projects.infrastructure;

import com.construction.contracts.projects.application.ProjectStore;
import com.construction.contracts.projects.domain.Project;
import com.construction.contracts.projects.domain.Project.Role;
import com.construction.contracts.projects.domain.Project.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProjectStore implements ProjectStore {
    private static final String SCOPE = """
        FROM projects p
        WHERE p.organization_id = :orgId AND (:admin OR EXISTS (
            SELECT 1 FROM project_memberships m
            WHERE m.organization_id = p.organization_id AND m.project_id = p.id AND m.user_id = :actorId
        ))
        """;

    private final JdbcClient jdbc;

    JdbcProjectStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Project> list(long orgId, long actorId, boolean administrator, int offset, int size) {
        return scoped("SELECT p.* " + SCOPE + " ORDER BY p.id LIMIT :size OFFSET :offset", orgId, actorId, administrator)
            .param("size", size).param("offset", offset).query(this::row).list();
    }

    @Override
    public long count(long orgId, long actorId, boolean administrator) {
        return scoped("SELECT COUNT(*) " + SCOPE, orgId, actorId, administrator).query(Long.class).single();
    }

    private JdbcClient.StatementSpec scoped(String sql, long orgId, long actorId, boolean administrator) {
        return jdbc.sql(sql).param("orgId", orgId).param("actorId", actorId).param("admin", administrator);
    }

    @Override
    public Optional<Project> find(long orgId, long projectId) {
        return jdbc.sql("SELECT * FROM projects WHERE organization_id = ? AND id = ?")
            .params(orgId, projectId).query(this::row).optional();
    }

    @Override
    public boolean codeExists(long orgId, String code, long exceptProjectId) {
        return jdbc.sql("SELECT id FROM projects WHERE organization_id = ? AND code = ? AND id <> ?")
            .params(orgId, code, exceptProjectId).query(Long.class).optional().isPresent();
    }

    @Override
    public Project create(long orgId, String code, String name) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO projects (organization_id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')")
            .params(orgId, code, name).update(key);
        return new Project(java.util.Objects.requireNonNull(key.getKey()).longValue(), code, name, Status.ACTIVE, 0);
    }

    @Override
    public boolean update(long orgId, Project project) {
        return jdbc.sql("""
            UPDATE projects SET code = ?, name = ?, status = ?, version = version + 1
            WHERE organization_id = ? AND id = ? AND version = ?
            """).params(project.code(), project.name(), project.status().name(), orgId, project.id(), project.version())
            .update() == 1;
    }

    @Override
    public Map<Long, Set<Role>> assignments(long orgId, long projectId) {
        return jdbc.sql("""
            SELECT user_id, role_code FROM project_memberships WHERE organization_id = ? AND project_id = ?
            """).params(orgId, projectId).query(rs -> {
                Map<Long, Set<Role>> result = new TreeMap<>();
                while (rs.next()) {
                    result.computeIfAbsent(rs.getLong("user_id"), id -> EnumSet.noneOf(Role.class))
                        .add(Role.valueOf(rs.getString("role_code")));
                }
                return result;
            });
    }

    @Override
    public Set<Role> roles(long orgId, long projectId, long actorId) {
        return Set.copyOf(jdbc.sql("""
            SELECT role_code FROM project_memberships WHERE organization_id = ? AND project_id = ? AND user_id = ?
            """).params(orgId, projectId, actorId).query((rs, i) -> Role.valueOf(rs.getString("role_code"))).list());
    }

    @Override
    public void replaceAssignments(long orgId, long projectId, Map<Long, Set<Role>> assignments) {
        jdbc.sql("DELETE FROM project_memberships WHERE organization_id = ? AND project_id = ?")
            .params(orgId, projectId).update();
        assignments.forEach((userId, roles) -> roles.forEach(role -> jdbc.sql("""
            INSERT INTO project_memberships (organization_id, project_id, user_id, role_code)
            VALUES (?, ?, ?, ?)
            """).params(orgId, projectId, userId, role.name()).update()));
    }

    private Project row(ResultSet rs, int index) throws SQLException {
        return new Project(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            Status.valueOf(rs.getString("status")), rs.getLong("version"));
    }
}
