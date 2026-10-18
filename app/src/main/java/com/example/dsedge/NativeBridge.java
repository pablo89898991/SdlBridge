package com.example.dsedge;

import java.util.concurrent.CopyOnWriteArrayList;

/** Glue between the native SDL3 loop (libmain.so) and the Java service. */
public final class NativeBridge {
    public interface Listener {
        void onFrame(float[] f);
        void onStatus(String s);
    }

    /** Layout of the float[] frame sent from native code. */
    public static final int FRAME_LEN = 21;
    // 0 mask | 1..4 lx ly rx ry (0..255) | 5,6 l2 r2 (0..255) | 7..9 gyro rad/s | 10..12 accel m/s^2
    // 13..15 finger0 (down,x,y) | 16..18 finger1 | 19 battery % (-1 unknown) | 20 SDL power state

    static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    static volatile String lastStatus = "Starting...";

    private NativeBridge() {}

    /** Called from the native SDL thread. */
    static void onFrame(float[] f) {
        for (Listener l : listeners) l.onFrame(f);
    }

    /** Called from the native SDL thread. */
    static void onStatus(String s) {
        lastStatus = s;
        for (Listener l : listeners) l.onStatus(s);
    }

    /** rumble strong/weak 0..255, lightbar rgb 0..255. */
    static native void nativeSetOutput(int rumbleStrong, int rumbleWeak, int r, int g, int b);
}
