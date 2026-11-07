package dev.restclient.ui;

import dev.restclient.testkit.AfterEach;
import dev.restclient.testkit.Test;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertTrue;

/** Zoom steps and scaling. Runs headless (no windows, so setZoom only records the level). */
class ZoomTest {
    @AfterEach
    void reset() {
        Theme.setZoom(1f, false);
    }

    @Test
    void stepsWalkTheLadderAndStopAtTheEnds() {
        Theme.setZoom(1f, false);
        assertEquals(1.1f, Theme.zoomStep(1));
        assertEquals(0.85f, Theme.zoomStep(-1));
        assertEquals(1f, Theme.zoomStep(0));
        Theme.setZoom(2f, false);
        assertEquals(2f, Theme.zoomStep(1));
        Theme.setZoom(0.75f, false);
        assertEquals(0.75f, Theme.zoomStep(-1));
    }

    @Test
    void zoomIsClampedAndScalesPixelSizes() {
        Theme.setZoom(5f, false);
        assertEquals(2f, Theme.zoom());
        assertEquals(60, Theme.scaled(30));
        Theme.setZoom(1.25f, false);
        assertEquals(38, Theme.scaled(30));
        assertTrue(Theme.mono(12f).getSize() == 15, "mono font follows zoom");
    }
}
