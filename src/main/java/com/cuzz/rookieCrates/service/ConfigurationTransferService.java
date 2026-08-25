package com.cuzz.rookieCrates.service;

import com.cuzz.rookieCrates.domain.CrateDefinition;
import com.cuzz.rookieCrates.domain.Placement;
import com.cuzz.rookieCrates.domain.PlayerCrateState;
import com.cuzz.rookieCrates.domain.RewardBundle;
import com.cuzz.rookieCrates.domain.ScenePoint;
import com.cuzz.rookieCrates.domain.SceneProfile;
import com.cuzz.rookieCrates.storage.SQLiteDatabase;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** JSON backup/merge import for authoritative crate configuration and player counters. */
public final class ConfigurationTransferService {

    public static final int FORMAT_VERSION = 1;

    private final SQLiteDatabase database;
    private final Path exportFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public ConfigurationTransferService(SQLiteDatabase database, Path dataFolder) {
        this.database = Objects.requireNonNull(database, "database");
        this.exportFile = Objects.requireNonNull(dataFolder, "dataFolder")
                .resolve("exports")
                .resolve("rookiecrates-export.json")
                .toAbsolutePath()
                .normalize();
    }

    public Path exportFile() {
        return exportFile;
    }

    public CompletableFuture<Path> exportAll() {
        return database.submit(dao -> new ConfigurationSnapshot(
                FORMAT_VERSION,
                System.currentTimeMillis(),
                dao.listSceneProfiles(),
                dao.listAllScenePoints(),
                dao.listCrates(),
                dao.listAllRewards(),
                dao.listAllPlacements(),
                dao.listAllPlayerStates()
        )).thenApplyAsync(snapshot -> {
            Path temporary = null;
            try {
                Files.createDirectories(exportFile.getParent());
                temporary = Files.createTempFile(exportFile.getParent(), "rookiecrates-export-", ".tmp");
                Files.writeString(
                        temporary,
                        gson.toJson(snapshot),
                        StandardCharsets.UTF_8
                );
                try {
                    Files.move(temporary, exportFile,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, exportFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return exportFile;
            } catch (IOException exception) {
                throw new CompletionException(exception);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                        // The successful move already removed it; a failed cleanup is harmless.
                    }
                }
            }
        });
    }

    public CompletableFuture<ImportReport> importAll() {
        return CompletableFuture.supplyAsync(() -> {
            final ConfigurationSnapshot snapshot;
            try {
                if (!Files.isRegularFile(exportFile)) {
                    throw new IOException("导入文件不存在: " + exportFile);
                }
                snapshot = gson.fromJson(
                        Files.readString(exportFile, StandardCharsets.UTF_8),
                        ConfigurationSnapshot.class
                );
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
            if (snapshot == null || snapshot.formatVersion() != FORMAT_VERSION) {
                throw new IllegalArgumentException("不支持的导出文件版本。" );
            }
            return snapshot;
        }).thenCompose(snapshot -> database.transaction(dao -> {
            List<SceneProfile> profiles = safe(snapshot.sceneProfiles());
            List<ScenePoint> points = safe(snapshot.scenePoints());
            List<CrateDefinition> crates = safe(snapshot.crates());
            List<RewardBundle> rewards = safe(snapshot.rewards());
            List<Placement> placements = safe(snapshot.placements());
            List<PlayerCrateState> states = safe(snapshot.playerStates());
            for (SceneProfile profile : profiles) {
                dao.upsertSceneProfile(profile);
            }
            for (CrateDefinition crate : crates) {
                dao.upsertCrate(crate);
            }
            for (RewardBundle reward : rewards) {
                dao.replaceRewardBundle(reward);
            }
            for (Placement placement : placements) {
                dao.upsertPlacement(placement);
            }
            for (ScenePoint point : points) {
                dao.upsertScenePoint(point);
            }
            for (PlayerCrateState state : states) {
                dao.updatePlayerState(state);
            }
            return new ImportReport(
                    crates.size(),
                    rewards.size(),
                    placements.size(),
                    profiles.size(),
                    points.size(),
                    states.size()
            );
        }));
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    public record ImportReport(
            int crates,
            int rewards,
            int placements,
            int sceneProfiles,
            int scenePoints,
            int playerStates
    ) { }

    private record ConfigurationSnapshot(
            int formatVersion,
            long exportedAt,
            List<SceneProfile> sceneProfiles,
            List<ScenePoint> scenePoints,
            List<CrateDefinition> crates,
            List<RewardBundle> rewards,
            List<Placement> placements,
            List<PlayerCrateState> playerStates
    ) { }
}
