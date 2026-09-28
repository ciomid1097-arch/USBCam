package com.usb.cam;

/** All tunables in one place, so swapping the encoder/protocol later is easy. */
public final class StreamConfig {
    private StreamConfig() {}

    /** TCP port; PC side runs `adb reverse tcp:PORT tcp:PORT` and listens on the same port. */
    public static final int PORT = 8420;

    /** Capture sizes offered in the UI: {width, height}. */
    public static final int[][] SIZES = {
            {640, 480},
            {1280, 720},
            {1920, 1080},
    };

    /** Default capture size index into SIZES. */
    public static final int DEFAULT_SIZE_INDEX = 1;

    /** JPEG quality for the frames (encoder-dependent; used by JPEG encoder). */
    public static final int JPEG_QUALITY = 80;

    /**
     * Frame header, little-endian:
     *   u32 payloadLength, then payloadLength bytes of payload (JPEG).
     *   The first payload byte of the first frame after connect is 0x01/0x00 = back/front camera.
     */
    public static final int HEADER_BYTES = 4;

    /** Camera facing ids. */
    public static final int FACING_BACK = 0;
    public static final int FACING_FRONT = 1;
}
