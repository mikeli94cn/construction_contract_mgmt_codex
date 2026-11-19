package com.construction.contracts.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class VersionHeaders {
    private VersionHeaders() {}

    public static String etag(long version) {
        return "\"" + version + "\"";
    }

    public static long requireVersion(String header) {
        if (header == null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
        }
        if (header.matches("\"[0-9]+\"")) {
            try {
                return Long.parseLong(header.substring(1, header.length() - 1));
            } catch (NumberFormatException ignored) {
                // Out-of-range versions cannot match.
            }
        }
        throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Reload the resource before saving");
    }
}
