package com.cuzz.rookieCrates.config;

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
        assertEquals("loot_white", defaults.lootModel());
        assertEquals("idle", defaults.idleAnimation());
        assertEquals("open2", defaults.openAnimation());
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
        config.set("defaults.crate.models.loot", "loot_pink");
        config.set("defaults.crate.animations.idle", "standby");
        config.set("defaults.crate.animations.open", "open_event");

        CrateDefaults defaults = CrateDefaults.load(config);

        assertEquals(25.0D, defaults.singlePrice());
        assertEquals(150.0D, defaults.sevenPrice());
        assertEquals(8, defaults.guaranteeA());
        assertEquals(60, defaults.guaranteeS());
        assertEquals("event_crate", defaults.crateModel());
        assertEquals("loot_pink", defaults.lootModel());
        assertEquals("standby", defaults.idleAnimation());
        assertEquals("open_event", defaults.openAnimation());
    }

    @Test
    void rejectsInvalidGuaranteeOrder() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("defaults.crate.guarantee-a", 90);
        config.set("defaults.crate.guarantee-s", 80);

        assertThrows(IllegalArgumentException.class, () -> CrateDefaults.load(config));
    }
}
