package com.example.dsedge;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** USB ids of the controller SDL is reading, so DroidDeck can ignore the Android duplicate of it. */
    static volatile int vid, pid;
    private static final Pattern IDS = Pattern.compile("vid=([0-9a-f]{4}) pid=([0-9a-f]{4})");

    private static volatile int rumbleStrong, rumbleWeak;

    private NativeBridge() {}

    /** Called from the native SDL thread. */
    static void onFrame(float[] f) {
        if (f.length >= FRAME_LEN) f[0] = Settings.mapMask((int) f[0]);
        for (Listener l : listeners) l.onFrame(f);
    }

    /** Called from the native SDL thread. */
    static void onStatus(String s) {
        lastStatus = s;
        Matcher m = IDS.matcher(s);
        if (m.find()) {
            vid = Integer.parseInt(m.group(1), 16);
            pid = Integer.parseInt(m.group(2), 16);
        } else if (!s.startsWith("Connected")) {
            vid = 0;
            pid = 0;
        }
        for (Listener l : listeners) l.onStatus(s);
    }

    static void setRumble(int strong, int weak) {
        rumbleStrong = strong;
        rumbleWeak = weak;
        applyOutput();
    }

    /** Pushes rumble, lightbar (scaled by brightness) and player LED to the controller. */
    static void applyOutput() {
        try {
            int b = Settings.brightness;
            nativeSetOutput(rumbleStrong, rumbleWeak, Settings.lightR * b / 100, Settings.lightG * b / 100, Settings.lightB * b / 100);
            nativeSetPlayer(Settings.player - 1); // -1 = player LEDs off
        } catch (UnsatisfiedLinkError e) {
            // The bridge screen has not been opened yet, so the native library is not loaded.
        }
    }

    /** Forced mode: ignore Android's basic input and only use SDL's own USB driver. */
    static void applyForce() {
        try { nativeSetForce(Settings.forceUsb ? 1 : 0); } catch (UnsatisfiedLinkError e) { }
    }

    static void rescan() {
        try { nativeRescan(); } catch (UnsatisfiedLinkError e) { }
    }

    static native void nativeSetForce(int on);

    static native void nativeRescan();

    /** rumble strong/weak 0..255, lightbar rgb 0..255. */
    static native void nativeSetOutput(int rumbleStrong, int rumbleWeak, int r, int g, int b);

    /** -1 = player LEDs off, 0..3 = player 1..4. */
    static native void nativeSetPlayer(int index);
}
