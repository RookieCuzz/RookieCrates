package com.cuzz.rookieCrates.domain;

import java.util.Objects;
import java.util.UUID;

public record PendingDelivery(
        long id,
        UUID transactionId,
        int resultIndex,
        UUID playerUuid,
        String rewardId,
        byte[] itemBlob,
        int amount,
        String command,
        PendingDeliveryStatus status,
        int attempts,
        String lastError,
        long createdAt,
        Long deliveredAt
) {
    public PendingDelivery {
        if (id < 0) {
            throw new IllegalArgumentException("id must be non-negative; use 0 for a new row");
        }
        transactionId = Objects.requireNonNull(transactionId, "transactionId");
        if (resultIndex < 1 || resultIndex > 7) {
            throw new IllegalArgumentException("resultIndex must be in 1..7");
        }
        playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        rewardId = DomainChecks.required(rewardId, "rewardId");
        itemBlob = DomainChecks.copy(itemBlob);
        command = DomainChecks.nullable(command);
        boolean hasItem = itemBlob != null && itemBlob.length > 0;
        boolean hasCommand = command != null;
        if (hasItem == hasCommand) {
            throw new IllegalArgumentException("a delivery must contain exactly one item or command");
        }
        if ((hasItem && amount <= 0) || (hasCommand && amount != 0)) {
            throw new IllegalArgumentException("item amount must be positive and command amount must be zero");
        }
        status = Objects.requireNonNull(status, "status");
        if (attempts < 0 || createdAt < 0 || (deliveredAt != null && deliveredAt < createdAt)) {
            throw new IllegalArgumentException("delivery counters or timestamps are invalid");
        }
        lastError = DomainChecks.nullable(lastError);
    }

    @Override
    public byte[] itemBlob() {
        return DomainChecks.copy(itemBlob);
    }
}
