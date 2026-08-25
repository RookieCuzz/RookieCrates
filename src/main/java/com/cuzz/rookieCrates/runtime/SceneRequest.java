package com.cuzz.rookieCrates.runtime;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Fully resolved scene input. Only one- and seven-result requests are supported. */
public record SceneRequest(
        UUID transactionId,
        CratePlacement placement,
        List<SceneReward> rewards,
        String openAnimation,
        boolean skipAllowed,
        Runnable onComplete,
        Consumer<Throwable> onFailure
) {
    public SceneRequest {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(rewards, "rewards");
        if (rewards.size() != 1 && rewards.size() != 7) {
            throw new IllegalArgumentException("rewards must contain exactly one or seven results");
        }
        rewards = List.copyOf(rewards);
        if (rewards.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("rewards must not contain null");
        }
        openAnimation = requireText(openAnimation, "openAnimation");
        Objects.requireNonNull(onComplete, "onComplete");
        Objects.requireNonNull(onFailure, "onFailure");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
