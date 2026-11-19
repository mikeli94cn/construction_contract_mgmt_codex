CREATE TABLE projects (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY project_organization_id (organization_id, id),
    UNIQUE KEY project_code (organization_id, code),
    FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_memberships (
    organization_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_code VARCHAR(24) NOT NULL CHECK (role_code IN ('PROJECT_MANAGER', 'CONTRACT_EDITOR', 'PROJECT_VIEWER')),
    PRIMARY KEY (organization_id, project_id, user_id, role_code),
    KEY project_membership_user (organization_id, user_id, project_id),
    FOREIGN KEY (organization_id, project_id) REFERENCES projects(organization_id, id),
    FOREIGN KEY (organization_id, user_id) REFERENCES organization_memberships(organization_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
