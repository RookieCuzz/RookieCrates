package com.cuzz.rookieCrates.runtime;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** A concrete draw result to render; the business layer remains responsible for granting it. */
public record SceneReward(ItemStack item, String displayName) {
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
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }
}
