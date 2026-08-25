package com.cuzz.rookieCrates.domain;

import java.util.Objects;
import java.util.UUID;

public record OpenResult(
        UUID transactionId,
        int resultIndex,
        String rewardId,
        Rarity rarity,
        boolean delivered
) {
    public OpenResult {
        transactionId = Objects.requireNonNull(transactionId, "transactionId");
        if (resultIndex < 1 || resultIndex > 7) {
            throw new IllegalArgumentException("resultIndex must be in 1..7");
        }
        rewardId = DomainChecks.required(rewardId, "rewardId");
        rarity = Objects.requireNonNull(rarity, "rarity");
    }
}
