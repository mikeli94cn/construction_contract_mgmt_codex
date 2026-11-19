CREATE TABLE organizations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    password_hash VARCHAR(255),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'DISABLED')),
    version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE organization_memberships (
    organization_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id, user_id),
    KEY membership_user (user_id, organization_id),
    FOREIGN KEY (organization_id) REFERENCES organizations(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE organization_membership_roles (
    organization_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_code VARCHAR(32) NOT NULL CHECK (role_code IN ('ORG_ADMIN', 'PARTNER_MANAGER', 'MEMBER')),
    PRIMARY KEY (organization_id, user_id, role_code),
    FOREIGN KEY (organization_id, user_id) REFERENCES organization_memberships(organization_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE account_activation_tokens (
    token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT,
    actor_id BIGINT,
    action VARCHAR(48) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    request_id VARCHAR(64) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    KEY audit_time (organization_id, occurred_at),
    KEY audit_resource (organization_id, resource_type, resource_id),
    FOREIGN KEY (organization_id) REFERENCES organizations(id),
    FOREIGN KEY (actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
