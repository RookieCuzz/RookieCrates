package com.cuzz.rookieCrates.runtime;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** A concrete draw result and its rarity-specific display model. */
public record SceneReward(ItemStack item, String displayName, String lootModel, double displayScale) {
    public SceneReward {
        Objects.requireNonNull(item, "item");
        if (item.getType().isAir() || item.getAmount() <= 0) {
            throw new IllegalArgumentException("item must be a non-empty ItemStack");
        }
        item = item.clone();
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        Objects.requireNonNull(lootModel, "lootModel");
        if (lootModel.isBlank()) {
            throw new IllegalArgumentException("lootModel must not be blank");
        }
        if (!Double.isFinite(displayScale) || displayScale <= 0.0D || displayScale > 4.0D) {
            throw new IllegalArgumentException("displayScale must be greater than 0 and at most 4");
        }
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }
}
