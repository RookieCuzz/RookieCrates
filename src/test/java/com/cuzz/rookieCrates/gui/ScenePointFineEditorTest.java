package com.cuzz.rookieCrates.gui;

import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.ScenePointLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScenePointFineEditorTest {

    @Test
    void positionUsesHundredthAndShiftUsesTenth() {
        assertEquals(0.01D, CratesGuiController.fineStep(CratesGuiController.FineAxis.X, false));
        assertEquals(0.1D, CratesGuiController.fineStep(CratesGuiController.FineAxis.X, true));
    }

    @Test
    void adjustmentChangesOnlySelectedCoordinate() {
        ScenePointLocation source = new ScenePointLocation("world", 1, 2, 3, 45, 10);
        ScenePointLocation moved = CratesGuiController.adjusted(
                source, CratesGuiController.FineAxis.Z, -0.01D);

        assertEquals(1.0D, moved.x());
        assertEquals(2.0D, moved.y());
        assertEquals(2.99D, moved.z(), 1.0E-9D);
        assertEquals(45.0F, moved.yaw());
        assertEquals(10.0F, moved.pitch());
    }

    @Test
    void rotationWrapsYawAndClampsPitch() {
        ScenePointLocation source = new ScenePointLocation("world", 1, 2, 3, 179, 89);

        assertEquals(-171.0F, CratesGuiController.adjusted(
                source, CratesGuiController.FineAxis.YAW, 10).yaw());
        assertEquals(90.0F, CratesGuiController.adjusted(
                source, CratesGuiController.FineAxis.PITCH, 10).pitch());
    }
}
