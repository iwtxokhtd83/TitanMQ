package com.titanmq.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive back-pressure controller.
 *
 * <p>Monitors in-flight message count and applies flow control when thresholds are exceeded.
 * This prevents OOM conditions and cascading failures that plague Kafka under heavy load.
 *
 * <p>Strategy:
 * <ul>
 *   <li>When in-flight exceeds high watermark: reject new messages (producer gets back-pressure signal)</li>
 *   <li>When in-flight drops below low watermark: resume accepting messages</li>
 *   <li>Gradual throttling between watermarks for smooth degradation</li>
 * </ul>
 */
public class BackPressureController {

    private static final Logger log = LoggerFactory.getLogger(BackPressureController.class);

    private final int highWaterMark;
    private final int lowWaterMark;
    private final AtomicLong inFlightCount = new AtomicLong(0);
    private volatile boolean throttled = false;

    public BackPressureController(int highWaterMark, int lowWaterMark) {
        if (lowWaterMark >= highWaterMark) {
            throw new IllegalArgumentException("lowWaterMark must be < highWaterMark");
        }
        this.highWaterMark = highWaterMark;
        this.lowWaterMark = lowWaterMark;
    }

    /**
     * Try to acquire a slot for a new message.
     *
     * @return true if the message can proceed, false if back-pressure is active
     */
    public boolean tryAcquire() {
        long current = inFlightCount.incrementAndGet();
        if (current > highWaterMark) {
            if (!throttled) {
                throttled = true;
                log.warn("Back-pressure activated: in-flight={}, highWaterMark={}", current, highWaterMark);
            }
            inFlightCount.decrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Release a slot when a message has been consumed/acknowledged.
     */
    public void release() {
        long current = inFlightCount.decrementAndGet();
        if (throttled && current <= lowWaterMark) {
            throttled = false;
            log.info("Back-pressure deactivated: in-flight={}, lowWaterMark={}", current, lowWaterMark);
        }
    }

    public boolean isThrottled() {
        return throttled;
    }

    public long inFlightCount() {
        return inFlightCount.get();
    }

    /**
     * Calculate throttle percentage (0.0 = no throttle, 1.0 = full throttle).
     * Useful for gradual back-pressure in producer clients.
     */
    public double throttleRatio() {
        long current = inFlightCount.get();
        if (current <= lowWaterMark) return 0.0;
        if (current >= highWaterMark) return 1.0;
        return (double) (current - lowWaterMark) / (highWaterMark - lowWaterMark);
    }
}
