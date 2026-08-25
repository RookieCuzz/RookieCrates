package com.cuzz.rookieCrates.runtime;

import com.cuzz.rookieCrates.runtime.recovery.SceneRecovery;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Exact mutable player state touched by a scene, represented without retaining a World instance. */
final class PlayerStateSnapshot {
    private final UUID worldId;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final GameMode gameMode;
    private final boolean allowFlight;
    private final boolean flying;
    private final float flySpeed;
    private final float walkSpeed;
    private final boolean invulnerable;
    private final boolean collidable;
    private final UUID spectatorTargetId;

    private PlayerStateSnapshot(
            UUID worldId,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            GameMode gameMode,
            boolean allowFlight,
            boolean flying,
            float flySpeed,
            float walkSpeed,
            boolean invulnerable,
            boolean collidable,
            UUID spectatorTargetId
    ) {
        this.worldId = worldId;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.gameMode = gameMode;
        this.allowFlight = allowFlight;
        this.flying = flying;
        this.flySpeed = flySpeed;
        this.walkSpeed = walkSpeed;
        this.invulnerable = invulnerable;
        this.collidable = collidable;
        this.spectatorTargetId = spectatorTargetId;
    }

    static PlayerStateSnapshot capture(Player player) {
        Location location = player.getLocation();
        Entity spectatorTarget = player.getSpectatorTarget();
        return new PlayerStateSnapshot(
                location.getWorld().getUID(),
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                player.getGameMode(),
                player.getAllowFlight(),
                player.isFlying(),
                player.getFlySpeed(),
                player.getWalkSpeed(),
                player.isInvulnerable(),
                player.isCollidable(),
                spectatorTarget == null ? null : spectatorTarget.getUniqueId()
        );
    }

    static PlayerStateSnapshot fromRecovery(SceneRecovery recovery) {
        return new PlayerStateSnapshot(
                UUID.fromString(recovery.worldId()),
                recovery.worldName(),
                recovery.x(),
                recovery.y(),
                recovery.z(),
                recovery.yaw(),
                recovery.pitch(),
                GameMode.valueOf(recovery.gameMode()),
                recovery.allowFlight(),
                recovery.flying(),
                recovery.flySpeed(),
                recovery.walkSpeed(),
                recovery.invulnerable(),
                recovery.collidable(),
                recovery.spectatorTargetId() == null ? null : UUID.fromString(recovery.spectatorTargetId())
        );
    }

    SceneRecovery toRecovery(UUID playerId, UUID transactionId) {
        return new SceneRecovery(
                playerId,
                transactionId,
                worldId.toString(),
                worldName,
                x,
                y,
                z,
                yaw,
                pitch,
                gameMode.name(),
                allowFlight,
                flying,
                flySpeed,
                walkSpeed,
                invulnerable,
                collidable,
                spectatorTargetId == null ? null : spectatorTargetId.toString()
        );
    }

    Location recoveryLocation() {
        World world = Bukkit.getWorld(worldId);
        if (world == null) {
            world = Bukkit.getWorld(worldName);
        }
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }

    /** Best-effort restore of every field. Returns false if any required operation failed. */
    boolean restore(Player player, boolean includeLocation) {
        List<Throwable> failures = new ArrayList<>();

        if (player.getGameMode() == GameMode.SPECTATOR) {
            attempt(() -> player.setSpectatorTarget(null), failures);
        }
        if (includeLocation) {
            Location location = recoveryLocation();
            if (location == null) {
                failures.add(new IllegalStateException("Recovery world is not loaded: " + worldName));
            } else {
                attempt(() -> {
                    if (!player.teleport(location)) {
                        throw new IllegalStateException("Player teleport was rejected");
                    }
                }, failures);
            }
        }

        attempt(() -> player.setGameMode(gameMode), failures);
        if (!allowFlight && player.isFlying()) {
            attempt(() -> player.setFlying(false), failures);
        }
        attempt(() -> player.setAllowFlight(allowFlight), failures);
        attempt(() -> player.setFlying(flying), failures);
        attempt(() -> player.setFlySpeed(flySpeed), failures);
        attempt(() -> player.setWalkSpeed(walkSpeed), failures);
        attempt(() -> player.setInvulnerable(invulnerable), failures);
        attempt(() -> player.setCollidable(collidable), failures);

        if (gameMode == GameMode.SPECTATOR && spectatorTargetId != null) {
            Entity target = Bukkit.getEntity(spectatorTargetId);
            if (target != null && target.isValid()) {
                attempt(() -> player.setSpectatorTarget(target), failures);
            }
        }
        return failures.isEmpty();
    }

    private static void attempt(Runnable operation, List<Throwable> failures) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            failures.add(exception);
        }
    }
}
