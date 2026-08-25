package com.cuzz.rookieCrates.config;

import com.cuzz.rookieCrates.domain.Rarity;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** The four ModelEngine loot displays used for C, B, A and S draw results. */
public final class LootModelPalette {

    private static final String ROOT = "defaults.crate.models.loot-by-rarity.";
    private final Map<Rarity, String> models;

    public LootModelPalette(Map<Rarity, String> models) {
        Objects.requireNonNull(models, "models");
        EnumMap<Rarity, String> validated = new EnumMap<>(Rarity.class);
        for (Rarity rarity : Rarity.values()) {
            String model = Objects.requireNonNull(models.get(rarity), "Missing loot model for " + rarity).trim();
            if (model.isEmpty()) {
                throw new IllegalArgumentException("Loot model for " + rarity + " must not be blank");
            }
            validated.put(rarity, model);
        }
        this.models = Map.copyOf(validated);
    }

    public static LootModelPalette load(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        EnumMap<Rarity, String> models = new EnumMap<>(Rarity.class);
        models.put(Rarity.C, config.getString(ROOT + "C", "loot_white"));
        models.put(Rarity.B, config.getString(ROOT + "B", "loot_blue"));
        models.put(Rarity.A, config.getString(ROOT + "A", "loot_pink"));
        models.put(Rarity.S, config.getString(ROOT + "S", "loot_yellow"));
        return new LootModelPalette(models);
    }

    public String modelFor(Rarity rarity) {
        return models.get(Objects.requireNonNull(rarity, "rarity"));
    }

    public Map<Rarity, String> asMap() {
        return models;
    }
}
