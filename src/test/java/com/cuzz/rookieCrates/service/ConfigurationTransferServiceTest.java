package com.cuzz.rookieCrates.service;

import com.cuzz.rookieCrates.domain.CrateDefinition;
import com.cuzz.rookieCrates.domain.Placement;
import com.cuzz.rookieCrates.domain.PlayerCrateState;
import com.cuzz.rookieCrates.domain.Rarity;
import com.cuzz.rookieCrates.domain.RewardBundle;
import com.cuzz.rookieCrates.domain.RewardCommand;
import com.cuzz.rookieCrates.domain.RewardDefinition;
import com.cuzz.rookieCrates.domain.RewardItem;
import com.cuzz.rookieCrates.domain.ScenePoint;
import com.cuzz.rookieCrates.domain.ScenePointKind;
import com.cuzz.rookieCrates.domain.SceneProfile;
import com.cuzz.rookieCrates.storage.SQLiteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationTransferServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsAndImportsConfigurationAndPlayerCounters() throws Exception {
        Path dataFolder = temporaryDirectory.resolve("plugin-data");
        UUID playerId = UUID.randomUUID();
        try (SQLiteDatabase source = new SQLiteDatabase(temporaryDirectory.resolve("source.db"))) {
            source.start();
            source.transaction(dao -> {
                dao.upsertSceneProfile(new SceneProfile(
                        "alpha_scene", "Alpha", "default_crate", "loot_white", "crate_intro"
                ));
                dao.upsertCrate(new CrateDefinition(
                        "alpha", "Alpha Crate", true, new byte[]{1, 2, 3},
                        25.0D, 150.0D, 10, 80, "alpha_scene", Rarity.S,
                        true, 1.5D, 2.0D, "idle", "open1", "open7"
                ));
                dao.replaceRewardBundle(new RewardBundle(
                        new RewardDefinition("diamond", "alpha", "Diamond", "", Rarity.S,
                                1.0D, 0.75D, true, true),
                        List.of(new RewardItem(0, "diamond", new byte[]{9, 8, 7}, 3)),
                        List.of(new RewardCommand(0, "diamond", "say {player}", 0))
                ));
                dao.upsertPlacement(new Placement("alpha_main", "alpha", "world", 1, 2, 3, 4, 5));
                dao.upsertScenePoint(new ScenePoint(
                        "alpha_scene", 1, ScenePointKind.CRATE, "world", 1, 2, 3, 4, 5));
                dao.updatePlayerState(new PlayerCrateState(playerId, "alpha", 7, 9, 79, 123, 456));
                return null;
            }).get(5, TimeUnit.SECONDS);

            ConfigurationTransferService service = new ConfigurationTransferService(source, dataFolder);
            Path exported = service.exportAll().get(5, TimeUnit.SECONDS);
            assertTrue(Files.isRegularFile(exported));
            assertTrue(Files.readString(exported).contains("sevenPrice"));
        }

        try (SQLiteDatabase destination = new SQLiteDatabase(temporaryDirectory.resolve("destination.db"))) {
            destination.start();
            ConfigurationTransferService service = new ConfigurationTransferService(destination, dataFolder);
            ConfigurationTransferService.ImportReport report = service.importAll().get(5, TimeUnit.SECONDS);
            assertEquals(1, report.crates());
            assertEquals(1, report.rewards());
            assertEquals(1, report.playerStates());

            destination.submit(dao -> {
                CrateDefinition crate = dao.findCrate("alpha").orElseThrow();
                assertEquals(150.0D, crate.sevenPrice());
                assertArrayEquals(new byte[]{1, 2, 3}, crate.keyItemBlob());
                assertEquals("crate_intro", dao.findSceneProfile("alpha_scene")
                        .orElseThrow().serverToursRoute());
                RewardBundle reward = dao.findReward("diamond").orElseThrow();
                assertEquals(0.75D, reward.definition().displayScale());
                assertEquals(3, reward.items().getFirst().amount());
                assertEquals("say {player}", reward.commands().getFirst().command());
                assertEquals(7, dao.findPlayerState(playerId, "alpha").orElseThrow().virtualKeys());
                return null;
            }).get(5, TimeUnit.SECONDS);
        }
    }
}
