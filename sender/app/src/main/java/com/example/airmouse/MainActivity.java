package com.example.airmouse;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorReader.Listener {
    private static final int DEFAULT_UDP_PORT = 5000;
    private static final long UI_INTERVAL_MS = 120L;

    private SensorReader sensorReader;
    private final CalibrationData calibration = new CalibrationData();
    private final AirMouseController controller = new AirMouseController();
    private UdpMouseClient udpClient;

    private EditText ipEdit;
    private EditText portEdit;
    private TextView statusText;
    private TextView sensorText;
    private TextView calibrationText;
    private TextView pendingAckText;
    private TextView sensitivityLabel;
    private TextView deadzoneLabel;
    private TextView neutralPoseText;
    private Button startStopButton;
    private Button neutralPoseButton;
    private MotionPreviewView previewView;

    private boolean sending = false;
    private long lastUiUpdateMs = 0L;

    private final float[] lastRawAcc = new float[3];
    private final float[] lastCorrectedAcc = new float[3];
    private final float[] lastRawGyro = new float[3];
    private final float[] lastCorrectedGyro = new float[3];
    private final float[] lastRawMag = new float[3];
    private final float[] lastCorrectedMag = new float[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        calibration.load(this);
        sensorReader = new SensorReader(this, this);
        buildUi();
        updateCalibrationText();
        updateSensorText();
        updateNeutralPoseText();
        setStatus(sensorReader.hasRequiredSensors()
                ? "Ready. Enter laptop IP, calibrate, then press Start."
                : "Required sensors not found. Accelerometer and gyroscope are needed.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        calibration.load(this);
        updateCalibrationText();
        // Sensors need to be running continuously so accel/mag are fresh
        // whenever the user taps "Set Neutral Pose", even before Start.
        sensorReader.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sending) stopSending();
        else sensorReader.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeUdp();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(18));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Air Mouse - Android Sender");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView protocol = smallText("UDP + UTF-8 JSON | move has no ACK | click/scroll retry until ACK | default port 5000");
        protocol.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(protocol, matchWrapTop(4));

        root.addView(sectionTitle("Connection"));
        root.addView(label("Laptop IP"));
        ipEdit = new EditText(this);
        ipEdit.setHint("Example: 192.168.1.23");
        ipEdit.setSingleLine(true);
        ipEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(ipEdit, matchWrap());

        root.addView(label("Port"));
        portEdit = new EditText(this);
        portEdit.setSingleLine(true);
        portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        portEdit.setText(String.valueOf(DEFAULT_UDP_PORT));
        root.addView(portEdit, matchWrap());

        root.addView(sectionTitle("Neutral Pose (hold grip)"));
        root.addView(smallText("Hold the phone exactly how you want to use it as an air mouse " +
                "(e.g. screen facing you, perpendicular to the keyboard) and tap the button below. " +
                "This fixes the up/down drift that happens when the phone is turned to face different directions."));
        neutralPoseButton = new Button(this);
        neutralPoseButton.setText("Set Neutral Pose");
        neutralPoseButton.setOnClickListener(v -> setNeutralPose());
        root.addView(neutralPoseButton, matchWrapTop(6));
        neutralPoseText = smallText("");
        neutralPoseText.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(neutralPoseText, matchWrapTop(4));

        startStopButton = new Button(this);
        startStopButton.setText("Start Sending");
        startStopButton.setOnClickListener(v -> { if (sending) stopSending(); else startSending(); });
        root.addView(startStopButton, matchWrapTop(10));

        LinearLayout testRow = row();
        Button testClick = new Button(this);
        testClick.setText("Test Click");
        testClick.setOnClickListener(v -> sendTestClick());
        Button testUp = new Button(this);
        testUp.setText("Test Scroll Up");
        testUp.setOnClickListener(v -> sendTestScroll(controller.getConfig().scrollAmount));
        Button testDown = new Button(this);
        testDown.setText("Test Scroll Down");
        testDown.setOnClickListener(v -> sendTestScroll(-controller.getConfig().scrollAmount));
        testRow.addView(testClick, weightWrap());
        testRow.addView(testUp, weightWrap());
        testRow.addView(testDown, weightWrap());
        root.addView(testRow, matchWrapTop(4));

        pendingAckText = smallText("Pending ACK: 0");
        root.addView(pendingAckText, matchWrapTop(4));

        previewView = new MotionPreviewView(this);
        root.addView(previewView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        root.addView(sectionTitle("Movement Settings"));
        sensitivityLabel = smallText("");
        root.addView(sensitivityLabel, matchWrap());
        SeekBar sensitivityBar = new SeekBar(this);
        sensitivityBar.setMax(2800);
        sensitivityBar.setProgress((int) controller.getConfig().pointerSensitivity);
        sensitivityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                controller.getConfig().pointerSensitivity = Math.max(200f, progress);
                updateSettingLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(sensitivityBar, matchWrap());

        deadzoneLabel = smallText("");
        root.addView(deadzoneLabel, matchWrapTop(6));
        SeekBar deadzoneBar = new SeekBar(this);
        deadzoneBar.setMax(30); // displayed as 0.0000 to 0.0030 rad
        deadzoneBar.setProgress(7);
        deadzoneBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                controller.getConfig().moveDeadZoneRad = progress / 10000f;
                updateSettingLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(deadzoneBar, matchWrap());

        LinearLayout switches1 = row();
        switches1.addView(checkBox("Invert X", controller.getConfig().invertX, (b, checked) -> controller.getConfig().invertX = checked), weightWrap());
        switches1.addView(checkBox("Invert Y", controller.getConfig().invertY, (b, checked) -> controller.getConfig().invertY = checked), weightWrap());
        root.addView(switches1, matchWrap());

        LinearLayout switches2 = row();
        switches2.addView(checkBox("Invert Scroll", controller.getConfig().invertScroll, (b, checked) -> controller.getConfig().invertScroll = checked), weightWrap());
        switches2.addView(checkBox("Invert Click", controller.getConfig().invertClick, (b, checked) -> controller.getConfig().invertClick = checked), weightWrap());
        root.addView(switches2, matchWrap());

        CheckBox fusion = checkBox("Use complementary fusion (gyro + accel + magnetometer)", controller.getConfig().useComplementaryFusion,
                (b, checked) -> controller.getConfig().useComplementaryFusion = checked);
        root.addView(fusion, matchWrap());

        Button centerButton = new Button(this);
        centerButton.setText("Center Preview Square");
        centerButton.setOnClickListener(v -> previewView.resetCursor());
        root.addView(centerButton, matchWrapTop(4));
        updateSettingLabels();

        root.addView(sectionTitle("Calibration"));
        Button calibrationButton = new Button(this);
        calibrationButton.setText("Open Calibration Page");
        calibrationButton.setOnClickListener(v -> startActivity(new Intent(this, CalibrationActivity.class)));
        root.addView(calibrationButton, matchWrap());

        Button reloadCalButton = new Button(this);
        reloadCalButton.setText("Reload Calibration Values");
        reloadCalButton.setOnClickListener(v -> {
            calibration.load(this);
            controller.resetRuntimeState();
            updateCalibrationText();
            updateNeutralPoseText();
            setStatus("Calibration reloaded. Set neutral pose again before sending.");
        });
        root.addView(reloadCalButton, matchWrapTop(4));

        calibrationText = smallText("");
        calibrationText.setTypeface(Typeface.MONOSPACE);
        root.addView(calibrationText, matchWrapTop(8));

        root.addView(sectionTitle("Debug Sensor Values"));
        sensorText = smallText("");
        sensorText.setTypeface(Typeface.MONOSPACE);
        root.addView(sensorText, matchWrap());

        statusText = smallText("");
        statusText.setPadding(0, dp(10), 0, 0);
        root.addView(statusText, matchWrap());

        setContentView(scrollView);
    }

    private void setNeutralPose() {
        boolean ok = controller.setNeutralPose();
        if (ok) {
            previewView.resetCursor();
            updateNeutralPoseText();
            setStatus("Neutral pose set. Hold this grip as your 'center' from now on.");
            toast("Neutral pose set.");
        } else {
            setStatus("Could not set neutral pose yet - waiting for sensor data. Try again in a second.");
            toast("Sensors not ready yet, try again.");
        }
    }

    private void startSending() {
        if (!sensorReader.hasRequiredSensors()) {
            toast("Accelerometer or gyroscope not found.");
            return;
        }
        if (!controller.hasNeutralPose()) {
            toast("Set the neutral pose first (hold your grip and tap 'Set Neutral Pose').");
            return;
        }
        String ip = ipEdit.getText().toString().trim();
        if (ip.isEmpty()) {
            toast("Enter laptop IP first.");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portEdit.getText().toString().trim());
        } catch (Exception ex) {
            toast("Invalid port.");
            return;
        }
        try {
            closeUdp();
            udpClient = new UdpMouseClient(ip, port, msg -> runOnUiThread(() -> setStatus(msg)));
            calibration.load(this);
            sensorReader.start();
            sending = true;
            startStopButton.setText("Stop Sending");
            setStatus("Sending to " + ip + ":" + port);
        } catch (Exception ex) {
            setStatus("Could not start: " + ex.getMessage());
            toast("Start error: " + ex.getMessage());
            closeUdp();
        }
    }

    private void stopSending() {
        sending = false;
        sensorReader.start(); // keep sensors running so neutral pose / preview stay live
        closeUdp();
        startStopButton.setText("Start Sending");
        pendingAckText.setText("Pending ACK: 0");
        setStatus("Stopped.");
    }

    private void closeUdp() {
        if (udpClient != null) {
            udpClient.close();
            udpClient = null;
        }
    }

    private boolean ensureUdpForTest() {
        if (udpClient != null) return true;
        String ip = ipEdit.getText().toString().trim();
        if (ip.isEmpty()) {
            toast("Enter laptop IP first.");
            return false;
        }
        int port;
        try {
            port = Integer.parseInt(portEdit.getText().toString().trim());
        } catch (Exception ex) {
            toast("Invalid port.");
            return false;
        }
        try {
            udpClient = new UdpMouseClient(ip, port, msg -> runOnUiThread(() -> setStatus(msg)));
            return true;
        } catch (Exception ex) {
            toast("UDP error: " + ex.getMessage());
            return false;
        }
    }

    private void sendTestClick() {
        if (!ensureUdpForTest()) return;
        udpClient.sendClick();
        previewView.showClick();
        updatePendingAckText();
    }

    private void sendTestScroll(int amount) {
        if (!ensureUdpForTest()) return;
        udpClient.sendScroll(amount);
        previewView.showScroll(amount);
        updatePendingAckText();
    }

    @Override
    public void onAccelerometer(float[] values, long timestampNs) {
        System.arraycopy(values, 0, lastRawAcc, 0, 3);
        float[] corrected = calibration.correctAcc(values);
        System.arraycopy(corrected, 0, lastCorrectedAcc, 0, 3);
        controller.updateAccelerometer(corrected);
        maybeUpdateUi();
    }

    @Override
    public void onGyroscope(float[] values, long timestampNs, float dtSeconds) {
        System.arraycopy(values, 0, lastRawGyro, 0, 3);
        float[] corrected = calibration.correctGyro(values);
        System.arraycopy(corrected, 0, lastCorrectedGyro, 0, 3);
        if (!sending || udpClient == null) {
            maybeUpdateUi();
            return;
        }

        List<AirMouseController.Event> events = controller.updateGyroscope(corrected, dtSeconds);
        for (AirMouseController.Event event : events) {
            if (event instanceof AirMouseController.Event.Move) {
                AirMouseController.Event.Move move = (AirMouseController.Event.Move) event;
                udpClient.sendMove(move.dx, move.dy);
                runOnUiThread(() -> previewView.addMovement(move.dx, move.dy));
            } else if (event instanceof AirMouseController.Event.Click) {
                udpClient.sendClick();
                runOnUiThread(() -> previewView.showClick());
            } else if (event instanceof AirMouseController.Event.Scroll) {
                int amount = ((AirMouseController.Event.Scroll) event).amount;
                udpClient.sendScroll(amount);
                runOnUiThread(() -> previewView.showScroll(amount));
            }
        }
        maybeUpdateUi();
    }

    @Override
    public void onMagnetometer(float[] values, long timestampNs) {
        System.arraycopy(values, 0, lastRawMag, 0, 3);
        float[] corrected = calibration.correctMag(values);
        System.arraycopy(corrected, 0, lastCorrectedMag, 0, 3);
        controller.updateMagnetometer(corrected);
        maybeUpdateUi();
    }

    @Override
    public void onSensorStatus(String message) {
        runOnUiThread(() -> setStatus(message));
    }

    private void maybeUpdateUi() {
        long now = System.currentTimeMillis();
        if (now - lastUiUpdateMs < UI_INTERVAL_MS) return;
        lastUiUpdateMs = now;
        runOnUiThread(this::updateSensorText);
    }

    private void updateSensorText() {
        float[] filteredGyro = controller.getFilteredGyroCopy();
        float[] linear = controller.getLinearAccelCopy();
        float[] gravity = controller.getGravityCopy();
        float[] orientation = controller.getFusedOrientationCopy();
        String text = String.format(Locale.US,
                "Raw Acc:       [%7.3f %7.3f %7.3f]\n" +
                        "Corrected Acc: [%7.3f %7.3f %7.3f]\n" +
                        "Gravity LPF:   [%7.3f %7.3f %7.3f]\n" +
                        "Linear Acc HPF:[%7.3f %7.3f %7.3f]\n\n" +
                        "Raw Gyro:      [%7.3f %7.3f %7.3f]\n" +
                        "Corrected Gyro:[%7.3f %7.3f %7.3f]\n" +
                        "Filtered Gyro: [%7.3f %7.3f %7.3f]\n\n" +
                        "Raw Mag:       [%7.3f %7.3f %7.3f]\n" +
                        "Corrected Mag: [%7.3f %7.3f %7.3f]\n\n" +
                        "Orientation (relative to neutral pose):\n  yaw=%.3f pitch=%.3f roll=%.3f\n\n" +
                        "Sensors used:\n%s",
                lastRawAcc[0], lastRawAcc[1], lastRawAcc[2],
                lastCorrectedAcc[0], lastCorrectedAcc[1], lastCorrectedAcc[2],
                gravity[0], gravity[1], gravity[2],
                linear[0], linear[1], linear[2],
                lastRawGyro[0], lastRawGyro[1], lastRawGyro[2],
                lastCorrectedGyro[0], lastCorrectedGyro[1], lastCorrectedGyro[2],
                filteredGyro[0], filteredGyro[1], filteredGyro[2],
                lastRawMag[0], lastRawMag[1], lastRawMag[2],
                lastCorrectedMag[0], lastCorrectedMag[1], lastCorrectedMag[2],
                orientation[0], orientation[1], orientation[2],
                sensorReader.sensorSummary());
        sensorText.setText(text);
        updatePendingAckText();
    }

    private void updatePendingAckText() {
        pendingAckText.setText("Pending ACK: " + (udpClient == null ? 0 : udpClient.getPendingCount()));
    }

    private void updateCalibrationText() {
        if (calibrationText != null) calibrationText.setText(calibration.summary());
    }

    private void updateNeutralPoseText() {
        if (neutralPoseText != null) {
            neutralPoseText.setText(controller.hasNeutralPose()
                    ? "Neutral pose: SET"
                    : "Neutral pose: NOT SET (required before Start Sending)");
        }
    }

    private void updateSettingLabels() {
        if (sensitivityLabel != null) {
            sensitivityLabel.setText(String.format(Locale.US, "Sensitivity: %.0f px/rad", controller.getConfig().pointerSensitivity));
        }
        if (deadzoneLabel != null) {
            deadzoneLabel.setText(String.format(Locale.US, "Move dead-zone: %.4f rad", controller.getConfig().moveDeadZoneRad));
        }
    }

    private void setStatus(String message) {
        if (statusText != null) statusText.setText("Status: " + message);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(8), 0, dp(2));
        return tv;
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

    private CheckBox checkBox(String text, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener(listener);
        return cb;
    }

    private LinearLayout row() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
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
}