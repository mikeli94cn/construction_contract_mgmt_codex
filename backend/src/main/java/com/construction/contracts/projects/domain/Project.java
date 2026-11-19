package com.construction.contracts.projects.domain;

import java.util.Locale;

public record Project(long id, String code, String name, Status status, long version) {
    public enum Status { ACTIVE, ARCHIVED }
    public enum Role { PROJECT_MANAGER, CONTRACT_EDITOR, PROJECT_VIEWER }

    public static String normalizeCode(String code) {
        return code.toUpperCase(Locale.ROOT);
    }
}
