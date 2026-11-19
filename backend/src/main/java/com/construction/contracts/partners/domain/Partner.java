package com.construction.contracts.partners.domain;

import java.util.Locale;

public record Partner(long id, String referenceCode, String legalName, String registrationIdentifier,
                      String contactEmail, Status status, long version) {
    public enum Status { ACTIVE, INACTIVE }

    public static String normalizeReference(String reference) {
        return reference.toUpperCase(Locale.ROOT);
    }
}
