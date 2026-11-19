package com.construction.contracts.partners.application;

import com.construction.contracts.partners.domain.Partner;
import com.construction.contracts.partners.domain.Partner.Status;
import java.util.List;
import java.util.Optional;

public interface PartnerStore {
    record Summary(long id, String referenceCode, String legalName) {}
    record Search(String query, Status status, int offset, int size) {}

    List<Partner> list(long orgId, Search search);
    List<Summary> lookup(long orgId, Search search);
    long count(long orgId, Search search);
    Optional<Partner> find(long orgId, long partnerId);
    boolean referenceExists(long orgId, String reference, long exceptId);
    Partner create(long orgId, Partner partner);
    boolean update(long orgId, Partner partner);
}
