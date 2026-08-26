package com.cuzz.rookieCrates.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

/** Defaults applied when an administrator creates a new reward. */
public record LootDisplaySettings(double defaultItemScale) {
    public static final double DEFAULT_ITEM_SCALE = 0.6D;

    public LootDisplaySettings {
        if (!Double.isFinite(defaultItemScale) || defaultItemScale <= 0.0D || defaultItemScale > 4.0D) {
            throw new IllegalArgumentException("loot-display.default-item-scale must be greater than 0 and at most 4");
        }
    }

    public static LootDisplaySettings load(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        return new LootDisplaySettings(config.getDouble(
                "loot-display.default-item-scale",
                DEFAULT_ITEM_SCALE
        ));
    }
}
