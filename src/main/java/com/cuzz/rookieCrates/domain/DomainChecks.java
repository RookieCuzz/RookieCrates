package com.cuzz.rookieCrates.domain;

import java.util.Objects;

final class DomainChecks {
    private DomainChecks() {
    }

    static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    static String nullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }
}
