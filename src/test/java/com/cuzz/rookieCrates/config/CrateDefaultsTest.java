package com.cuzz.rookieCrates.config;

import com.cuzz.rookieCrates.domain.Rarity;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateDefaultsTest {

    @Test
    void loadsFallbackValuesWhenSectionIsAbsent() {
        CrateDefaults defaults = CrateDefaults.load(new YamlConfiguration());

        assertEquals(0.0D, defaults.singlePrice());
        assertEquals(0.0D, defaults.sevenPrice());
        assertEquals(10, defaults.guaranteeA());
        assertEquals(80, defaults.guaranteeS());
        assertTrue(defaults.skipAllowed());
        assertEquals(1.5D, defaults.interactionWidth());
        assertEquals(2.0D, defaults.interactionHeight());
        assertEquals("default_crate", defaults.crateModel());
        assertEquals("loot_white", defaults.lootModels().modelFor(Rarity.C));
        assertEquals("loot_blue", defaults.lootModels().modelFor(Rarity.B));
        assertEquals("loot_pink", defaults.lootModels().modelFor(Rarity.A));
        assertEquals("loot_yellow", defaults.lootModels().modelFor(Rarity.S));
        assertEquals("idle", defaults.idleAnimation());
        assertEquals("open1", defaults.singleOpenAnimation());
        assertEquals("open7", defaults.sevenOpenAnimation());
    }

    @Test
    void loadsConfiguredValues() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("defaults.crate.single-price", 25.0D);
        config.set("defaults.crate.seven-price", 150.0D);
        config.set("defaults.crate.guarantee-a", 8);
        config.set("defaults.crate.guarantee-s", 60);
        config.set("defaults.crate.skip-allowed", false);
        config.set("defaults.crate.interaction.width", 2.5D);
        config.set("defaults.crate.interaction.height", 3.0D);
        config.set("defaults.crate.models.crate", "event_crate");
        config.set("defaults.crate.models.loot-by-rarity.C", "common_model");
        config.set("defaults.crate.models.loot-by-rarity.B", "rare_model");
        config.set("defaults.crate.models.loot-by-rarity.A", "epic_model");
        config.set("defaults.crate.models.loot-by-rarity.S", "legendary_model");
        config.set("defaults.crate.animations.idle", "standby");
        config.set("defaults.crate.animations.single-open", "open_single_event");
        config.set("defaults.crate.animations.seven-open", "open_seven_event");

        CrateDefaults defaults = CrateDefaults.load(config);

        assertEquals(25.0D, defaults.singlePrice());
        assertEquals(150.0D, defaults.sevenPrice());
        assertEquals(8, defaults.guaranteeA());
        assertEquals(60, defaults.guaranteeS());
        assertEquals("event_crate", defaults.crateModel());
        assertEquals("common_model", defaults.lootModels().modelFor(Rarity.C));
        assertEquals("rare_model", defaults.lootModels().modelFor(Rarity.B));
        assertEquals("epic_model", defaults.lootModels().modelFor(Rarity.A));
        assertEquals("legendary_model", defaults.lootModels().modelFor(Rarity.S));
        assertEquals("standby", defaults.idleAnimation());
        assertEquals("open_single_event", defaults.singleOpenAnimation());
        assertEquals("open_seven_event", defaults.sevenOpenAnimation());
    }

    @Test
    void rejectsInvalidGuaranteeOrder() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("defaults.crate.guarantee-a", 90);
        config.set("defaults.crate.guarantee-s", 80);

        assertThrows(IllegalArgumentException.class, () -> CrateDefaults.load(config));
    }

    @Test
    void loadsAndValidatesDefaultLootItemScale() {
        assertEquals(0.6D, LootDisplaySettings.load(new YamlConfiguration()).defaultItemScale());

        YamlConfiguration configured = new YamlConfiguration();
        configured.set("loot-display.default-item-scale", 0.75D);
        assertEquals(0.75D, LootDisplaySettings.load(configured).defaultItemScale());

        configured.set("loot-display.default-item-scale", 0.0D);
        assertThrows(IllegalArgumentException.class, () -> LootDisplaySettings.load(configured));
        configured.set("loot-display.default-item-scale", 4.1D);
        assertThrows(IllegalArgumentException.class, () -> LootDisplaySettings.load(configured));
    }
}
