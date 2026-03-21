package com.titanmq.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackPressureControllerTest {

    @Test
    void shouldAllowMessagesUnderHighWaterMark() {
        BackPressureController controller = new BackPressureController(10, 5);

        for (int i = 0; i < 10; i++) {
            assertTrue(controller.tryAcquire(), "Should allow message " + i);
        }
        assertEquals(10, controller.inFlightCount());
    }

    @Test
    void shouldRejectWhenOverHighWaterMark() {
        BackPressureController controller = new BackPressureController(5, 2);

        for (int i = 0; i < 5; i++) {
            assertTrue(controller.tryAcquire());
        }

        assertFalse(controller.tryAcquire(), "Should reject when at high watermark");
        assertTrue(controller.isThrottled());
    }

    @Test
    void shouldResumeAfterDroppingBelowLowWaterMark() {
        BackPressureController controller = new BackPressureController(5, 2);

        // Fill up
        for (int i = 0; i < 5; i++) controller.tryAcquire();
        controller.tryAcquire(); // Trigger throttle
        assertTrue(controller.isThrottled());

        // Release down to low watermark
        for (int i = 0; i < 3; i++) controller.release();
        assertFalse(controller.isThrottled(), "Should resume after dropping below low watermark");
    }

    @Test
    void shouldCalculateThrottleRatio() {
        BackPressureController controller = new BackPressureController(100, 50);

        assertEquals(0.0, controller.throttleRatio());

        // Fill to midpoint between low and high
        for (int i = 0; i < 75; i++) controller.tryAcquire();
        assertEquals(0.5, controller.throttleRatio(), 0.01);
    }

    @Test
    void shouldRejectInvalidWatermarks() {
        assertThrows(IllegalArgumentException.class, () ->
                new BackPressureController(5, 10));
        assertThrows(IllegalArgumentException.class, () ->
                new BackPressureController(5, 5));
    }
}
