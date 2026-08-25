package com.cuzz.rookieCrates.domain;

/** Rarities are ordered from most to least rare. */
public enum Rarity {
    S,
    A,
    B,
    C,
    D,
    E;

    public boolean isAtLeast(Rarity threshold) {
        return ordinal() <= threshold.ordinal();
    }
}
