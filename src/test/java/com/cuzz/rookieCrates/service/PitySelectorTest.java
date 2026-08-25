package com.cuzz.rookieCrates.service;

import com.cuzz.rookieCrates.domain.Rarity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PitySelectorTest {

    private final List<PitySelector.Candidate<String>> pool = List.of(
            new PitySelector.Candidate<>("s", Rarity.S, 1.0D),
            new PitySelector.Candidate<>("a", Rarity.A, 10.0D),
            new PitySelector.Candidate<>("b", Rarity.B, 1000.0D)
    );

    @Test
    void tenthDrawForcesAOrBetter() {
        PitySelector<String> selector = new PitySelector<>(new Random(1));
        PitySelector.Draw<String> draw = selector.draw(
                pool,
                new PitySelector.Progress(9, 9),
                new PitySelector.Policy(10, 80)
        );
        assertTrue(draw.candidate().rarity().isAtLeast(Rarity.A));
        assertEquals(Rarity.A, draw.forcedRarity());
    }

    @Test
    void eightiethDrawForcesSAndResetsBothCounters() {
        PitySelector<String> selector = new PitySelector<>(new Random(2));
        PitySelector.Draw<String> draw = selector.draw(
                pool,
                new PitySelector.Progress(3, 79),
                new PitySelector.Policy(10, 80)
        );
        assertEquals(Rarity.S, draw.candidate().rarity());
        assertEquals(new PitySelector.Progress(0, 0), draw.nextProgress());
    }

    @Test
    void sevenDrawsAdvancePitySequentially() {
        PitySelector<String> selector = new PitySelector<>(new Random(3));
        List<PitySelector.Draw<String>> results = selector.drawMany(
                List.of(new PitySelector.Candidate<>("b", Rarity.B, 1.0D),
                        new PitySelector.Candidate<>("a", Rarity.A, 1.0E-100D),
                        new PitySelector.Candidate<>("s", Rarity.S, 1.0E-200D)),
                new PitySelector.Progress(6, 74),
                new PitySelector.Policy(10, 80),
                7
        );

        assertEquals(7, results.size());
        assertEquals(
                Arrays.asList(null, null, null, Rarity.A, null, Rarity.S, null),
                results.stream().map(PitySelector.Draw::forcedRarity).toList()
        );
        assertEquals(
                List.of(Rarity.B, Rarity.B, Rarity.B, Rarity.A, Rarity.B, Rarity.S, Rarity.B),
                results.stream().map(draw -> draw.candidate().rarity()).toList()
        );
        assertEquals(
                List.of(
                        new PitySelector.Progress(7, 75),
                        new PitySelector.Progress(8, 76),
                        new PitySelector.Progress(9, 77),
                        new PitySelector.Progress(0, 78),
                        new PitySelector.Progress(1, 79),
                        new PitySelector.Progress(0, 0),
                        new PitySelector.Progress(1, 1)
                ),
                results.stream().map(PitySelector.Draw::nextProgress).toList()
        );
    }

    @Test
    void rejectsInvalidWeights() {
        PitySelector<String> selector = new PitySelector<>(new Random(4));
        assertThrows(IllegalArgumentException.class, () -> selector.draw(
                List.of(new PitySelector.Candidate<>("bad", Rarity.C, 0.0D)),
                new PitySelector.Progress(0, 0),
                new PitySelector.Policy(10, 80)
        ));
    }
}
