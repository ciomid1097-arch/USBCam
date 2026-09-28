package com.usb.cam;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connects to the PC listener on 127.0.0.1:PORT (reachable thanks to `adb reverse`)
 * and pushes JPEG frames over TCP. Only the newest frame is kept: if the PC is slow,
 * older frames are dropped so latency never grows.
 *
 * Wire format (little-endian), per frame:
 *   u32 payloadLength, then payloadLength bytes (a JPEG image).
 */
final class FrameSender {

    interface Listener {
        /** Called from the sender thread on connection state changes. */
        void onStateChange(boolean connected);
    }

    private final BlockingQueue<byte[]> inbox = new ArrayBlockingQueue<>(2);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Listener listener;

    void setListener(Listener l) {
        listener = l;
    }

    boolean isRunning() {
        return running.get();
    }

    /** Drop any queued frame so the next one sent is fresh. */
    void dropStale() {
        inbox.clear();
    }

    /** Offer a frame; returns false if a frame is already queued (it is intentionally dropped). */
    boolean offer(byte[] jpeg) {
        return inbox.offer(jpeg);
    }

    /** Starts the sender loop (connect, send, reconnect on failure). Idempotent. */
    synchronized void start() {
        if (running.getAndSet(true)) return;
        Thread t = new Thread(this::loop, "frame-sender");
        t.setDaemon(true);
        t.start();
    }

    /** Stops the loop and closes the socket. */
    void stop() {
        running.set(false);
        inbox.clear();
        synchronized (this) {
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) {}
                socket = null;
            }
        }
    }

    private Socket socket;

    private void loop() {
        while (running.get()) {
            Socket s = null;
            try {
                s = new Socket();
                s.bind(null);
                s.connect(new InetSocketAddress("127.0.0.1", StreamConfig.PORT), 1500);
                s.setTcpNoDelay(true);
                s.setSendBufferSize(64 * 1024);
                synchronized (this) { socket = s; }
                notifyState(true);
                try {
                    sendLoop(s);
                } catch (InterruptedException ie) {
                    notifyState(false);
                }
                notifyState(false);
            } catch (IOException e) {
                notifyState(false);
            } finally {
                if (s != null) try { s.close(); } catch (IOException ignored) {}
                synchronized (this) { socket = null; }
            }
            if (!running.get()) break;
            sleepIdle();
        }
    }

    /** While connected: pull the newest frame and write it. Blocks when idle. */
    private void sendLoop(Socket s) throws IOException, InterruptedException {
        OutputStream out = s.getOutputStream();
        ByteBuffer len = ByteBuffer.allocate(StreamConfig.HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        while (running.get()) {
            byte[] frame;
            if (inbox.isEmpty()) {
                frame = inbox.poll(250, TimeUnit.MILLISECONDS);
                if (frame == null) continue; // idle heartbeat tick keeps us responsive
            } else {
                frame = inbox.poll(); // newest queued frame; clear anything older behind it
                while (!inbox.isEmpty()) frame = inbox.poll();
            }
            len.clear();
            len.putInt(frame.length);
            out.write(len.array());
            out.write(frame);
            out.flush();
        }
    }

    /** Not connected yet: wait, but wake early if a frame arrives so start latency is low. */
    private void sleepIdle() {
        try {
            inbox.poll(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
    }

    private void notifyState(boolean connected) {
        Listener l = listener;
        if (l != null) l.onStateChange(connected);
    }
}
