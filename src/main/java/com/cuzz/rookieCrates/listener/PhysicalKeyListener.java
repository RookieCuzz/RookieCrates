package com.cuzz.rookieCrates.listener;

import com.cuzz.rookieCrates.key.PhysicalKeyService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Objects;
import java.util.function.BiConsumer;

/** Opens the draw menu when a player right-clicks a tagged physical key. */
public final class PhysicalKeyListener implements Listener {
    private final PhysicalKeyService keys;
    private final BiConsumer<Player, String> openCrate;

    public PhysicalKeyListener(PhysicalKeyService keys, BiConsumer<Player, String> openCrate) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.openCrate = Objects.requireNonNull(openCrate, "openCrate");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        keys.crateId(event.getItem()).ifPresent(crateId -> {
            event.setCancelled(true);
            openCrate.accept(event.getPlayer(), crateId);
        });
    }
}
