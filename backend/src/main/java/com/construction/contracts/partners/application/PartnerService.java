package com.construction.contracts.partners.application;

import com.construction.contracts.audit.application.AuditWriter;
import com.construction.contracts.identity.application.OrganizationAccess;
import com.construction.contracts.identity.domain.OrganizationRole;
import com.construction.contracts.partners.application.PartnerStore.Search;
import com.construction.contracts.partners.application.PartnerStore.Summary;
import com.construction.contracts.partners.domain.Partner;
import com.construction.contracts.partners.domain.Partner.Status;
import com.construction.contracts.projects.application.ProjectService;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartnerService {
    public record Page<T>(List<T> items, long total) {}
    public record Fields(String referenceCode, String legalName, String registrationIdentifier, String contactEmail) {}

    private final PartnerStore partners;
    private final OrganizationAccess organizations;
    private final ProjectService projects;
    private final AuditWriter audit;

    public PartnerService(PartnerStore partners, OrganizationAccess organizations, ProjectService projects,
                          AuditWriter audit) {
        this.partners = partners;
        this.organizations = organizations;
        this.projects = projects;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<Partner> list(long orgId, long actorId, Search search) {
        requireRegistry(organizations.roles(actorId, orgId));
        return new Page<>(partners.list(orgId, search), partners.count(orgId, search));
    }

    @Transactional(readOnly = true)
    public Page<Summary> lookup(long orgId, long actorId, long projectId, String query, int offset, int size) {
        projects.requireContractAuthor(orgId, actorId, projectId);
        var search = new Search(query, Status.ACTIVE, offset, size);
        return new Page<>(partners.lookup(orgId, search), partners.count(orgId, search));
    }

    @Transactional(readOnly = true)
    public Partner get(long orgId, long actorId, long partnerId) {
        requireRegistry(organizations.roles(actorId, orgId));
        return find(orgId, partnerId);
    }

    @Transactional
    public Partner create(long orgId, long actorId, Fields fields) {
        requireRegistry(organizations.lockAndGetRoles(actorId, orgId));
        String reference = Partner.normalizeReference(fields.referenceCode());
        requireUnique(orgId, reference, 0);
        Partner created = partners.create(orgId, new Partner(0, reference, legalName(fields.legalName()),
            optional(fields.registrationIdentifier()), optional(fields.contactEmail()), Status.ACTIVE, 0));
        record(orgId, actorId, created.id(), "PARTNER_CREATED", "Business partner created");
        return created;
    }

    @Transactional
    public Partner update(long orgId, long actorId, long partnerId, long version, Fields fields, Status status) {
        requireRegistry(organizations.lockAndGetRoles(actorId, orgId));
        Partner current = find(orgId, partnerId);
        if (current.version() != version) {
            throw stale();
        }
        String reference = fields.referenceCode() == null ? current.referenceCode()
            : Partner.normalizeReference(fields.referenceCode());
        requireUnique(orgId, reference, partnerId);
        var changed = new Partner(partnerId, reference,
            fields.legalName() == null ? current.legalName() : legalName(fields.legalName()),
            fields.registrationIdentifier() == null ? current.registrationIdentifier() : optional(fields.registrationIdentifier()),
            fields.contactEmail() == null ? current.contactEmail() : optional(fields.contactEmail()),
            status == null ? current.status() : status, version);
        if (!partners.update(orgId, changed)) {
            throw stale();
        }
        record(orgId, actorId, partnerId, "PARTNER_UPDATED", "status=" + current.status() + " -> " + changed.status()
            + ", referenceChanged=" + !current.referenceCode().equals(changed.referenceCode()));
        return find(orgId, partnerId);
    }

    private Partner find(long orgId, long partnerId) {
        return partners.find(orgId, partnerId).orElseThrow(() ->
            new PartnerProblem(404, "partner_unavailable", "Business partner unavailable"));
    }

    private void requireUnique(long orgId, String reference, long exceptId) {
        if (partners.referenceExists(orgId, reference, exceptId)) {
            throw new PartnerProblem(409, "partner_reference_exists", "This partner reference is already in use");
        }
    }

    private void requireRegistry(Set<OrganizationRole> roles) {
        if (!roles.contains(OrganizationRole.ORG_ADMIN) && !roles.contains(OrganizationRole.PARTNER_MANAGER)) {
            throw new PartnerProblem(403, "partner_management_denied", "Business partner registry permission required");
        }
    }

    private PartnerProblem stale() {
        return new PartnerProblem(412, "stale_version", "Reload the business partner before saving");
    }

    private String legalName(String value) {
        if (value.isBlank()) {
            throw new PartnerProblem(400, "invalid_legal_name", "Enter a legal name");
        }
        return value.strip();
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void record(long orgId, long actorId, long partnerId, String action, String summary) {
        audit.record(orgId, actorId, action, "BUSINESS_PARTNER", partnerId, summary);
    }
}
