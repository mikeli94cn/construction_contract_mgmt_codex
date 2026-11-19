package com.construction.contracts.partners.infrastructure;

import com.construction.contracts.partners.application.PartnerStore;
import com.construction.contracts.partners.domain.Partner;
import com.construction.contracts.partners.domain.Partner.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPartnerStore implements PartnerStore {
    private static final String FILTER = """
        FROM business_partners WHERE organization_id = :orgId
        AND (:status IS NULL OR status = :status)
        AND (reference_code LIKE :referenceQuery ESCAPE '!' OR legal_name LIKE :query ESCAPE '!')
        """;
    private static final String PAGE = " ORDER BY id LIMIT :size OFFSET :offset";

    private final JdbcClient jdbc;

    JdbcPartnerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Partner> list(long orgId, Search search) {
        return page("SELECT * " + FILTER, orgId, search).query(this::row).list();
    }

    @Override
    public List<Summary> lookup(long orgId, Search search) {
        return page("SELECT id, reference_code, legal_name " + FILTER, orgId, search)
            .query((rs, index) -> new Summary(rs.getLong("id"), rs.getString("reference_code"), rs.getString("legal_name")))
            .list();
    }

    @Override
    public long count(long orgId, Search search) {
        return filtered("SELECT COUNT(*) " + FILTER, orgId, search).query(Long.class).single();
    }

    private JdbcClient.StatementSpec page(String sql, long orgId, Search search) {
        return filtered(sql + PAGE, orgId, search).param("size", search.size()).param("offset", search.offset());
    }

    private JdbcClient.StatementSpec filtered(String sql, long orgId, Search search) {
        String query = search.query().strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return jdbc.sql(sql).param("orgId", orgId).param("status", search.status() == null ? null : search.status().name())
            .param("query", "%" + query + "%")
            .param("referenceQuery", "%" + Partner.normalizeReference(query) + "%");
    }

    @Override
    public Optional<Partner> find(long orgId, long partnerId) {
        return jdbc.sql("SELECT * FROM business_partners WHERE organization_id = ? AND id = ?")
            .params(orgId, partnerId).query(this::row).optional();
    }

    @Override
    public boolean referenceExists(long orgId, String reference, long exceptId) {
        return jdbc.sql("SELECT id FROM business_partners WHERE organization_id = ? AND reference_code = ? AND id <> ?")
            .params(orgId, reference, exceptId).query(Long.class).optional().isPresent();
    }

    @Override
    public Partner create(long orgId, Partner partner) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO business_partners
                (organization_id, reference_code, legal_name, registration_identifier, contact_email, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """).params(orgId, partner.referenceCode(), partner.legalName(), partner.registrationIdentifier(),
                partner.contactEmail()).update(key);
        return new Partner(Objects.requireNonNull(key.getKey()).longValue(), partner.referenceCode(), partner.legalName(),
            partner.registrationIdentifier(), partner.contactEmail(), partner.status(), 0);
    }

    @Override
    public boolean update(long orgId, Partner partner) {
        return jdbc.sql("""
            UPDATE business_partners SET reference_code = ?, legal_name = ?, registration_identifier = ?,
                contact_email = ?, status = ?, version = version + 1
            WHERE organization_id = ? AND id = ? AND version = ?
            """).params(partner.referenceCode(), partner.legalName(), partner.registrationIdentifier(),
                partner.contactEmail(), partner.status().name(), orgId, partner.id(), partner.version()).update() == 1;
    }

    private Partner row(ResultSet rs, int index) throws SQLException {
        return new Partner(rs.getLong("id"), rs.getString("reference_code"), rs.getString("legal_name"),
            rs.getString("registration_identifier"), rs.getString("contact_email"),
            Status.valueOf(rs.getString("status")), rs.getLong("version"));
    }
}
