package com.example.airmouse;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;


public class SensorReader implements SensorEventListener {
    public interface Listener {
        void onAccelerometer(float[] values, long timestampNs);
        void onGyroscope(float[] values, long timestampNs, float dtSeconds);
        void onMagnetometer(float[] values, long timestampNs);
        void onSensorStatus(String message);
    }

    public static final int DEFAULT_SENSOR_DELAY_US = 10_000; 

    private final SensorManager sensorManager;
    private final Listener listener;
    private final Sensor accelerometer;
    private final Sensor gyroscope;
    private final Sensor magnetometer;

    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private long lastGyroTimestampNs = 0L;
    private boolean running = false;

    public SensorReader(Context context, Listener listener) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.listener = listener;
        this.accelerometer = chooseSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER);
        this.gyroscope = chooseSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_GYROSCOPE);
        this.magnetometer = chooseSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_MAGNETIC_FIELD);
    }

    private Sensor chooseSensor(int preferredType, int fallbackType) {
        Sensor preferred = sensorManager.getDefaultSensor(preferredType);
        if (preferred != null) return preferred;
        return sensorManager.getDefaultSensor(fallbackType);
    }

    public boolean hasRequiredSensors() {
        return accelerometer != null && gyroscope != null;
    }

    public Sensor getAccelerometer() { return accelerometer; }
    public Sensor getGyroscope() { return gyroscope; }
    public Sensor getMagnetometer() { return magnetometer; }

    public synchronized void start() {
        if (running) return;
        running = true;
        lastGyroTimestampNs = 0L;
        sensorThread = new HandlerThread("AirMouse-SensorThread");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        if (accelerometer != null) sensorManager.registerListener(this, accelerometer, DEFAULT_SENSOR_DELAY_US, sensorHandler);
        if (gyroscope != null) sensorManager.registerListener(this, gyroscope, DEFAULT_SENSOR_DELAY_US, sensorHandler);
        if (magnetometer != null) sensorManager.registerListener(this, magnetometer, DEFAULT_SENSOR_DELAY_US, sensorHandler);

        if (listener != null) listener.onSensorStatus("SensorReader started on background thread.");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        sensorManager.unregisterListener(this);
        if (sensorThread != null) {
            sensorThread.quitSafely();
            sensorThread = null;
            sensorHandler = null;
        }
        if (listener != null) listener.onSensorStatus("SensorReader stopped.");
    }

Replace your current `onSensorChanged()` method with:

@Override
public void onSensorChanged(SensorEvent event) {
    float[] firstThree = firstThree(event.values);
    int type = event.sensor.getType();

    if (type == Sensor.TYPE_ACCELEROMETER ||
            type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) {

        TraceMarks.begin("sensor_read_accel");
        try {
            if (listener != null) {
                listener.onAccelerometer(firstThree, event.timestamp);
            }
        } finally {
            TraceMarks.end();
        }

    } else if (type == Sensor.TYPE_GYROSCOPE ||
            type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {

        TraceMarks.begin("sensor_read_gyro");
        try {
            float dt = 0f;

            if (lastGyroTimestampNs != 0L) {
                dt = (event.timestamp - lastGyroTimestampNs) * 1e-9f;

                if (dt < 0f || dt > 0.2f) {
                    dt = 0f;
                }
            }

            lastGyroTimestampNs = event.timestamp;

            if (listener != null) {
                listener.onGyroscope(
                        firstThree,
                        event.timestamp,
                        dt
                );
            }
        } finally {
            TraceMarks.end();
        }

    } else if (type == Sensor.TYPE_MAGNETIC_FIELD ||
            type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) {

        TraceMarks.begin("sensor_read_mag");
        try {
            if (listener != null) {
                listener.onMagnetometer(firstThree, event.timestamp);
            }
        } finally {
            TraceMarks.end();
        }
    }
}


    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (listener != null) {
            listener.onSensorStatus("Sensor accuracy changed: " + sensorName(sensor) + " accuracy=" + accuracy);
        }
    }

    public String sensorSummary() {
        return "Acc:  " + sensorName(accelerometer) + "\n" +
                "Gyro: " + sensorName(gyroscope) + "\n" +
                "Mag:  " + sensorName(magnetometer);
    }

    private String sensorName(Sensor sensor) {
        if (sensor == null) return "not available";
        return sensor.getName() + " (type " + sensor.getType() + ")";
    }

    private static float[] firstThree(float[] values) {
        return new float[]{
                values.length > 0 ? values[0] : 0f,
                values.length > 1 ? values[1] : 0f,
                values.length > 2 ? values[2] : 0f
        };
    }
}
