# AirMouseUdp - Complete Android Studio Project

This project implements the **Android side** of the Air Mouse assignment.
It is designed for Android 10 / API 29 and higher.

## What is implemented from the PDF Android requirements?

- Android app built for API 29+.
- Uses phone sensors to control an air mouse.
- Reads accelerometer, gyroscope, and magnetometer.
- Prefers uncalibrated/raw sensor types and falls back to normal sensors if the phone does not provide raw versions.
- Separate calibration page.
- Gyroscope bias calibration.
- Accelerometer six-position calibration: +X, -X, +Y, -Y, +Z, -Z.
- Magnetometer figure-8 min/max calibration.
- Sensor values are displayed for debugging.
- UI has laptop IP field and port field.
- UI has Start / Stop sending.
- UI shows motion direction with a moving green square.
- UI flashes for click and scroll.
- Sensitivity and dead-zone are adjustable.
- Invert switches are provided for X/Y/click/scroll direction tuning.
- Pointer movement uses relative deltas, not absolute positions.
- UDP + UTF-8 JSON protocol is implemented.
- Move packets do not require ACK.
- Click and scroll packets use seq and are retried until ACK is received.
- Cleartext traffic and INTERNET permission are enabled.
- Background sensor thread is used so sensor callbacks do not run on the main UI thread.
- Android Trace sections are added for Perfetto analysis.

## UDP protocol

Transport: UDP  
Encoding: UTF-8 JSON  
Default port: `5000`

Move packet:

```json
{"type":"move","dx":12.5,"dy":-4.0}
```

Click packet:

```json
{"type":"click","seq":101}
```

Scroll packet:

```json
{"type":"scroll","seq":102,"amount":-5}
```

ACK from laptop:

```json
{"type":"ack","seq":101}
```

Rules implemented:

- `dx` and `dy` are relative movements.
- `seq` is only used for click and scroll.
- Android retries click/scroll until ACK arrives.
- Pending ACK count is shown in the UI.

## Sensor and filter logic

The app uses these correction and filtering stages:

1. **Gyroscope bias removal**  
   Keep the phone still, average gyro output, and subtract that average later.

2. **Accelerometer six-position calibration**  
   For each axis, capture positive and negative gravity positions. This computes offset and scale.

3. **Magnetometer figure-8 calibration**  
   Move the phone through many directions. The app stores min/max and computes offset and scale.

4. **Gyroscope low-pass filter**  
   Smooths pointer movement and reduces sudden jumps.

5. **Gravity low-pass + linear acceleration high-pass**  
   The accelerometer contains gravity. The app estimates gravity and subtracts it to detect quick Y-axis motion for scroll.

6. **Complementary fusion**  
   Gyroscope integration is slowly corrected by accelerometer+magnetometer orientation. This reduces drift and helps produce more stable motion.

7. **Dead-zone and clamp**  
   Tiny motions are ignored and very large packets are limited so the pointer does not jump.

8. **Cooldown**  
   Click and scroll have cooldown windows so one gesture does not create many repeated actions.

## Motion mapping

- Z-axis rotation / yaw -> horizontal pointer movement (`dx`).
- X-axis rotation / pitch -> vertical pointer movement (`dy`).
- Quick Y-axis rotation -> left click.
- Quick Y-axis linear movement -> scroll up/down.

Because real phone axes may feel reversed, the UI includes invert switches.

## How to test without the final laptop-side project

A Python receiver is included:

```bash
cd AirMouseUdp
python tools/laptop_udp_mouse_receiver.py
```

Install dependency if you want the mouse to really move:

```bash
pip install pyautogui
```

Phone and laptop must be on the same Wi-Fi. Enter the laptop IPv4 address in the app.

## Perfetto

The app adds trace sections with `android.os.Trace`:

- `sensor_event_dispatch`
- `filter_accelerometer_highpass`
- `airmouse_filter_and_classify`

A sample config is included:

```text
tools/pbtx.config
```

Use it as the starting config for answering the Perfetto questions in the assignment.

## Important files

- `MainActivity.java` - main control panel and UDP sending flow.
- `CalibrationActivity.java` - separate calibration page.
- `SensorReader.java` - raw sensor reading on a background thread.
- `AirMouseController.java` - filtering, fusion, motion/click/scroll detection.
- `CalibrationData.java` - calibration storage and correction formulas.
- `UdpMouseClient.java` - UDP JSON sender + ACK retry logic.
- `MotionPreviewView.java` - visual movement/click/scroll preview.
- `tools/pbtx.config` - sample Perfetto trace config.
- `tools/laptop_udp_mouse_receiver.py` - optional laptop-side test receiver.
