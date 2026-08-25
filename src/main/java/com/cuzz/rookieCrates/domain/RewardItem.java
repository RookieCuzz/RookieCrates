package com.cuzz.rookieCrates.domain;

public record RewardItem(long id, String rewardId, byte[] itemBlob, int amount) {
    public RewardItem {
        if (id < 0) {
            throw new IllegalArgumentException("id must be non-negative; use 0 for a new row");
        }
        rewardId = DomainChecks.required(rewardId, "rewardId");
        itemBlob = DomainChecks.copy(itemBlob);
        if (itemBlob == null || itemBlob.length == 0) {
            throw new IllegalArgumentException("itemBlob must not be empty");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    @Override
    public byte[] itemBlob() {
        return DomainChecks.copy(itemBlob);
    }
}
