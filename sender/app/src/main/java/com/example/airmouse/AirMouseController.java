
package com.example.airmouse;

import android.hardware.SensorManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class AirMouseController {
    public static class Config {
        public float pointerSensitivity = 1300f;
        public float moveDeadZoneRad = 0.00065f;
        public float maxMovePerPacket = 42f;
        public float gyroLowPassAlpha = 0.72f;
        public float gravityLowPassAlpha = 0.90f;
        public float complementaryGyroWeight = 0.985f;
        public float clickGyroThreshold = 2.7f;
        public float scrollAccelThreshold = 3.2f;
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

    private final float[] fusedOrientation = new float[3];
    private final float[] previousOrientation = new float[3];
    private boolean hasFusedOrientation = false;
    private boolean hasPreviousOrientation = false;
    private boolean hasAccel = false;
    private boolean hasMag = false;

    private final float[] neutralRotationMatrix = new float[9];
    private boolean hasNeutralPose = false;

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


    public boolean setNeutralPose() {
        if (!hasAccel || !hasMag) return false;
        float[] R = new float[9];
        float[] I = new float[9];
        boolean ok = SensorManager.getRotationMatrix(R, I, accel, mag);
        if (!ok) return false;
        System.arraycopy(R, 0, neutralRotationMatrix, 0, 9);
        hasNeutralPose = true;

        Arrays.fill(fusedOrientation, 0f);
        Arrays.fill(previousOrientation, 0f);
        hasFusedOrientation = true;
        hasPreviousOrientation = true;
        ignoreGestureUntilMs = SystemClock.uptimeMillis() + 400L;
        return true;
    }

    public boolean hasNeutralPose() {
        return hasNeutralPose;
    }

    public void clearNeutralPose() {
        hasNeutralPose = false;
        hasFusedOrientation = false;
        hasPreviousOrientation = false;
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
            if (!hasNeutralPose) return events;

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
        if (!hasFusedOrientation) {
            float[] relative = computeRelativeAngles();
            if (relative != null) {
                System.arraycopy(relative, 0, fusedOrientation, 0, 3);
            }
            hasFusedOrientation = true;
            System.arraycopy(fusedOrientation, 0, previousOrientation, 0, 3);
            hasPreviousOrientation = true;
        }

        fusedOrientation[0] = wrapAngle(fusedOrientation[0] + filteredGyro[2] * dtSeconds);
        fusedOrientation[1] = wrapAngle(fusedOrientation[1] + filteredGyro[0] * dtSeconds);
        fusedOrientation[2] = wrapAngle(fusedOrientation[2] + filteredGyro[1] * dtSeconds);

        if (config.useComplementaryFusion) {
            float[] relative = computeRelativeAngles();
            if (relative != null) {
                float gyroW = config.complementaryGyroWeight;
                float sensorW = 1f - gyroW;
                fusedOrientation[0] = angleBlend(fusedOrientation[0], relative[0], gyroW, sensorW);
                fusedOrientation[1] = angleBlend(fusedOrientation[1], relative[1], gyroW, sensorW);
                fusedOrientation[2] = angleBlend(fusedOrientation[2], relative[2], gyroW, sensorW);
            }
        }
    }


    private float[] computeRelativeAngles() {
        if (!hasAccel || !hasMag || !hasNeutralPose) return null;
        float[] currentR = new float[9];
        float[] I = new float[9];
        boolean ok = SensorManager.getRotationMatrix(currentR, I, accel, mag);
        if (!ok) return null;

        float[] relativeR = multiplyTransposeA(neutralRotationMatrix, currentR);

        float r00 = relativeR[0], r10 = relativeR[3];
        float r20 = relativeR[6], r21 = relativeR[7], r22 = relativeR[8];

        float yaw = (float) Math.atan2(r10, r00);
        float pitch = (float) Math.atan2(-r20, Math.sqrt(r21 * r21 + r22 * r22));
        float roll = (float) Math.atan2(r21, r22);

        return new float[]{yaw, pitch, roll};
    }


    private static float[] multiplyTransposeA(float[] a, float[] b) {
        float[] result = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float sum = 0f;
                for (int k = 0; k < 3; k++) {
                    sum += a[k * 3 + row] * b[k * 3 + col];
                }
                result[row * 3 + col] = sum;
            }
        }
        return result;
    }

    private void detectScrollOrClick(List<Event> events) {

        long now = SystemClock.uptimeMillis();
        if (now < ignoreGestureUntilMs) return;
        else return;

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