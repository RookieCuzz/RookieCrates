package com.cuzz.rookieCrates.runtime;

import com.melluh.servertours.api.event.RoutePlaybackEndEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerToursSceneBridgeTest {
    private static final SceneTiming TIMING = new SceneTiming(95L, 100L, 40L);

    @Test
    void shorterCameraHoldsUntilModelSceneCompletes() {
        assertEquals(195L, ServerToursSceneBridge.totalEndFrame(TIMING, 80L));
        assertEquals(195L, ServerToursSceneBridge.totalEndFrame(TIMING, 195L));
    }

    @Test
    void longerCameraExtendsRewardDisplay() {
        assertEquals(320L, ServerToursSceneBridge.totalEndFrame(TIMING, 320L));
        assertThrows(IllegalArgumentException.class,
                () -> ServerToursSceneBridge.totalEndFrame(TIMING, -1L));
    }

    @Test
    void onlyNaturalFinishAndAuthorizedShiftExitSucceed() {
        assertTrue(ServerToursSceneBridge.successfulEnd(
                RoutePlaybackEndEvent.EndReason.FINISHED, false));
        assertTrue(ServerToursSceneBridge.successfulEnd(
                RoutePlaybackEndEvent.EndReason.EXITED, true));
        assertFalse(ServerToursSceneBridge.successfulEnd(
                RoutePlaybackEndEvent.EndReason.EXITED, false));
        assertFalse(ServerToursSceneBridge.successfulEnd(
                RoutePlaybackEndEvent.EndReason.REPLACED, true));
        assertFalse(ServerToursSceneBridge.successfulEnd(
                RoutePlaybackEndEvent.EndReason.ERROR, false));
        assertFalse(ServerToursSceneBridge.successfulEnd(
                RoutePlaybackEndEvent.EndReason.PLUGIN_DISABLED, false));
    }

    @Test
    void skipEventIsOmittedWhenUnlockWouldBeAfterSceneEnd() {
        assertTrue(ServerToursSceneBridge.skipEventFits(TIMING, 195L));
        assertFalse(ServerToursSceneBridge.skipEventFits(
                new SceneTiming(20L, 20L, 100L),
                40L
        ));
    }
}
