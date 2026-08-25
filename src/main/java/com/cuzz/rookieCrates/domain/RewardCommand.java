package com.cuzz.rookieCrates.domain;

public record RewardCommand(long id, String rewardId, String command, int executionOrder) {
    public RewardCommand {
        if (id < 0) {
            throw new IllegalArgumentException("id must be non-negative; use 0 for a new row");
        }
        rewardId = DomainChecks.required(rewardId, "rewardId");
        command = DomainChecks.required(command, "command");
        if (executionOrder < 0) {
            throw new IllegalArgumentException("executionOrder must be non-negative");
        }
    }
}
