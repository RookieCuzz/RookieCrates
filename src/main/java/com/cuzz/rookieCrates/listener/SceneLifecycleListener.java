package com.cuzz.rookieCrates.listener;

import com.cuzz.rookieCrates.runtime.SceneAbortReason;
import com.cuzz.rookieCrates.runtime.SceneController;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.Objects;

/** Routes every normal/abnormal player lifecycle edge into idempotent scene cleanup. */
public final class SceneLifecycleListener implements Listener {
    private final SceneController controller;

    public SceneLifecycleListener(SceneController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            controller.skip(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        controller.abort(event.getPlayer(), SceneAbortReason.QUIT);
    }

    @EventHandler(ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        controller.abort(event.getPlayer(), SceneAbortReason.KICK);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        controller.handleDeath(event.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        controller.handleRespawn(event);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        controller.recoverPending(event.getPlayer());
    }
}
