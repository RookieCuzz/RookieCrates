package com.cuzz.rookieCrates.domain;

import java.util.List;
import java.util.Objects;

public record RewardBundle(
        RewardDefinition definition,
        List<RewardItem> items,
        List<RewardCommand> commands
) {
    public RewardBundle {
        definition = Objects.requireNonNull(definition, "definition");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        String rewardId = definition.id();
        if (items.stream().anyMatch(item -> !rewardId.equals(item.rewardId()))
                || commands.stream().anyMatch(command -> !rewardId.equals(command.rewardId()))) {
            throw new IllegalArgumentException("all bundle children must reference the reward definition");
        }
    }
}
