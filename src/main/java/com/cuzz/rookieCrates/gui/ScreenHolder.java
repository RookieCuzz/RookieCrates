package com.cuzz.rookieCrates.gui;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ScreenHolder implements InventoryHolder {

    public enum Screen {
        PLAYER_LIST,
        PLAYER_CRATE,
        ADMIN_LIST,
        ADMIN_CRATE,
        REWARD_EDIT,
        SCENE,
        SCENE_FINE
    }

    private final UUID viewer;
    private final Screen screen;
    private final String crateId;
    private final String rewardId;
    private final int page;
    private final Inventory inventory;
    private final Map<Integer, String> targets = new HashMap<>();

    public ScreenHolder(UUID viewer, Screen screen, String crateId, String rewardId, int page, int size, String title) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.screen = Objects.requireNonNull(screen, "screen");
        this.crateId = crateId;
        this.rewardId = rewardId;
        this.page = Math.max(0, page);
        this.inventory = Bukkit.createInventory(this, size, Objects.requireNonNull(title, "title"));
    }

    public UUID viewer() {
        return viewer;
    }

    public Screen screen() {
        return screen;
    }

    public String crateId() {
        return crateId;
    }

    public String rewardId() {
        return rewardId;
    }

    public int page() {
        return page;
    }

    public void setTarget(int slot, String target) {
        targets.put(slot, Objects.requireNonNull(target, "target"));
    }

    public Optional<String> target(int slot) {
        return Optional.ofNullable(targets.get(slot));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
