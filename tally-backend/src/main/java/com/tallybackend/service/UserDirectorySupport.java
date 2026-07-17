package com.tallybackend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class UserDirectorySupport {

    private UserDirectorySupport() {
    }

    static List<String> parseRolesCsv(String csv) {
        List<String> roles = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return roles;
        }
        for (String part : csv.split(",")) {
            String role = trimToNull(part);
            if (role != null) {
                roles.add(role.toUpperCase(Locale.ROOT));
            }
        }
        return roles;
    }

    static String rolesToCsv(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String role : roles) {
            String normalized = trimToNull(role);
            if (normalized == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(normalized.toUpperCase(Locale.ROOT));
        }
        return builder.toString();
    }

    static String normalizeUsername(String value) {
        String text = trimToNull(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
