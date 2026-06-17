package com.example.airmouse;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Stores all sensor calibration parameters in SharedPreferences.
 *
 * Gyro: bias is removed as corrected = raw - bias.
 * Accelerometer: six-position calibration gives offset and scale, corrected = (raw - offset) / scale.
 * Magnetometer: figure-8 calibration gives offset and scale, corrected = (raw - offset) / scale.
 */
public class CalibrationData {
    private static final String PREFS = "air_mouse_calibration_v2";
    public static final float G = 9.81f;

    public final float[] gyroBias = new float[]{0f, 0f, 0f};
    public final float[] accOffset = new float[]{0f, 0f, 0f};
    public final float[] accScale = new float[]{1f, 1f, 1f};
    public final float[] magOffset = new float[]{0f, 0f, 0f};
    public final float[] magScale = new float[]{1f, 1f, 1f};

    public final float[][] accPose = new float[3][2]; // axis, 0=negative, 1=positive
    public final boolean[][] accPoseSet = new boolean[3][2];

    public boolean gyroCalibrated = false;
    public boolean magCalibrated = false;

    public void load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadArray(sp, "gyroBias", gyroBias);
        loadArray(sp, "accOffset", accOffset);
        loadArray(sp, "accScale", accScale);
        loadArray(sp, "magOffset", magOffset);
        loadArray(sp, "magScale", magScale);
        gyroCalibrated = sp.getBoolean("gyroCalibrated", false);
        magCalibrated = sp.getBoolean("magCalibrated", false);
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                accPose[axis][side] = sp.getFloat("accPose_" + axis + "_" + side, 0f);
                accPoseSet[axis][side] = sp.getBoolean("accPoseSet_" + axis + "_" + side, false);
            }
        }
    }

    public void save(Context context) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        saveArray(e, "gyroBias", gyroBias);
        saveArray(e, "accOffset", accOffset);
        saveArray(e, "accScale", accScale);
        saveArray(e, "magOffset", magOffset);
        saveArray(e, "magScale", magScale);
        e.putBoolean("gyroCalibrated", gyroCalibrated);
        e.putBoolean("magCalibrated", magCalibrated);
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                e.putFloat("accPose_" + axis + "_" + side, accPose[axis][side]);
                e.putBoolean("accPoseSet_" + axis + "_" + side, accPoseSet[axis][side]);
            }
        }
        e.apply();
    }

    public void reset(Context context) {
        for (int i = 0; i < 3; i++) {
            gyroBias[i] = 0f;
            accOffset[i] = 0f;
            accScale[i] = 1f;
            magOffset[i] = 0f;
            magScale[i] = 1f;
            for (int j = 0; j < 2; j++) {
                accPose[i][j] = 0f;
                accPoseSet[i][j] = false;
            }
        }
        gyroCalibrated = false;
        magCalibrated = false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public float[] correctGyro(float[] raw) {
        return new float[]{
                raw[0] - gyroBias[0],
                raw[1] - gyroBias[1],
                raw[2] - gyroBias[2]
        };
    }

    public float[] correctAcc(float[] raw) {
        return new float[]{
                correctAcc(0, raw[0]),
                correctAcc(1, raw[1]),
                correctAcc(2, raw[2])
        };
    }

    public float[] correctMag(float[] raw) {
        return new float[]{
                correctMag(0, raw[0]),
                correctMag(1, raw[1]),
                correctMag(2, raw[2])
        };
    }

    public float correctAcc(int axis, float raw) {
        float scale = Math.abs(accScale[axis]) < 0.0001f ? 1f : accScale[axis];
        return (raw - accOffset[axis]) / scale;
    }

    public float correctMag(int axis, float raw) {
        float scale = Math.abs(magScale[axis]) < 0.0001f ? 1f : magScale[axis];
        return (raw - magOffset[axis]) / scale;
    }

    public void setGyroBiasFromAverage(float[] avg) {
        System.arraycopy(avg, 0, gyroBias, 0, 3);
        gyroCalibrated = true;
    }

    public void setAccelerometerPose(int axis, boolean positive, float rawAverageOnThatAxis) {
        int side = positive ? 1 : 0;
        accPose[axis][side] = rawAverageOnThatAxis;
        accPoseSet[axis][side] = true;
        recomputeAccelerometerCalibration();
    }

    private void recomputeAccelerometerCalibration() {
        for (int axis = 0; axis < 3; axis++) {
            if (accPoseSet[axis][0] && accPoseSet[axis][1]) {
                float negative = accPose[axis][0];
                float positive = accPose[axis][1];
                accOffset[axis] = (positive + negative) / 2f;
                accScale[axis] = (positive - negative) / (2f * G);
                if (Math.abs(accScale[axis]) < 0.0001f) accScale[axis] = 1f;
            }
        }
    }

    public void setMagCalibration(float[] min, float[] max) {
        for (int i = 0; i < 3; i++) {
            magOffset[i] = (min[i] + max[i]) / 2f;
            magScale[i] = (max[i] - min[i]) / 2f;
            if (Math.abs(magScale[i]) < 0.0001f) magScale[i] = 1f;
        }
        magCalibrated = true;
    }

    public boolean isAccelerometerFullyCalibrated() {
        for (int axis = 0; axis < 3; axis++) {
            if (!accPoseSet[axis][0] || !accPoseSet[axis][1]) return false;
        }
        return true;
    }

    public boolean isFullyCalibrated() {
        return gyroCalibrated && isAccelerometerFullyCalibrated() && magCalibrated;
    }

    public String summary() {
        return String.format(Locale.US,
                "Gyro calibrated: %s\n" +
                "Gyro bias: [%.4f, %.4f, %.4f]\n\n" +
                "Accel calibrated: %s\n" +
                "Acc offset: [%.3f, %.3f, %.3f]\n" +
                "Acc scale:  [%.3f, %.3f, %.3f]\n\n" +
                "Mag calibrated: %s\n" +
                "Mag offset: [%.2f, %.2f, %.2f]\n" +
                "Mag scale:  [%.2f, %.2f, %.2f]",
                gyroCalibrated ? "yes" : "no",
                gyroBias[0], gyroBias[1], gyroBias[2],
                isAccelerometerFullyCalibrated() ? "yes" : "no",
                accOffset[0], accOffset[1], accOffset[2],
                accScale[0], accScale[1], accScale[2],
                magCalibrated ? "yes" : "no",
                magOffset[0], magOffset[1], magOffset[2],
                magScale[0], magScale[1], magScale[2]);
    }

    private static void loadArray(SharedPreferences sp, String key, float[] values) {
        for (int i = 0; i < values.length; i++) {
            values[i] = sp.getFloat(key + i, values[i]);
        }
    }

    private static void saveArray(SharedPreferences.Editor e, String key, float[] values) {
        for (int i = 0; i < values.length; i++) {
            e.putFloat(key + i, values[i]);
        }
    }
}
