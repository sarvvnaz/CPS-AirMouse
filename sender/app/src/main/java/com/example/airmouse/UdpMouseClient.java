package com.example.airmouse;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class UdpMouseClient {
    public interface StatusListener {
        void onStatus(String message);
    }

    private static final int ACK_TIMEOUT_MS = 180;
    private static final int ACK_RECEIVE_TIMEOUT_MS = 250;

    private final InetAddress host;
    private final int port;
    private final DatagramSocket socket;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<Integer, PendingPacket> pendingPackets = new ConcurrentHashMap<>();
    private final AtomicInteger seqGenerator = new AtomicInteger(100);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean moveSendBusy = new AtomicBoolean(false);
    private final Object sendLock = new Object();
    private final StatusListener statusListener;

    private static class PendingPacket {
        final String json;
        volatile long lastSentMs;
        volatile int attempts;

        PendingPacket(String json) {
            this.json = json;
            this.lastSentMs = 0L;
            this.attempts = 0;
        }
    }

    public UdpMouseClient(String ipAddress, int port, StatusListener listener) throws Exception {
        this.host = InetAddress.getByName(ipAddress.trim());
        this.port = port;
        this.statusListener = listener;
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(ACK_RECEIVE_TIMEOUT_MS);
        this.executor = Executors.newScheduledThreadPool(3);

        executor.execute(this::receiveAckLoop);
        executor.scheduleAtFixedRate(this::retryPendingPackets, ACK_TIMEOUT_MS, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        notifyStatus("UDP ready: " + host.getHostAddress() + ":" + port);
    }

    public void sendMove(double dx, double dy) {
        if (!running.get()) return;
        if (Math.abs(dx) < 0.01 && Math.abs(dy) < 0.01) return;

        final String json = String.format(Locale.US,
                "{\"type\":\"move\",\"dx\":%.3f,\"dy\":%.3f}", dx, dy);

        if (!moveSendBusy.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                sendRaw(json);
            } catch (Exception ex) {
                notifyStatus("Move send failed: " + ex.getMessage());
            } finally {
                moveSendBusy.set(false);
            }
        });
    }

    public int sendClick() {
        int seq = seqGenerator.incrementAndGet();
        String json = String.format(Locale.US, "{\"type\":\"click\",\"seq\":%d}", seq);
        sendReliable(seq, json);
        return seq;
    }

    public int sendScroll(int amount) {
        int seq = seqGenerator.incrementAndGet();
        String json = String.format(Locale.US,
                "{\"type\":\"scroll\",\"seq\":%d,\"amount\":%d}", seq, amount);
        sendReliable(seq, json);
        return seq;
    }

    private void sendReliable(int seq, String json) {
        if (!running.get()) return;
        PendingPacket packet = new PendingPacket(json);
        pendingPackets.put(seq, packet);
        executor.execute(() -> sendPending(seq, packet));
    }

    private void sendPending(int seq, PendingPacket packet) {
        if (!running.get()) return;
        try {
            sendRaw(packet.json);
            packet.lastSentMs = System.currentTimeMillis();
            packet.attempts++;
            if (packet.attempts == 1) {
                notifyStatus("Sent reliable packet seq=" + seq);
            }
        } catch (Exception ex) {
            notifyStatus("Send failed: " + ex.getMessage());
        }
    }

    private void retryPendingPackets() {
        if (!running.get()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, PendingPacket> entry : pendingPackets.entrySet()) {
            PendingPacket packet = entry.getValue();
            if (now - packet.lastSentMs >= ACK_TIMEOUT_MS) {
                sendPending(entry.getKey(), packet);
            }
        }
    }

    private void receiveAckLoop() {
        byte[] buffer = new byte[512];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(text);
                String type = json.optString("type", "");
                int seq = json.optInt("seq", -1);

                if ("ack".equals(type) && seq >= 0) {
                    PendingPacket removed = pendingPackets.remove(seq);
                    if (removed != null) {
                        notifyStatus("ACK received seq=" + seq);
                    }
                }
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Exception ex) {
                if (running.get()) {
                    notifyStatus("ACK receive error: " + ex.getMessage());
                }
            }
        }
    }

    private void sendRaw(String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, host, port);
        synchronized (sendLock) {
            socket.send(packet);
        }
    }

    public int getPendingCount() {
        return pendingPackets.size();
    }

    public void close() {
        if (!running.getAndSet(false)) return;
        pendingPackets.clear();
        try {
            socket.close();
        } catch (Exception ignored) {
        }
        executor.shutdownNow();
        notifyStatus("UDP stopped");
    }

    private void notifyStatus(String message) {
        if (statusListener != null) {
            statusListener.onStatus(message);
        }
    }
}