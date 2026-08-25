package com.cuzz.rookieCrates.domain;

import java.util.Objects;
import java.util.UUID;

public record ActiveSceneRecovery(
        UUID playerUuid,
        UUID transactionId,
        UUID worldUuid,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String gameMode,
        boolean allowFlight,
        boolean flying,
        float walkSpeed,
        float flySpeed,
        boolean invulnerable,
        boolean collidable,
        UUID cameraEntityUuid,
        long createdAt
) {
    public ActiveSceneRecovery {
        playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        transactionId = Objects.requireNonNull(transactionId, "transactionId");
        worldUuid = Objects.requireNonNull(worldUuid, "worldUuid");
        world = DomainChecks.required(world, "world");
        gameMode = DomainChecks.required(gameMode, "gameMode");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Float.isFinite(walkSpeed) || !Float.isFinite(flySpeed)) {
            throw new IllegalArgumentException("recovery numeric values must be finite");
        }
        if (createdAt < 0) {
            throw new IllegalArgumentException("createdAt must be non-negative");
        }
    }
}
