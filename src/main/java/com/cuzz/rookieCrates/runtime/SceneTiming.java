package com.cuzz.rookieCrates.runtime;

/** Main-thread scheduler delays for the reveal and final cleanup phases. */
public record SceneTiming(long revealDelayTicks, long resultDisplayTicks, long skipAfterTicks) {
    public static final SceneTiming DEFAULT = new SceneTiming(95L, 100L, 40L);

    public SceneTiming(long revealDelayTicks, long resultDisplayTicks) {
        this(revealDelayTicks, resultDisplayTicks, 40L);
    }

    public SceneTiming {
        if (revealDelayTicks < 1L || resultDisplayTicks < 1L || skipAfterTicks < 0L) {
            throw new IllegalArgumentException("scene delays must be positive; skipAfterTicks may be zero");
        }
    }
}
