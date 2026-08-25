package com.cuzz.rookieCrates.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainModelTest {
    @Test
    void rarityOrderIsHighestToLowest() {
        assertEquals(List.of(Rarity.S, Rarity.A, Rarity.B, Rarity.C), List.of(Rarity.values()));
        assertTrue(Rarity.S.isAtLeast(Rarity.S));
        assertTrue(Rarity.S.isAtLeast(Rarity.A));
        assertTrue(Rarity.A.isAtLeast(Rarity.B));
        assertFalse(Rarity.B.isAtLeast(Rarity.A));
    }

    @Test
    void crateDefensivelyCopiesKeyAndAllowsBroadcastToBeDisabled() {
        byte[] key = {1, 2, 3};
        CrateDefinition crate = new CrateDefinition(
                "basic", "Basic", true, key, 10, 60, 10, 80,
                null, null, true, 1.5, 2.0, "idle", "open2"
        );
        key[0] = 9;
        assertEquals(1, crate.keyItemBlob()[0]);
        byte[] returned = crate.keyItemBlob();
        returned[1] = 9;
        assertEquals(2, crate.keyItemBlob()[1]);
        assertNull(crate.broadcastRarity());
    }

    @Test
    void onlySingleOrSevenDrawTransactionsAreValid() {
        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        OpenTransaction single = new OpenTransaction(
                id, player, "basic", 1, OpenTransactionStatus.PENDING, 1, null, null
        );
        OpenTransaction seven = new OpenTransaction(
                UUID.randomUUID(), player, "basic", 7, OpenTransactionStatus.PENDING, 1, null, null
        );

        assertEquals(1, single.drawCount());
        assertEquals(7, seven.drawCount());
        assertThrows(IllegalArgumentException.class, () -> new OpenTransaction(
                UUID.randomUUID(), player, "basic", 0, OpenTransactionStatus.PENDING, 1, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new OpenTransaction(
                UUID.randomUUID(), player, "basic", 2, OpenTransactionStatus.PENDING, 1, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new OpenTransaction(
                UUID.randomUUID(), player, "basic", 10, OpenTransactionStatus.PENDING, 1, null, null
        ));
    }

    @Test
    void sceneHasExactlySevenLootSlots() {
        assertThrows(IllegalArgumentException.class, () -> new ScenePoint(
                "default", 8, ScenePointKind.LOOT, "world", 0, 0, 0, 0, 0
        ));
    }
}
