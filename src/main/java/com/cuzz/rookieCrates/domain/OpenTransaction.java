package com.cuzz.rookieCrates.domain;

import java.util.Objects;
import java.util.UUID;

public record OpenTransaction(
        UUID id,
        UUID playerUuid,
        String crateId,
        int drawCount,
        OpenTransactionStatus status,
        long createdAt,
        Long completedAt,
        String failureReason
) {
    public OpenTransaction {
        id = Objects.requireNonNull(id, "id");
        playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        crateId = DomainChecks.required(crateId, "crateId");
        if (drawCount != 1 && drawCount != 7) {
            throw new IllegalArgumentException("drawCount must be 1 or 7");
        }
        status = Objects.requireNonNull(status, "status");
        if (createdAt < 0 || (completedAt != null && completedAt < createdAt)) {
            throw new IllegalArgumentException("transaction timestamps are invalid");
        }
        failureReason = DomainChecks.nullable(failureReason);
    }
}
