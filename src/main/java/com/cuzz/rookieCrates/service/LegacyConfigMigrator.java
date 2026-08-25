package com.cuzz.rookieCrates.service;

import com.cuzz.rookieCrates.domain.CrateDefinition;
import com.cuzz.rookieCrates.domain.Placement;
import com.cuzz.rookieCrates.domain.Rarity;
import com.cuzz.rookieCrates.domain.ScenePoint;
import com.cuzz.rookieCrates.domain.ScenePointKind;
import com.cuzz.rookieCrates.domain.SceneProfile;
import com.cuzz.rookieCrates.storage.SQLiteDatabase;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** One-time import of the prototype config.yml absolute crate/camera/loot1..loot7 points. */
public final class LegacyConfigMigrator {

    private final JavaPlugin plugin;
    private final SQLiteDatabase database;

    public LegacyConfigMigrator(JavaPlugin plugin, SQLiteDatabase database) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<Boolean> migrateIfPresent() {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("legacy-migration.import-old-scene", true)
                || config.getBoolean("legacy-migration.completed", false)) {
            return CompletableFuture.completedFuture(false);
        }
        String crateId = config.getString("legacy-migration.default-crate-id", "default")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!crateId.matches("[a-z0-9_-]{1,64}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "legacy-migration.default-crate-id is invalid"));
        }

        Optional<LegacyPoint> crate = read("crateLocations");
        Optional<LegacyPoint> camera = read("cameraLocations");
        List<LegacyPoint> loot = new ArrayList<>(7);
        for (int index = 1; index <= 7; index++) {
            Optional<LegacyPoint> point = read("loot" + index + "Locations");
            if (point.isEmpty()) {
                return crate.isEmpty() && camera.isEmpty()
                        ? CompletableFuture.completedFuture(false)
                        : CompletableFuture.failedFuture(new IllegalStateException(
                        "Legacy scene is incomplete: missing loot" + index + "Locations"));
            }
            loot.add(point.get());
        }
        if (crate.isEmpty() && camera.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        if (crate.isEmpty() || camera.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Legacy scene is incomplete: crateLocations/cameraLocations are required"));
        }

        String profileId = crateId + "_scene";
        return database.transaction(dao -> {
            if (dao.findCrate(crateId).isPresent()) {
                return false;
            }
            dao.upsertSceneProfile(new SceneProfile(
                    profileId,
                    "Legacy imported scene",
                    "default_crate",
                    "loot_white"
            ));
            dao.upsertCrate(new CrateDefinition(
                    crateId,
                    "Default Crate",
                    false,
                    null,
                    0.0D,
                    0.0D,
                    CrateDefinition.DEFAULT_GUARANTEE_A,
                    CrateDefinition.DEFAULT_GUARANTEE_S,
                    profileId,
                    Rarity.S,
                    true,
                    1.5D,
                    2.0D,
                    "idle",
                    "open2"
            ));
            dao.upsertPlacement(crate.get().toPlacement(crateId + "_main", crateId));
            dao.upsertScenePoint(crate.get().toScenePoint(profileId, ScenePointKind.CRATE, 1));
            dao.upsertScenePoint(camera.get().toScenePoint(profileId, ScenePointKind.CAMERA, 1));
            for (int index = 0; index < loot.size(); index++) {
                dao.upsertScenePoint(loot.get(index).toScenePoint(
                        profileId, ScenePointKind.LOOT, index + 1));
            }
            return true;
        }).thenApply(migrated -> {
            if (migrated) {
                plugin.getLogger().info("Imported legacy crate/camera/loot1..loot7 scene into SQLite crate '"
                        + crateId + "'. It remains disabled until keys and rewards are configured.");
            }
            return migrated;
        });
    }

    private Optional<LegacyPoint> read(String path) {
        FileConfiguration config = plugin.getConfig();
        String world = config.getString(path + ".world");
        if (world == null || world.isBlank()) {
            return Optional.empty();
        }
        for (String coordinate : List.of("x", "y", "z", "yaw", "pitch")) {
            if (!config.contains(path + "." + coordinate)) {
                throw new IllegalStateException("Legacy scene point is missing " + path + "." + coordinate);
            }
        }
        return Optional.of(new LegacyPoint(
                world,
                config.getDouble(path + ".x"),
                config.getDouble(path + ".y"),
                config.getDouble(path + ".z"),
                (float) config.getDouble(path + ".yaw"),
                (float) config.getDouble(path + ".pitch")
        ));
    }

    private record LegacyPoint(
            String world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        private Placement toPlacement(String placementId, String crateId) {
            return new Placement(placementId, crateId, world, x, y, z, yaw, pitch);
        }

        private ScenePoint toScenePoint(String profileId, ScenePointKind kind, int index) {
            return new ScenePoint(profileId, index, kind, world, x, y, z, yaw, pitch);
        }
    }
}
