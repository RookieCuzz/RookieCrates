package com.cuzz.rookieCrates.domain;

import java.util.Objects;

public record RewardDefinition(
        String id,
        String crateId,
        String displayName,
        String description,
        Rarity rarity,
        double weight,
        boolean enabled,
        boolean broadcast
) {
    public RewardDefinition(
            String id,
            String crateId,
            String displayName,
            String description,
            Rarity rarity,
            double weight,
            boolean enabled
    ) {
        this(id, crateId, displayName, description, rarity, weight, enabled, false);
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
    }
}
