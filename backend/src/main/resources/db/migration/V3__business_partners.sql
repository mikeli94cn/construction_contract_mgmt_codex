CREATE TABLE business_partners (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    reference_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_name VARCHAR(160) NOT NULL,
    registration_identifier VARCHAR(80),
    contact_email VARCHAR(254),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY partner_organization_id (organization_id, id),
    UNIQUE KEY partner_reference (organization_id, reference_code),
    KEY partner_status (organization_id, status, id),
    FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
