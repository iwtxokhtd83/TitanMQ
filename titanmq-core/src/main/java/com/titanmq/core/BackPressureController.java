package com.titanmq.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive back-pressure controller using lock-free CAS operations.
 *
 * <p>Uses a dual-watermark system with a continuous throttle ratio signal:
 * <ul>
 *   <li>Below low watermark: full speed (throttleRatio = 0.0)</li>
 *   <li>Between watermarks: gradual throttling (0.0–1.0)</li>
 *   <li>Above high watermark: reject (tryAcquire returns false)</li>
 * </ul>
 *
 * <p>The hysteresis gap between watermarks prevents oscillation.
 *
 * <p>Thread safety: all operations are lock-free using CAS loops.
 * The in-flight count is guaranteed to never exceed the high watermark.
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
     * <p>Uses a CAS loop to atomically check-and-increment, guaranteeing the
     * in-flight count never exceeds the high watermark — even under heavy
     * concurrent load from multiple threads.
     *
     * @return true if the message can proceed, false if back-pressure is active
     */
    public boolean tryAcquire() {
        while (true) {
            long current = inFlightCount.get();
            if (current >= highWaterMark) {
                if (!throttled) {
                    throttled = true;
                    log.warn("Back-pressure activated: in-flight={}, highWaterMark={}", current, highWaterMark);
                }
                return false;
            }
            if (inFlightCount.compareAndSet(current, current + 1)) {
                return true;
            }
            // CAS failed — another thread modified the count, retry
        }
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
