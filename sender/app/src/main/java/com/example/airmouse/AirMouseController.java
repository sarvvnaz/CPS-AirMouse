package com.example.airmouse;

import android.hardware.SensorManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Converts calibrated sensor readings into relative mouse commands.
 *
 * Filtering used here:
 * 1) Gyro bias removal comes from CalibrationData.
 * 2) Low-pass on gyroscope to smooth pointer motion.
 * 3) Low-pass gravity estimation + subtraction to get linear acceleration for scroll.
 * 4) Complementary fusion: gyro integration is slowly corrected by accelerometer+magnetometer orientation.
 * 5) Dead-zone, clamp, and cooldown to suppress noise and accidental commands.
 */
public class AirMouseController {
    public static class Config {
        public float pointerSensitivity = 1300f;     // pixels per radian of fused angular delta
        public float moveDeadZoneRad = 0.00065f;     // angle delta dead-zone per packet
        public float maxMovePerPacket = 42f;
        public float gyroLowPassAlpha = 0.72f;
        public float gravityLowPassAlpha = 0.90f;
        public float complementaryGyroWeight = 0.985f;
        public float clickGyroThreshold = 2.7f;      // rad/s on Y axis
        public float scrollAccelThreshold = 3.2f;    // m/s^2 linear acceleration on Y axis
        public long clickCooldownMs = 650L;
        public long scrollCooldownMs = 700L;
        public int scrollAmount = 5;
        public boolean invertX = false;
        public boolean invertY = true;
        public boolean invertScroll = false;
        public boolean invertClick = false;
        public boolean useComplementaryFusion = true;
    }

    public abstract static class Event {
        public static class Move extends Event {
            public final double dx;
            public final double dy;
            Move(double dx, double dy) { this.dx = dx; this.dy = dy; }
        }
        public static class Click extends Event {}
        public static class Scroll extends Event {
            public final int amount;
            Scroll(int amount) { this.amount = amount; }
        }
    }

    private final Config config = new Config();

    private final float[] rawGyro = new float[3];
    private final float[] filteredGyro = new float[3];
    private final float[] accel = new float[3];
    private final float[] gravity = new float[3];
    private final float[] linearAccel = new float[3];
    private final float[] mag = new float[3];

    private final float[] fusedOrientation = new float[3]; // yaw(Z), pitch(X), roll(Y)
    private final float[] previousOrientation = new float[3];
    private boolean hasFusedOrientation = false;
    private boolean hasPreviousOrientation = false;
    private boolean hasAccel = false;
    private boolean hasMag = false;

    private long lastClickMs = 0L;
    private long lastScrollMs = 0L;
    private long ignoreGestureUntilMs = 0L;

    public Config getConfig() {
        return config;
    }

    public void resetRuntimeState() {
        Arrays.fill(filteredGyro, 0f);
        Arrays.fill(gravity, 0f);
        Arrays.fill(linearAccel, 0f);
        Arrays.fill(fusedOrientation, 0f);
        Arrays.fill(previousOrientation, 0f);
        hasFusedOrientation = false;
        hasPreviousOrientation = false;
        lastClickMs = 0L;
        lastScrollMs = 0L;
        ignoreGestureUntilMs = SystemClock.uptimeMillis() + 1000L;
    }

    public void updateAccelerometer(float[] correctedAcc) {
        TraceMarks.begin("filter_accelerometer_highpass");
        try {
            System.arraycopy(correctedAcc, 0, accel, 0, 3);
            hasAccel = true;
            for (int i = 0; i < 3; i++) {
                gravity[i] = config.gravityLowPassAlpha * gravity[i] + (1f - config.gravityLowPassAlpha) * accel[i];
                linearAccel[i] = accel[i] - gravity[i];
            }
        } finally {
            TraceMarks.end();
        }
    }

    public void updateMagnetometer(float[] correctedMag) {
        System.arraycopy(correctedMag, 0, mag, 0, 3);
        hasMag = true;
    }

    public List<Event> updateGyroscope(float[] correctedGyro, float dtSeconds) {
        TraceMarks.begin("airmouse_filter_and_classify");
        try {
            List<Event> events = new ArrayList<>();
            if (dtSeconds <= 0f) return events;

            System.arraycopy(correctedGyro, 0, rawGyro, 0, 3);
            for (int i = 0; i < 3; i++) {
                filteredGyro[i] = config.gyroLowPassAlpha * filteredGyro[i] +
                        (1f - config.gyroLowPassAlpha) * correctedGyro[i];
            }

            integrateAndFuseOrientation(dtSeconds);
            detectScrollOrClick(events);
            detectMove(events);
            return events;
        } finally {
            TraceMarks.end();
        }
    }

    private void integrateAndFuseOrientation(float dtSeconds) {
        // Integrate gyro: Z axis for yaw/horizontal, X axis for pitch/vertical, Y axis for roll.
        if (!hasFusedOrientation) {
            float[] absolute = computeAccelMagOrientation();
            if (absolute != null) {
                System.arraycopy(absolute, 0, fusedOrientation, 0, 3);
            }
            hasFusedOrientation = true;
            System.arraycopy(fusedOrientation, 0, previousOrientation, 0, 3);
            hasPreviousOrientation = true;
        }

        fusedOrientation[0] = wrapAngle(fusedOrientation[0] + filteredGyro[2] * dtSeconds);
        fusedOrientation[1] = wrapAngle(fusedOrientation[1] + filteredGyro[0] * dtSeconds);
        fusedOrientation[2] = wrapAngle(fusedOrientation[2] + filteredGyro[1] * dtSeconds);

        if (config.useComplementaryFusion) {
            float[] absolute = computeAccelMagOrientation();
            if (absolute != null) {
                float gyroW = config.complementaryGyroWeight;
                float sensorW = 1f - gyroW;
                fusedOrientation[0] = angleBlend(fusedOrientation[0], absolute[0], gyroW, sensorW);
                fusedOrientation[1] = angleBlend(fusedOrientation[1], absolute[1], gyroW, sensorW);
                fusedOrientation[2] = angleBlend(fusedOrientation[2], absolute[2], gyroW, sensorW);
            }
        }
    }

    private float[] computeAccelMagOrientation() {
        if (!hasAccel || !hasMag) return null;
        float[] R = new float[9];
        float[] I = new float[9];
        boolean ok = SensorManager.getRotationMatrix(R, I, accel, mag);
        if (!ok) return null;
        float[] out = new float[3];
        SensorManager.getOrientation(R, out); // [azimuth/yaw, pitch, roll]
        return out;
    }

    private void detectScrollOrClick(List<Event> events) {
        long now = SystemClock.uptimeMillis();
        if (now < ignoreGestureUntilMs) return;

        // Scroll: fast linear Y movement. Require Y dominance so normal pointer motion does not scroll.
        float ay = linearAccel[1];
        float absAy = Math.abs(ay);
        boolean yDominant = absAy > Math.abs(linearAccel[0]) * 1.25f &&
                absAy > Math.abs(linearAccel[2]) * 1.25f;
        boolean notClickLike = Math.abs(filteredGyro[1]) < config.clickGyroThreshold * 0.75f;
        if (yDominant && notClickLike && absAy > config.scrollAccelThreshold &&
                now - lastScrollMs > config.scrollCooldownMs) {
            int amount = ay > 0 ? -config.scrollAmount : config.scrollAmount;
            if (config.invertScroll) amount = -amount;
            lastScrollMs = now;
            events.add(new Event.Scroll(amount));
            return;
        }

        // Click: quick rotation around Y axis. Default is negative Y; user can invert.
        boolean clickDetected = config.invertClick ?
                filteredGyro[1] > config.clickGyroThreshold :
                filteredGyro[1] < -config.clickGyroThreshold;
        if (clickDetected && now - lastClickMs > config.clickCooldownMs) {
            lastClickMs = now;
            events.add(new Event.Click());
        }
    }

    private void detectMove(List<Event> events) {
        if (!hasPreviousOrientation) return;

        float deltaYawZ = wrapAngle(fusedOrientation[0] - previousOrientation[0]);
        float deltaPitchX = wrapAngle(fusedOrientation[1] - previousOrientation[1]);
        System.arraycopy(fusedOrientation, 0, previousOrientation, 0, 3);

        if (Math.abs(deltaYawZ) < config.moveDeadZoneRad) deltaYawZ = 0f;
        if (Math.abs(deltaPitchX) < config.moveDeadZoneRad) deltaPitchX = 0f;

        double dx = clamp(deltaYawZ * config.pointerSensitivity, -config.maxMovePerPacket, config.maxMovePerPacket);
        double dy = clamp(-deltaPitchX * config.pointerSensitivity, -config.maxMovePerPacket, config.maxMovePerPacket);
        if (config.invertX) dx = -dx;
        if (config.invertY) dy = -dy;

        if (Math.abs(dx) >= 0.15 || Math.abs(dy) >= 0.15) {
            events.add(new Event.Move(dx, dy));
        }
    }

    public float[] getFilteredGyroCopy() { return filteredGyro.clone(); }
    public float[] getLinearAccelCopy() { return linearAccel.clone(); }
    public float[] getGravityCopy() { return gravity.clone(); }
    public float[] getFusedOrientationCopy() { return fusedOrientation.clone(); }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float angleBlend(float current, float target, float currentWeight, float targetWeight) {
        float diff = wrapAngle(target - current);
        return wrapAngle(current + diff * targetWeight / (currentWeight + targetWeight));
    }

    private static float wrapAngle(float angle) {
        while (angle > Math.PI) angle -= (float) (2.0 * Math.PI);
        while (angle < -Math.PI) angle += (float) (2.0 * Math.PI);
        return angle;
    }
}
