package com.example.airmouse;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Locale;


public class CalibrationActivity extends Activity implements SensorReader.Listener {
    private static final int GYRO_SAMPLES = 220;
    private static final int ACC_SAMPLES = 140;
    private static final int MAG_MIN_SAMPLES = 250;
    private static final float MAG_MIN_RANGE = 8.0f;

    private SensorReader sensorReader;
    private final CalibrationData calibration = new CalibrationData();

    private TextView instructionText;
    private TextView progressText;
    private TextView valuesText;
    private TextView calibrationText;

    private boolean gyroCollecting = false;
    private int gyroSamples = 0;
    private final float[] gyroSum = new float[3];

    private boolean accCollecting = false;
    private int accAxis = 0;
    private boolean accPositive = true;
    private int accSamples = 0;
    private final float[] accSum = new float[3];

    private boolean magCollecting = false;
    private int magSamples = 0;
    private final float[] magMin = new float[]{Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
    private final float[] magMax = new float[]{Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};

    private final float[] lastAcc = new float[3];
    private final float[] lastGyro = new float[3];
    private final float[] lastMag = new float[3];
    private long lastUiMs = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        calibration.load(this);
        sensorReader = new SensorReader(this, this);
        buildUi();
        updateCalibrationText();
        setInstruction("This page calibrates the raw sensors. Keep the phone still when asked. For magnetometer, rotate the phone as a big English 8 in all directions.");
        setProgress("Ready.\n" + sensorReader.sensorSummary());
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorReader.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorReader.stop();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(18));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Air Mouse Sensor Calibration");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        instructionText = smallText("");
        instructionText.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(instructionText, matchWrapTop(10));

        progressText = smallText("");
        root.addView(progressText, matchWrapTop(8));

        root.addView(sectionTitle("1) Gyroscope bias"));
        root.addView(smallText("Put the phone on a stable table and do not touch it. The ideal gyro output is zero; we average its error and subtract it later."));
        Button gyroButton = new Button(this);
        gyroButton.setText("Start Gyroscope Calibration");
        gyroButton.setOnClickListener(v -> startGyroCalibration());
        root.addView(gyroButton, matchWrapTop(6));

        root.addView(sectionTitle("2) Accelerometer six-position calibration"));
        root.addView(smallText("Capture all six positions. Android axes: +X = right side, +Y = top side, +Z = screen side. The chosen side must face upward."));
        root.addView(accRow(0, "X", "+X: right edge upward", "-X: left edge upward"));
        root.addView(accRow(1, "Y", "+Y: top edge upward", "-Y: bottom edge upward"));
        root.addView(accRow(2, "Z", "+Z: screen facing up", "-Z: screen facing down"));

        root.addView(sectionTitle("3) Magnetometer figure-8 calibration"));
        root.addView(smallText("Move the phone like a large English 8 and rotate it through many angles. We store min/max for each axis, then compute offset and scale."));
        Button magButton = new Button(this);
        magButton.setText("Start / Stop Magnetometer Calibration");
        magButton.setOnClickListener(v -> toggleMagCalibration());
        root.addView(magButton, matchWrapTop(6));

        Button resetButton = new Button(this);
        resetButton.setText("Reset All Calibration Values");
        resetButton.setOnClickListener(v -> {
            calibration.reset(this);
            updateCalibrationText();
            setProgress("Calibration values reset.");
        });
        root.addView(resetButton, matchWrapTop(12));

        Button doneButton = new Button(this);
        doneButton.setText("Back to Main Page");
        doneButton.setOnClickListener(v -> finish());
        root.addView(doneButton, matchWrapTop(6));

        calibrationText = smallText("");
        calibrationText.setTypeface(Typeface.MONOSPACE);
        root.addView(calibrationText, matchWrapTop(12));

        valuesText = smallText("");
        valuesText.setTypeface(Typeface.MONOSPACE);
        root.addView(sectionTitle("Live raw values"));
        root.addView(valuesText, matchWrap());

        setContentView(scrollView);
    }

    private LinearLayout accRow(int axis, String name, String positiveHelp, String negativeHelp) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        TextView help = smallText(positiveHelp + " | " + negativeHelp);
        container.addView(help, matchWrapTop(4));
        LinearLayout row = row();
        Button pos = new Button(this);
        pos.setText("Capture +" + name);
        pos.setOnClickListener(v -> startAccCalibration(axis, true, positiveHelp));
        Button neg = new Button(this);
        neg.setText("Capture -" + name);
        neg.setOnClickListener(v -> startAccCalibration(axis, false, negativeHelp));
        row.addView(pos, weightWrap());
        row.addView(neg, weightWrap());
        container.addView(row, matchWrap());
        return container;
    }

    private void startGyroCalibration() {
        gyroCollecting = true;
        gyroSamples = 0;
        Arrays.fill(gyroSum, 0f);
        setInstruction("Gyroscope calibration is running. Keep the phone completely still.");
        setProgress("Collecting gyro samples: 0 / " + GYRO_SAMPLES);
    }

    private void startAccCalibration(int axis, boolean positive, String help) {
        accCollecting = true;
        accAxis = axis;
        accPositive = positive;
        accSamples = 0;
        Arrays.fill(accSum, 0f);
        setInstruction("Accelerometer calibration: " + help + ". Hold still until samples finish.");
        setProgress("Collecting accelerometer samples for " + axisName(axis) + (positive ? "+" : "-") + ": 0 / " + ACC_SAMPLES);
    }

    private void toggleMagCalibration() {
        if (!magCollecting) {
            magCollecting = true;
            magSamples = 0;
            Arrays.fill(magMin, Float.POSITIVE_INFINITY);
            Arrays.fill(magMax, Float.NEGATIVE_INFINITY);
            setInstruction("Magnetometer calibration is running. Move phone as English 8 in all orientations, then press the button again.");
            setProgress("Collecting magnetometer min/max values...");
        } else {
            magCollecting = false;
            boolean valid = magSamples >= MAG_MIN_SAMPLES;
            StringBuilder ranges = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                float range = magMax[i] - magMin[i];
                ranges.append(axisName(i)).append(" range=").append(String.format(Locale.US, "%.2f", range)).append(" ");
                if (!Float.isFinite(range) || range < MAG_MIN_RANGE) valid = false;
            }
            if (valid) {
                calibration.setMagCalibration(magMin, magMax);
                calibration.save(this);
                updateCalibrationText();
                setProgress("Magnetometer calibrated. " + ranges);
            } else {
                setProgress("Magnetometer calibration failed: not enough samples/range. Try a bigger figure-8. " + ranges);
            }
        }
    }

    @Override
    public void onAccelerometer(float[] values, long timestampNs) {
        System.arraycopy(values, 0, lastAcc, 0, 3);
        if (accCollecting) {
            for (int i = 0; i < 3; i++) accSum[i] += values[i];
            accSamples++;
            if (accSamples % 10 == 0) {
                runOnUiThread(() -> setProgress("Collecting accelerometer samples: " + accSamples + " / " + ACC_SAMPLES));
            }
            if (accSamples >= ACC_SAMPLES) {
                float avgAxis = accSum[accAxis] / accSamples;
                calibration.setAccelerometerPose(accAxis, accPositive, avgAxis);
                calibration.save(this);
                accCollecting = false;
                runOnUiThread(() -> {
                    updateCalibrationText();
                    setProgress("Accelerometer " + axisName(accAxis) + (accPositive ? "+" : "-") + " captured. Average on axis=" + String.format(Locale.US, "%.3f", avgAxis));
                    setInstruction("Capture the remaining accelerometer positions, or continue to magnetometer calibration.");
                });
            }
        }
        maybeUpdateValues();
    }

    @Override
    public void onGyroscope(float[] values, long timestampNs, float dtSeconds) {
        System.arraycopy(values, 0, lastGyro, 0, 3);
        if (gyroCollecting) {
            for (int i = 0; i < 3; i++) gyroSum[i] += values[i];
            gyroSamples++;
            if (gyroSamples % 20 == 0) {
                runOnUiThread(() -> setProgress("Collecting gyro samples: " + gyroSamples + " / " + GYRO_SAMPLES));
            }
            if (gyroSamples >= GYRO_SAMPLES) {
                float[] avg = new float[3];
                for (int i = 0; i < 3; i++) avg[i] = gyroSum[i] / gyroSamples;
                calibration.setGyroBiasFromAverage(avg);
                calibration.save(this);
                gyroCollecting = false;
                runOnUiThread(() -> {
                    updateCalibrationText();
                    setProgress("Gyroscope bias saved.");
                    setInstruction("Next, calibrate accelerometer in six positions.");
                });
            }
        }
        maybeUpdateValues();
    }

    @Override
    public void onMagnetometer(float[] values, long timestampNs) {
        System.arraycopy(values, 0, lastMag, 0, 3);
        if (magCollecting) {
            magSamples++;
            for (int i = 0; i < 3; i++) {
                magMin[i] = Math.min(magMin[i], values[i]);
                magMax[i] = Math.max(magMax[i], values[i]);
            }
            if (magSamples % 20 == 0) {
                runOnUiThread(() -> setProgress(String.format(Locale.US,
                        "Mag samples=%d | ranges X=%.1f Y=%.1f Z=%.1f",
                        magSamples, magMax[0]-magMin[0], magMax[1]-magMin[1], magMax[2]-magMin[2])));
            }
        }
        maybeUpdateValues();
    }

    @Override
    public void onSensorStatus(String message) {
        runOnUiThread(() -> setProgress(message));
    }

    private void maybeUpdateValues() {
        long now = System.currentTimeMillis();
        if (now - lastUiMs < 150) return;
        lastUiMs = now;
        runOnUiThread(() -> valuesText.setText(String.format(Locale.US,
                "Raw Acc:  [%8.3f %8.3f %8.3f]\n" +
                "Raw Gyro: [%8.4f %8.4f %8.4f]\n" +
                "Raw Mag:  [%8.2f %8.2f %8.2f]",
                lastAcc[0], lastAcc[1], lastAcc[2],
                lastGyro[0], lastGyro[1], lastGyro[2],
                lastMag[0], lastMag[1], lastMag[2])));
    }

    private void updateCalibrationText() {
        if (calibrationText != null) calibrationText.setText(calibration.summary());
    }

    private void setInstruction(String text) {
        if (instructionText != null) instructionText.setText("Instruction: " + text);
    }

    private void setProgress(String text) {
        if (progressText != null) progressText.setText("Status: " + text);
    }

    private String axisName(int axis) {
        return axis == 0 ? "X" : axis == 1 ? "Y" : "Z";
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(16), 0, dp(6));
        return tv;
    }

    private TextView smallText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setLineSpacing(2f, 1.05f);
        return tv;
    }

    private LinearLayout row() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        return ll;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapTop(int topDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams weightWrap() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
