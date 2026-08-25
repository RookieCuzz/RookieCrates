package com.cuzz.rookieCrates.runtime;

import com.cuzz.rookieCrates.runtime.model.RuntimeModelHandle;
import org.bukkit.GameMode;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One player's isolated camera/model resources for one resolved draw transaction. */
public final class SceneSession {
    private final Plugin plugin;
    private final CrateRuntime crateRuntime;
    private final SceneController controller;
    private final Player player;
    private final SceneRequest request;
    private final PlayerStateSnapshot snapshot;
    private final SceneTiming timing;
    private final List<RuntimeModelHandle> lootModels = new ArrayList<>();
    private RuntimeModelHandle crateModel;
    private ArmorStand camera;
    private BukkitTask revealTask;
    private BukkitTask finishTask;
    private BukkitTask skipEnableTask;
    private boolean closed;
    private boolean revealed;
    private boolean skipEnabled;

    SceneSession(
            Plugin plugin,
            CrateRuntime crateRuntime,
            SceneController controller,
            Player player,
            SceneRequest request,
            PlayerStateSnapshot snapshot,
            SceneTiming timing
    ) {
        this.plugin = plugin;
        this.crateRuntime = crateRuntime;
        this.controller = controller;
        this.player = player;
        this.request = request;
        this.snapshot = snapshot;
        this.timing = timing;
    }

    public UUID playerId() {
        return player.getUniqueId();
    }

    public UUID transactionId() {
        return request.transactionId();
    }

    public boolean canSkip() {
        return request.skipAllowed() && skipEnabled;
    }

    PlayerStateSnapshot snapshot() {
        return snapshot;
    }

    SceneRequest request() {
        return request;
    }

    void start() {
        crateRuntime.setPlacementVisible(player, request.placement().placementId(), false);
        crateModel = RuntimeModelHandle.spawnPrivate(
                request.placement().crateLocation(),
                request.placement().crateModel(),
                player
        );
        if (!crateModel.playAnimation(request.openAnimation(), false)) {
            throw new IllegalArgumentException("Missing crate animation: " + request.openAnimation());
        }

        camera = request.placement().cameraLocation().getWorld().spawn(
                request.placement().cameraLocation(),
                ArmorStand.class,
                stand -> {
                    stand.setVisible(false);
                    stand.setMarker(true);
                    stand.setGravity(false);
                    stand.setInvulnerable(true);
                    stand.setCollidable(false);
                    stand.setSilent(true);
                    stand.setPersistent(false);
                    stand.setCanTick(false);
                }
        );

        if (!player.teleport(request.placement().cameraLocation())) {
            throw new IllegalStateException("Camera teleport was rejected");
        }
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setGameMode(GameMode.SPECTATOR);
        player.setSpectatorTarget(camera);

        if (request.skipAllowed()) {
            if (timing.skipAfterTicks() == 0L) {
                skipEnabled = true;
            } else {
                skipEnableTask = plugin.getServer().getScheduler().runTaskLater(
                        plugin,
                        () -> skipEnabled = true,
                        timing.skipAfterTicks()
                );
            }
        }

        revealTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> controller.reveal(player.getUniqueId()),
                timing.revealDelayTicks()
        );
    }

    void reveal() {
        if (closed || revealed) {
            return;
        }
        revealed = true;
        if (crateModel != null) {
            crateModel.remove();
            crateModel = null;
        }

        List<org.bukkit.Location> positions = request.placement().lootLocations();
        for (int index = 0; index < request.rewards().size(); index++) {
            SceneReward reward = request.rewards().get(index);
            RuntimeModelHandle lootModel = RuntimeModelHandle.spawnPrivate(
                    positions.get(index),
                    request.lootModel(),
                    player
            );
            lootModels.add(lootModel);
            lootModel.configureLoot(reward.item(), reward.displayName());
            if (!lootModel.playAnimation("idle", true)) {
                throw new IllegalArgumentException("Missing idle animation for loot model: " + request.lootModel());
            }
        }

        // Both one-draw and seven-draw reveal in this single tick; seven-draw maps loot1..loot7.
        finishTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> controller.complete(player.getUniqueId()),
                timing.resultDisplayTicks()
        );
    }

    void closeResources() {
        if (closed) {
            return;
        }
        closed = true;
        if (revealTask != null) {
            safely(revealTask::cancel, "reveal task");
        }
        if (finishTask != null) {
            safely(finishTask::cancel, "finish task");
        }
        if (skipEnableTask != null) {
            safely(skipEnableTask::cancel, "skip task");
        }
        if (crateModel != null) {
            safely(crateModel::remove, "crate model");
            crateModel = null;
        }
        lootModels.forEach(model -> safely(model::remove, "loot model"));
        lootModels.clear();
        if (camera != null) {
            safely(camera::remove, "camera entity");
            camera = null;
        }
        safely(
                () -> crateRuntime.setPlacementVisible(player, request.placement().placementId(), true),
                "public crate visibility"
        );
    }

    private void safely(Runnable cleanup, String resourceName) {
        try {
            cleanup.run();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not clean scene " + resourceName + " for "
                    + player.getUniqueId() + ": " + exception.getMessage());
        }
    }
}
