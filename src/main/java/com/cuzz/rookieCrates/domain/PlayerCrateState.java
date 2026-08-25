package com.cuzz.rookieCrates.domain;

import java.util.Objects;
import java.util.UUID;

public record PlayerCrateState(
        UUID playerUuid,
        String crateId,
        int virtualKeys,
        int pityA,
        int pityS,
        long totalOpens,
        long updatedAt
) {
    public PlayerCrateState {
        playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        crateId = DomainChecks.required(crateId, "crateId");
        if (virtualKeys < 0 || pityA < 0 || pityS < 0 || totalOpens < 0 || updatedAt < 0) {
            throw new IllegalArgumentException("player state counters and timestamp must be non-negative");
        }
    }
}
