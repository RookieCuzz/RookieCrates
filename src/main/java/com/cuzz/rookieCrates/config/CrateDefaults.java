package com.cuzz.rookieCrates.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

/** Validated defaults used when an administrator creates a new crate. */
public record CrateDefaults(
        double singlePrice,
        double sevenPrice,
        int guaranteeA,
        int guaranteeS,
        boolean skipAllowed,
        double interactionWidth,
        double interactionHeight,
        String crateModel,
        LootModelPalette lootModels,
        String idleAnimation,
        String singleOpenAnimation,
        String sevenOpenAnimation
) {

    private static final String ROOT = "defaults.crate.";

    public CrateDefaults {
        requireNonNegative(singlePrice, "defaults.crate.single-price");
        requireNonNegative(sevenPrice, "defaults.crate.seven-price");
        if (guaranteeA <= 0 || guaranteeS <= 0 || guaranteeA > guaranteeS) {
            throw new IllegalArgumentException(
                    "defaults.crate guarantees must satisfy 0 < guarantee-a <= guarantee-s");
        }
        requirePositive(interactionWidth, "defaults.crate.interaction.width");
        requirePositive(interactionHeight, "defaults.crate.interaction.height");
        crateModel = requireText(crateModel, "defaults.crate.models.crate");
        lootModels = Objects.requireNonNull(lootModels, "lootModels");
        idleAnimation = requireText(idleAnimation, "defaults.crate.animations.idle");
        singleOpenAnimation = requireText(singleOpenAnimation, "defaults.crate.animations.single-open");
        sevenOpenAnimation = requireText(sevenOpenAnimation, "defaults.crate.animations.seven-open");
    }

    public static CrateDefaults load(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        return new CrateDefaults(
                config.getDouble(ROOT + "single-price", 0.0D),
                config.getDouble(ROOT + "seven-price", 0.0D),
                config.getInt(ROOT + "guarantee-a", 10),
                config.getInt(ROOT + "guarantee-s", 80),
                config.getBoolean(ROOT + "skip-allowed", true),
                config.getDouble(ROOT + "interaction.width", 1.5D),
                config.getDouble(ROOT + "interaction.height", 2.0D),
                config.getString(ROOT + "models.crate", "default_crate"),
                LootModelPalette.load(config),
                config.getString(ROOT + "animations.idle", "idle"),
                config.getString(ROOT + "animations.single-open", "open1"),
                config.getString(ROOT + "animations.seven-open", "open7")
        );
    }

    private static String requireText(String value, String path) {
        String normalized = Objects.requireNonNull(value, path).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
        return normalized;
    }

    private static void requireNonNegative(double value, String path) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException(path + " must be finite and non-negative");
        }
    }

    private static void requirePositive(double value, String path) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException(path + " must be finite and positive");
        }
    }
}
