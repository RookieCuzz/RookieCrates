package com.cuzz.rookieCrates.runtime;

import java.util.Objects;

/** Identifies the configured crate and the physical placement that was clicked. */
public record CrateInteraction(String crateId, String placementId) {
    public CrateInteraction {
        crateId = requireText(crateId, "crateId");
        placementId = requireText(placementId, "placementId");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
