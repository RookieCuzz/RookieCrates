package com.cuzz.rookieCrates.domain;

import java.util.Objects;

public record RewardDefinition(
        String id,
        String crateId,
        String displayName,
        String description,
        Rarity rarity,
        double weight,
        double displayScale,
        boolean enabled,
        boolean broadcast
) {
    public static final double DEFAULT_DISPLAY_SCALE = 0.6D;

    public RewardDefinition(
            String id,
            String crateId,
            String displayName,
            String description,
            Rarity rarity,
            double weight,
            boolean enabled,
            boolean broadcast
    ) {
        this(id, crateId, displayName, description, rarity, weight, DEFAULT_DISPLAY_SCALE, enabled, broadcast);
    }

    public RewardDefinition(
            String id,
            String crateId,
            String displayName,
            String description,
            Rarity rarity,
            double weight,
            boolean enabled
    ) {
        this(id, crateId, displayName, description, rarity, weight, DEFAULT_DISPLAY_SCALE, enabled, false);
    }

    public RewardDefinition {
        id = DomainChecks.required(id, "id");
        crateId = DomainChecks.required(crateId, "crateId");
        displayName = DomainChecks.required(displayName, "displayName");
        description = Objects.requireNonNullElse(description, "");
        rarity = Objects.requireNonNull(rarity, "rarity");
        if (!Double.isFinite(weight) || weight < 0.0) {
            throw new IllegalArgumentException("weight must be finite and non-negative");
        }
        if (!Double.isFinite(displayScale) || displayScale <= 0.0D || displayScale > 4.0D) {
            throw new IllegalArgumentException("displayScale must be greater than 0 and at most 4");
        }
    }
}
