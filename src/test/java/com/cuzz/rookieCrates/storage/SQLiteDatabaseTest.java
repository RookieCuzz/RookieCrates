package com.cuzz.rookieCrates.storage;

import com.cuzz.rookieCrates.domain.ActiveSceneRecovery;
import com.cuzz.rookieCrates.domain.CrateDefinition;
import com.cuzz.rookieCrates.domain.OpenResult;
import com.cuzz.rookieCrates.domain.OpenTransaction;
import com.cuzz.rookieCrates.domain.OpenTransactionStatus;
import com.cuzz.rookieCrates.domain.PendingDelivery;
import com.cuzz.rookieCrates.domain.PendingDeliveryStatus;
import com.cuzz.rookieCrates.domain.Placement;
import com.cuzz.rookieCrates.domain.Rarity;
import com.cuzz.rookieCrates.domain.RewardBundle;
import com.cuzz.rookieCrates.domain.RewardCommand;
import com.cuzz.rookieCrates.domain.RewardDefinition;
import com.cuzz.rookieCrates.domain.RewardItem;
import com.cuzz.rookieCrates.domain.ScenePoint;
import com.cuzz.rookieCrates.domain.ScenePointKind;
import com.cuzz.rookieCrates.domain.SceneProfile;
import com.cuzz.rookieCrates.runtime.recovery.SceneRecovery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteDatabaseTest {
    @TempDir
    Path tempDirectory;

    private SQLiteDatabase database;

    @BeforeEach
    void setUp() throws SQLException {
        database = new SQLiteDatabase(tempDirectory.resolve("rookie-crates.db"));
        database.start();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void migratesOnceAndPersistsCompleteConfiguration() throws Exception {
        database.transaction(dao -> {
            seedCrate(dao);
            RewardDefinition definition = new RewardDefinition(
                    "diamond", "basic", "Diamond", "A diamond", Rarity.S, 1.0, true, true
            );
            dao.replaceRewardBundle(new RewardBundle(
                    definition,
                    List.of(new RewardItem(0, "diamond", new byte[]{9, 8, 7}, 2)),
                    List.of(new RewardCommand(0, "diamond", "say {player}", 0))
            ));
            dao.upsertPlacement(new Placement("spawn", "basic", "world", 1, 2, 3, 90, 0));
            for (int index = 1; index <= 7; index++) {
                dao.upsertScenePoint(new ScenePoint(
                        "default", index, ScenePointKind.LOOT, "world", index, 70, 0, 0, 0
                ));
            }
            return null;
        }).join();

        assertEquals(3, database.submit(RookieCratesDao::schemaVersion).join());
        RewardBundle reward = database.submit(dao -> dao.findReward("diamond").orElseThrow()).join();
        assertTrue(reward.definition().broadcast());
        assertArrayEquals(new byte[]{9, 8, 7}, reward.items().getFirst().itemBlob());
        assertEquals(7, database.submit(dao -> dao.listScenePoints("default").size()).join());
        assertEquals(1, database.submit(dao -> dao.listAllPlacements().size()).join());

        database.close();
        database = new SQLiteDatabase(tempDirectory.resolve("rookie-crates.db"));
        database.start();
        assertEquals(3, database.submit(RookieCratesDao::schemaVersion).join());
        assertEquals(1, database.submit(dao -> dao.listAllRewards().size()).join());
    }

    @Test
    void foreignKeysAndTransactionsPreventPartialConfiguration() {
        CompletionException foreignKeyFailure = assertThrows(CompletionException.class, () ->
                database.run(dao -> dao.upsertReward(new RewardDefinition(
                        "orphan", "missing", "Orphan", "", Rarity.C, 1, true
                ))).join()
        );
        assertInstanceOf(SQLException.class, foreignKeyFailure.getCause());

        CompletionException rollback = assertThrows(CompletionException.class, () ->
                database.transaction(dao -> {
                    seedCrate(dao);
                    throw new SQLException("force rollback");
                }).join()
        );
        assertEquals("force rollback", rollback.getCause().getMessage());
        assertTrue(database.submit(RookieCratesDao::listCrates).join().isEmpty());
    }

    @Test
    void mixedVirtualAndPhysicalKeyPlanCanConsumeAnyVirtualRemainder() {
        UUID player = UUID.randomUUID();
        database.transaction(dao -> {
            seedCrate(dao);
            dao.addVirtualKeys(player, "basic", 7, 10);
            assertTrue(dao.consumeVirtualKeys(player, "basic", 3, 11));
            assertEquals(4, dao.findPlayerState(player, "basic").orElseThrow().virtualKeys());
            assertTrue(dao.consumeVirtualKeys(player, "basic", 4, 12));
            assertFalse(dao.consumeVirtualKeys(player, "basic", 1, 13));
            return null;
        }).join();
        assertEquals(0, database.submit(dao ->
                dao.findPlayerState(player, "basic").orElseThrow().virtualKeys()).join());
    }

    @Test
    void deliveryRemainderAndFullPlayerRecoveryRoundTrip() {
        UUID player = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        long deliveryId = database.transaction(dao -> {
            seedCrate(dao);
            dao.upsertReward(new RewardDefinition(
                    "stone", "basic", "Stone", "", Rarity.C, 10, true
            ));
            dao.createTransaction(new OpenTransaction(
                    transactionId, player, "basic", 7, OpenTransactionStatus.PENDING, 100, null, null
            ));
            for (int index = 1; index <= 7; index++) {
                dao.addOpenResult(new OpenResult(transactionId, index, "stone", Rarity.C, false));
            }
            long id = dao.enqueueDelivery(new PendingDelivery(
                    0, transactionId, 1, player, "stone", new byte[]{1, 2}, 64,
                    null, PendingDeliveryStatus.PENDING, 0, null, 100, null
            ));
            dao.saveActiveSceneRecovery(new ActiveSceneRecovery(
                    player, transactionId, worldId, "world", 1, 2, 3, 45, 10,
                    "SURVIVAL", true, true, 0.2f, 0.1f, true, false,
                    UUID.randomUUID(), 101
            ));
            return id;
        }).join();

        database.run(dao -> dao.updatePendingDeliveryRemaining(
                deliveryId, 17, 1, "inventory full"
        )).join();
        PendingDelivery remainder = database.submit(dao -> dao.findDelivery(deliveryId).orElseThrow()).join();
        assertEquals(17, remainder.amount());
        assertEquals(1, remainder.attempts());
        assertEquals(PendingDeliveryStatus.PENDING, remainder.status());
        assertEquals(worldId, database.submit(dao ->
                dao.findActiveSceneRecovery(player).orElseThrow().worldUuid()).join());
        assertEquals(7, database.submit(dao -> dao.listOpenResults(transactionId).size()).join());

        database.run(dao -> {
            dao.markPendingDeliveryDelivered(deliveryId, 200);
            dao.markOpenResultDelivered(transactionId, 1);
            dao.deleteActiveSceneRecovery(player, transactionId);
        }).join();
        assertEquals(PendingDeliveryStatus.DELIVERED,
                database.submit(dao -> dao.findDelivery(deliveryId).orElseThrow().status()).join());
        assertTrue(database.submit(dao -> dao.findActiveSceneRecovery(player).isEmpty()).join());
    }

    @Test
    void synchronousDaoRejectsMainThreadAccess() {
        assertThrows(IllegalStateException.class, () -> database.dao().listCrates());
    }

    @Test
    void sqliteConstraintRejectsTenDrawTransactionEvenWhenDomainIsBypassed() throws Exception {
        Path databasePath = tempDirectory.resolve("rookie-crates.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO open_transactions(
                         id, player_uuid, crate_id, draw_count, status, created_at
                     ) VALUES (?, ?, ?, ?, 'PENDING', ?)
                     """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, UUID.randomUUID().toString());
            statement.setString(3, "basic");
            statement.setInt(4, 10);
            statement.setLong(5, System.currentTimeMillis());

            SQLException failure = assertThrows(SQLException.class, statement::executeUpdate);
            assertTrue(failure.getMessage().contains("CHECK constraint failed"));
        }
    }

    @Test
    void runtimeRecoveryAdapterPreservesSpeedOrderingAndSpectatorTarget() {
        UUID player = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID spectator = UUID.randomUUID();
        database.transaction(dao -> {
            seedCrate(dao);
            dao.createTransaction(new OpenTransaction(
                    transactionId, player, "basic", 1, OpenTransactionStatus.PENDING, 10, null, null
            ));
            return null;
        }).join();
        SQLiteSceneRecoveryStore store = new SQLiteSceneRecoveryStore(database);
        SceneRecovery expected = new SceneRecovery(
                player, transactionId, UUID.randomUUID().toString(), "world",
                1, 2, 3, 4, 5, "ADVENTURE", true, false,
                0.15f, 0.35f, false, true, spectator.toString()
        );
        store.save(expected).join();
        assertEquals(expected, store.find(player).join().orElseThrow());
        assertEquals(List.of(expected), store.findAll().join().stream().toList());

        // APPLIED is the durable idempotency boundary: even if the later delete fails,
        // the old snapshot is no longer returned on the player's next login.
        store.markApplied(player, transactionId).join();
        assertTrue(store.find(player).join().isEmpty());
        assertTrue(store.findAll().join().isEmpty());
        store.save(expected).join();
        assertTrue(store.find(player).join().isEmpty(),
                "a duplicate save for the same transaction must not resurrect an applied snapshot");

        UUID newerTransactionId = UUID.randomUUID();
        database.run(dao -> dao.createTransaction(new OpenTransaction(
                newerTransactionId, player, "basic", 7,
                OpenTransactionStatus.PENDING, 11, null, null
        ))).join();
        SceneRecovery newer = new SceneRecovery(
                player, newerTransactionId, expected.worldId(), expected.worldName(),
                10, 20, 30, 40, 50, "SURVIVAL", false, false,
                0.2f, 0.1f, false, true, null
        );
        store.save(newer).join();
        store.delete(player, transactionId).join();
        assertEquals(newer, store.find(player).join().orElseThrow(),
                "a stale delete must not remove a newer scene snapshot");
        store.markApplied(player, newerTransactionId).join();
        store.delete(player, newerTransactionId).join();
    }

    @Test
    void claimableDeliveryPagesAreTransactionScopedItemFirstAndExcludeFailedRows() {
        UUID player = UUID.randomUUID();
        UUID firstTransaction = UUID.randomUUID();
        UUID secondTransaction = UUID.randomUUID();
        long[] ids = database.transaction(dao -> {
            seedCrate(dao);
            dao.createTransaction(new OpenTransaction(
                    firstTransaction, player, "basic", 1,
                    OpenTransactionStatus.DELIVERING, 100, null, null
            ));
            dao.createTransaction(new OpenTransaction(
                    secondTransaction, player, "basic", 1,
                    OpenTransactionStatus.DELIVERING, 101, null, null
            ));
            dao.addOpenResult(new OpenResult(firstTransaction, 1, "first", Rarity.A, false));
            dao.addOpenResult(new OpenResult(secondTransaction, 1, "second", Rarity.B, false));

            long firstCommand = dao.enqueueDelivery(new PendingDelivery(
                    0, firstTransaction, 1, player, "first", null, 0,
                    "say first", PendingDeliveryStatus.PENDING, 0, null, 100, null
            ));
            long firstItem = dao.enqueueDelivery(new PendingDelivery(
                    0, firstTransaction, 1, player, "first", new byte[]{1}, 1,
                    null, PendingDeliveryStatus.PENDING, 0, null, 100, null
            ));
            long failedCommand = dao.enqueueDelivery(new PendingDelivery(
                    0, firstTransaction, 1, player, "first", null, 0,
                    "say uncertain", PendingDeliveryStatus.PENDING, 0, null, 100, null
            ));
            long secondItem = dao.enqueueDelivery(new PendingDelivery(
                    0, secondTransaction, 1, player, "second", new byte[]{2}, 1,
                    null, PendingDeliveryStatus.PENDING, 0, null, 101, null
            ));
            assertTrue(dao.markPendingDeliveryFailed(
                    failedCommand, "MANUAL_REVIEW: command returned false"
            ));
            return new long[]{firstCommand, firstItem, failedCommand, secondItem};
        }).join();

        List<PendingDelivery> firstPage = database.submit(dao ->
                dao.listClaimableDeliveries(player, firstTransaction, 0, 0, 1)
        ).join();
        assertEquals(List.of(ids[1]), firstPage.stream().map(PendingDelivery::id).toList(),
                "item rows must be selected before commands even when their ids are newer");

        List<PendingDelivery> secondPage = database.submit(dao ->
                dao.listClaimableDeliveries(player, firstTransaction, 0, ids[1], 1)
        ).join();
        assertEquals(List.of(ids[0]), secondPage.stream().map(PendingDelivery::id).toList());

        List<PendingDelivery> allTransactions = database.submit(dao ->
                dao.listClaimableDeliveries(player, 0, 0, 10)
        ).join();
        assertEquals(List.of(ids[1], ids[3], ids[0]),
                allTransactions.stream().map(PendingDelivery::id).toList());
        assertFalse(allTransactions.stream().anyMatch(delivery -> delivery.id() == ids[2]),
                "FAILED/manual-review rows must never be picked up by a normal claim");
    }

    @Test
    void keysetClaimPaginationVisitsEveryRowBeyondTheFormerBatchCap() {
        UUID player = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        int expectedRows = 2_137;
        database.transaction(dao -> {
            seedCrate(dao);
            dao.createTransaction(new OpenTransaction(
                    transactionId, player, "basic", 1,
                    OpenTransactionStatus.DELIVERING, 100, null, null
            ));
            dao.addOpenResult(new OpenResult(transactionId, 1, "bulk", Rarity.B, false));
            for (int index = 0; index < expectedRows; index++) {
                dao.enqueueDelivery(new PendingDelivery(
                        0, transactionId, 1, player, "bulk", new byte[]{1}, 1,
                        null, PendingDeliveryStatus.PENDING, 0, null, 100 + index, null
                ));
            }
            return null;
        }).join();

        long afterId = 0;
        int visited = 0;
        while (true) {
            long cursor = afterId;
            List<PendingDelivery> page = database.submit(dao ->
                    dao.listClaimableDeliveries(player, transactionId, 0, cursor, 128)
            ).join();
            if (page.isEmpty()) {
                break;
            }
            assertTrue(page.getFirst().id() > afterId);
            afterId = page.getLast().id();
            visited += page.size();
        }
        assertEquals(expectedRows, visited);
    }

    @Test
    void deliveryCompletionRequiresDeliveringStateAndNoOutstandingRows() {
        UUID player = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        UUID wrongState = UUID.randomUUID();
        UUID manualReview = UUID.randomUUID();
        UUID noPayload = UUID.randomUUID();
        int completedCount = database.transaction(dao -> {
            seedCrate(dao);
            dao.createTransaction(new OpenTransaction(
                    completed, player, "basic", 1,
                    OpenTransactionStatus.DELIVERING, 100, null, null
            ));
            dao.addOpenResult(new OpenResult(completed, 1, "done", Rarity.A, false));
            long deliveredRow = dao.enqueueDelivery(new PendingDelivery(
                    0, completed, 1, player, "done", new byte[]{1}, 1,
                    null, PendingDeliveryStatus.PENDING, 0, null, 100, null
            ));
            assertTrue(dao.markPendingDeliveryDelivered(deliveredRow, 150));

            dao.createTransaction(new OpenTransaction(
                    wrongState, player, "basic", 1,
                    OpenTransactionStatus.DRAWN, 101, null, null
            ));
            dao.addOpenResult(new OpenResult(wrongState, 1, "drawn", Rarity.B, true));

            dao.createTransaction(new OpenTransaction(
                    manualReview, player, "basic", 1,
                    OpenTransactionStatus.DELIVERING, 102, null, null
            ));
            dao.addOpenResult(new OpenResult(manualReview, 1, "unknown", Rarity.S, false));
            long failedRow = dao.enqueueDelivery(new PendingDelivery(
                    0, manualReview, 1, player, "unknown", null, 0,
                    "grant side-effect", PendingDeliveryStatus.PENDING, 0, null, 102, null
            ));
            assertTrue(dao.markPendingDeliveryFailed(failedRow, "MANUAL_REVIEW: uncertain"));

            dao.createTransaction(new OpenTransaction(
                    noPayload, player, "basic", 1,
                    OpenTransactionStatus.DELIVERING, 103, null, null
            ));
            dao.addOpenResult(new OpenResult(noPayload, 1, "empty", Rarity.C, false));
            return dao.completeDeliverableTransactions(player, 200);
        }).join();

        assertEquals(2, completedCount);
        assertEquals(OpenTransactionStatus.COMPLETED, database.submit(dao ->
                dao.findTransaction(completed).orElseThrow().status()).join());
        assertEquals(OpenTransactionStatus.COMPLETED, database.submit(dao ->
                dao.findTransaction(noPayload).orElseThrow().status()).join());
        assertEquals(OpenTransactionStatus.DRAWN, database.submit(dao ->
                dao.findTransaction(wrongState).orElseThrow().status()).join());
        assertEquals(OpenTransactionStatus.DELIVERING, database.submit(dao ->
                dao.findTransaction(manualReview).orElseThrow().status()).join());
        assertFalse(database.submit(dao ->
                dao.listOpenResults(manualReview).getFirst().delivered()).join());
    }

    @Test
    void recoverableTransactionsBecomeClaimableOnlyAfterSceneRecoveryIsApplied() {
        UUID player = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID recoveryPlayer = UUID.randomUUID();
        UUID recoveryTransactionId = UUID.randomUUID();
        database.transaction(dao -> {
            seedCrate(dao);
            dao.createTransaction(new OpenTransaction(
                    transactionId, player, "basic", 1,
                    OpenTransactionStatus.DRAWN, 100, null, null
            ));
            dao.addOpenResult(new OpenResult(transactionId, 1, "reward", Rarity.A, false));
            dao.createTransaction(new OpenTransaction(
                    recoveryTransactionId, recoveryPlayer, "basic", 1,
                    OpenTransactionStatus.RECOVERY_REQUIRED, 100, null, "player unavailable"
            ));
            dao.addOpenResult(new OpenResult(
                    recoveryTransactionId, 1, "recovery-reward", Rarity.B, false
            ));
            dao.saveActiveSceneRecovery(new ActiveSceneRecovery(
                    player, transactionId, UUID.randomUUID(), "world",
                    1, 2, 3, 0, 0, "SURVIVAL", false, false,
                    0.2f, 0.1f, false, true, null, 100
            ));
            dao.saveActiveSceneRecovery(new ActiveSceneRecovery(
                    recoveryPlayer, recoveryTransactionId, UUID.randomUUID(), "world",
                    4, 5, 6, 0, 0, "SURVIVAL", false, false,
                    0.2f, 0.1f, false, true, null, 100
            ));
            assertEquals(0, dao.prepareTransactionForDelivery(player, transactionId));
            assertEquals(0, dao.prepareTransactionForDelivery(recoveryPlayer, recoveryTransactionId));
            assertEquals(OpenTransactionStatus.DRAWN,
                    dao.findTransaction(transactionId).orElseThrow().status());
            assertEquals(OpenTransactionStatus.RECOVERY_REQUIRED,
                    dao.findTransaction(recoveryTransactionId).orElseThrow().status());
            assertTrue(dao.markActiveSceneRecoveryApplied(player, transactionId, 101));
            assertTrue(dao.markActiveSceneRecoveryApplied(recoveryPlayer, recoveryTransactionId, 101));
            assertEquals(1, dao.prepareTransactionForDelivery(player, transactionId));
            assertEquals(1, dao.prepareTransactionForDelivery(recoveryPlayer, recoveryTransactionId));
            return null;
        }).join();

        assertEquals(OpenTransactionStatus.DELIVERING, database.submit(dao ->
                dao.findTransaction(transactionId).orElseThrow().status()).join());
        assertEquals(OpenTransactionStatus.DELIVERING, database.submit(dao ->
                dao.findTransaction(recoveryTransactionId).orElseThrow().status()).join());
    }

    @Test
    void outstandingResultCountIsScopedPerCrateAndDistinctPerResult() {
        UUID player = UUID.randomUUID();
        UUID basicTransaction = UUID.randomUUID();
        UUID otherTransaction = UUID.randomUUID();
        long[] basicRows = database.transaction(dao -> {
            seedCrate(dao);
            dao.upsertCrate(new CrateDefinition(
                    "other", "Other", true, new byte[]{2}, 25, 150,
                    10, 80, "default", Rarity.S, true,
                    1.5, 2.0, "idle", "open1", "open7"
            ));
            dao.createTransaction(new OpenTransaction(
                    basicTransaction, player, "basic", 1,
                    OpenTransactionStatus.DELIVERING, 100, null, null
            ));
            dao.addOpenResult(new OpenResult(basicTransaction, 1, "basic_reward", Rarity.A, false));
            long item = dao.enqueueDelivery(new PendingDelivery(
                    0, basicTransaction, 1, player, "basic_reward", new byte[]{1}, 1,
                    null, PendingDeliveryStatus.PENDING, 0, null, 100, null
            ));
            long command = dao.enqueueDelivery(new PendingDelivery(
                    0, basicTransaction, 1, player, "basic_reward", null, 0,
                    "say basic", PendingDeliveryStatus.PENDING, 0, null, 100, null
            ));

            dao.createTransaction(new OpenTransaction(
                    otherTransaction, player, "other", 1,
                    OpenTransactionStatus.DELIVERING, 101, null, null
            ));
            dao.addOpenResult(new OpenResult(otherTransaction, 1, "other_reward", Rarity.B, false));
            dao.enqueueDelivery(new PendingDelivery(
                    0, otherTransaction, 1, player, "other_reward", new byte[]{2}, 1,
                    null, PendingDeliveryStatus.PENDING, 0, null, 101, null
            ));
            return new long[]{item, command};
        }).join();

        assertEquals(1, database.submit(dao -> dao.countUndeliveredResults(player, "basic")).join());
        assertEquals(1, database.submit(dao -> dao.countUndeliveredResults(player, "other")).join());
        database.run(dao -> dao.markPendingDeliveryDelivered(basicRows[0], 200)).join();
        assertEquals(1, database.submit(dao -> dao.countUndeliveredResults(player, "basic")).join(),
                "two pending rows for one result must still count as one result");
        database.run(dao -> dao.markPendingDeliveryDelivered(basicRows[1], 201)).join();
        assertEquals(0, database.submit(dao -> dao.countUndeliveredResults(player, "basic")).join());
        assertEquals(1, database.submit(dao -> dao.countUndeliveredResults(player, "other")).join());
    }

    private static void seedCrate(RookieCratesDao dao) throws SQLException {
        dao.upsertSceneProfile(new SceneProfile("default", "Default", "default_crate", "loot_white"));
        dao.upsertCrate(new CrateDefinition(
                "basic", "Basic", true, new byte[]{1}, 100, 600,
                10, 80, "default", Rarity.S, true,
                1.5, 2.0, "idle", "open1", "open7"
        ));
    }
}
