package com.cuzz.rookieCrates.runtime;

import com.cuzz.rookieCrates.domain.Rarity;
import com.cuzz.rookieCrates.domain.RewardBundle;
import com.cuzz.rookieCrates.domain.RewardDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LootPreviewControllerTest {

    @Test
    void previewAlwaysSelectsSevenWeightedRewards() {
        RewardBundle common = reward("common", Rarity.C, 10.0D);
        RewardBundle rare = reward("rare", Rarity.S, 1.0D);

        List<RewardBundle> selected = LootPreviewController.selectPreviewRewards(
                List.of(common, rare), new Random(42L));

        assertEquals(7, selected.size());
        selected.forEach(reward -> assertEquals("basic", reward.definition().crateId()));
    }

    @Test
    void previewRejectsNonPositiveWeights() {
        assertThrows(IllegalArgumentException.class, () ->
                LootPreviewController.selectPreviewRewards(
                        List.of(reward("invalid", Rarity.C, 0.0D)), new Random(1L)));
    }

    private static RewardBundle reward(String id, Rarity rarity, double weight) {
        return new RewardBundle(
                new RewardDefinition(id, "basic", id, "", rarity, weight, true, false),
                List.of(),
                List.of()
        );
    }
}
