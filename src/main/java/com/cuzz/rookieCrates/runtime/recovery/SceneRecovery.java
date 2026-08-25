package com.cuzz.rookieCrates.runtime.recovery;

import java.util.Objects;
import java.util.UUID;

/** Storage-neutral player snapshot kept until a scene has been restored successfully. */
public record SceneRecovery(
        UUID playerId,
        UUID transactionId,
        String worldId,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String gameMode,
        boolean allowFlight,
        boolean flying,
        float flySpeed,
        float walkSpeed,
        boolean invulnerable,
        boolean collidable,
        String spectatorTargetId
) {
    public SceneRecovery {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(transactionId, "transactionId");
        worldId = requireText(worldId, "worldId");
        worldName = requireText(worldName, "worldName");
        gameMode = requireText(gameMode, "gameMode");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
