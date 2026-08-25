package com.cuzz.rookieCrates.service;

import com.cuzz.rookieCrates.domain.Rarity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Pure, deterministic-when-seeded weighted selection with independent A/S pity counters. */
public final class PitySelector<T> {

    private final RandomGenerator random;

    public PitySelector(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public Draw<T> draw(List<Candidate<T>> pool, Progress progress, Policy policy) {
        validate(pool, policy);
        Rarity forced = null;
        List<Candidate<T>> eligible = pool;

        if (progress.sinceS() + 1 >= policy.guaranteeS()) {
            forced = Rarity.S;
            eligible = eligibleAtLeast(pool, Rarity.S);
        } else if (progress.sinceA() + 1 >= policy.guaranteeA()) {
            forced = Rarity.A;
            eligible = eligibleAtLeast(pool, Rarity.A);
        }
        if (eligible.isEmpty()) {
            throw new IllegalStateException("Pity pool has no reward at or above " + forced);
        }

        Candidate<T> selected = selectWeighted(eligible);
        Progress next = advance(progress, selected.rarity());
        return new Draw<>(selected, next, forced);
    }

    public List<Draw<T>> drawMany(List<Candidate<T>> pool, Progress start, Policy policy, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Draw count must be positive");
        }
        List<Draw<T>> results = new ArrayList<>(count);
        Progress current = start;
        for (int i = 0; i < count; i++) {
            Draw<T> result = draw(pool, current, policy);
            results.add(result);
            current = result.nextProgress();
        }
        return List.copyOf(results);
    }

    private Candidate<T> selectWeighted(List<Candidate<T>> eligible) {
        double total = 0.0D;
        for (Candidate<T> candidate : eligible) {
            total += candidate.weight();
        }
        double cursor = random.nextDouble(total);
        double cumulative = 0.0D;
        for (Candidate<T> candidate : eligible) {
            cumulative += candidate.weight();
            if (cursor < cumulative) {
                return candidate;
            }
        }
        return eligible.getLast();
    }

    private static <T> List<Candidate<T>> eligibleAtLeast(List<Candidate<T>> pool, Rarity threshold) {
        return pool.stream().filter(candidate -> candidate.rarity().isAtLeast(threshold)).toList();
    }

    private static Progress advance(Progress current, Rarity hit) {
        if (hit == Rarity.S) {
            return new Progress(0, 0);
        }
        if (hit == Rarity.A) {
            return new Progress(0, current.sinceS() + 1);
        }
        return new Progress(current.sinceA() + 1, current.sinceS() + 1);
    }

    private static <T> void validate(List<Candidate<T>> pool, Policy policy) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(policy, "policy");
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("Reward pool cannot be empty");
        }
        if (policy.guaranteeA() <= 0 || policy.guaranteeS() <= 0
                || policy.guaranteeA() > policy.guaranteeS()) {
            throw new IllegalArgumentException("Pity thresholds must satisfy 0 < A <= S");
        }
        for (Candidate<T> candidate : pool) {
            Objects.requireNonNull(candidate.value(), "candidate value");
            Objects.requireNonNull(candidate.rarity(), "candidate rarity");
            if (!Double.isFinite(candidate.weight()) || candidate.weight() <= 0.0D) {
                throw new IllegalArgumentException("Reward weight must be finite and positive");
            }
        }
    }

    public record Candidate<T>(T value, Rarity rarity, double weight) {
    }

    public record Progress(int sinceA, int sinceS) {
        public Progress {
            if (sinceA < 0 || sinceS < 0) {
                throw new IllegalArgumentException("Pity progress cannot be negative");
            }
        }
    }

    public record Policy(int guaranteeA, int guaranteeS) {
    }

    public record Draw<T>(Candidate<T> candidate, Progress nextProgress, Rarity forcedRarity) {
        public boolean wasForced() {
            return forcedRarity != null;
        }
    }
}
