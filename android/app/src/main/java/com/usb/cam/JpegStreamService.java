package com.usb.cam;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that owns the camera: runs a continuous reprocessing-free capture
 * session producing JPEGs (camera2 supports JPEG as an output target directly), and
 * pushes each frame into FrameSender.
 */
public class JpegStreamService extends Service {

    private static final String TAG = "JpegStreamService";

    private static volatile boolean serviceRunning = false;

    public static boolean isServiceRunning() {
        return serviceRunning;
    }
    private static final String CHANNEL_ID = "stream";
    private static final int NOTIFICATION_ID = 1;

    public static final String EXTRA_SIZE_INDEX = "size_index";
    public static final String EXTRA_FACING = "facing";

    private final FrameSender sender = new FrameSender();

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder requestBuilder;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean lastConnected = false;

    private int facing = StreamConfig.FACING_BACK;
    private Size size = new Size(1280, 720);
    private int rotationDegrees = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        cameraThread = new HandlerThread("camera-thread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        sender.setListener(connected -> {
            if (connected != lastConnected) {
                lastConnected = connected;
                Log.i(TAG, connected ? "PC connected" : "PC disconnected");
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("stop", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            int newFacing = intent.getIntExtra(EXTRA_FACING, facing);
            int idx = intent.getIntExtra(EXTRA_SIZE_INDEX, StreamConfig.DEFAULT_SIZE_INDEX);
            idx = Math.max(0, Math.min(idx, StreamConfig.SIZES.length - 1));
            Size newSize = new Size(StreamConfig.SIZES[idx][0], StreamConfig.SIZES[idx][1]);
            boolean changed = newFacing != facing || !newSize.equals(size);
            facing = newFacing;
            size = newSize;
            cameraHandler.post(() -> { if (changed || camera == null) openCameraAndStream(); });
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        serviceRunning = true;
        sender.start();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usbcam:stream");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        // If killed, let Android restart it; settings come from the activity on next manual start.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        serviceRunning = false;
        if (wakeLock != null) { wakeLock.release(); wakeLock = null; }
        closeCamera();
        sender.stop();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        cameraHandler.post(this::restartSession);
    }

    // ---------- notification ----------

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "USB camera stream",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("USB camera streaming")
                .setContentText("Sending camera frames over USB")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ---------- camera ----------

    private String cameraIdFor(int facing) throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            Integer f = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (f == null) continue;
            if (facing == StreamConfig.FACING_BACK && f == CameraCharacteristics.LENS_FACING_BACK)
                return id;
            if (facing == StreamConfig.FACING_FRONT && f == CameraCharacteristics.LENS_FACING_FRONT)
                return id;
        }
        return null;
    }

    private void openCameraAndStream() {
        closeCamera();
        try {
            String id = cameraIdFor(facing);
            if (id == null) {
                Log.e(TAG, "no camera for facing " + facing);
                return;
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "camera permission missing");
                return;
            }
            Size chosen = chooseSize(id);
            rotationDegrees = rotationFor(id);
            Log.i(TAG, "opening camera " + id + " size " + chosen + " rot " + rotationDegrees);
            cameraManager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) {
                    camera = cam;
                    startSession();
                }
                @Override public void onDisconnected(CameraDevice cam) { closeCamera(); }
                @Override public void onError(CameraDevice cam, int error) {
                    Log.e(TAG, "camera error " + error);
                    closeCamera();
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "open failed", e);
        }
    }

    /** Pick the requested size if supported, else the closest supported JPEG size. */
    private Size chooseSize(String id) throws CameraAccessException {
        CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
        Size[] jpegs = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                .getOutputSizes(android.graphics.ImageFormat.JPEG);
        Size best = null;
        long want = (long) size.getWidth() * size.getHeight();
        long bestDiff = Long.MAX_VALUE;
        for (Size s : jpegs) {
            long diff = Math.abs((long) s.getWidth() * s.getHeight() - want);
            if (diff < bestDiff) { bestDiff = diff; best = s; }
        }
        return best != null ? best : size;
    }

    private int rotationFor(String id) throws CameraAccessException {
        CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
        Integer sensorOrient = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensor = sensorOrient == null ? 90 : sensorOrient;
        // Device natural orientation: 0 portrait, 90/270 landscape.
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        int device = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY).getRotation() * 90;
        int facingBack = facing == StreamConfig.FACING_BACK ? 1 : -1;
        return ((sensor - device) * facingBack + 360) % 360;
    }

    private void startSession() {
        try {
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    android.graphics.ImageFormat.JPEG, 3);
            reader.setOnImageAvailableListener(this::onImage, cameraHandler);

            List<android.view.Surface> surfaces = new ArrayList<>();
            surfaces.add(reader.getSurface());

            camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        requestBuilder.addTarget(reader.getSurface());
                        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                        Log.i(TAG, "streaming started " + size);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "repeating request failed", e);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    Log.e(TAG, "session configure failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "startSession failed", e);
        }
    }

    private void restartSession() {
        if (camera == null) return;
        closeSessionOnly();
        openCameraAndStream();
    }

    private void closeSessionOnly() {
        if (session != null) { try { session.close(); } catch (Exception ignored) {} session = null; }
        if (reader != null) { reader.close(); reader = null; }
    }

    private void closeCamera() {
        closeSessionOnly();
        if (camera != null) { camera.close(); camera = null; }
    }

    // ---------- frame pump ----------

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            if (sender.isRunning()) {
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] jpeg = new byte[buf.remaining()];
                buf.get(jpeg);
                if (jpeg.length > 3) {
                    byte[] out = new byte[jpeg.length + 1];
                    out[0] = (byte) facing;
                    System.arraycopy(jpeg, 0, out, 1, jpeg.length);
                    sender.offer(out);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "frame error", e);
        } finally {
            if (img != null) img.close();
        }
    }

}

