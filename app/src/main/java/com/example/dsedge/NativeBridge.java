package com.example.dsedge;

import java.util.concurrent.CopyOnWriteArrayList;

/** Glue between the native SDL3 loop (libmain.so) and the Java service. */
public final class NativeBridge {
    public interface Listener {
        void onFrame(float[] f, int slot);
        void onStatus(String s);
        /** The controller in this slot (0..3) was unplugged. */
        default void onPadGone(int slot) { }
    }

    /** Layout of the float[] frame sent from native code. */
    public static final int FRAME_LEN = 21;
    // 0 mask | 1..4 lx ly rx ry (0..255) | 5,6 l2 r2 (0..255) | 7..9 gyro rad/s | 10..12 accel m/s^2
    // 13..15 finger0 (down,x,y) | 16..18 finger1 | 19 battery % (-1 unknown) | 20 SDL power state

    static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    static volatile String lastStatus = "Starting...";

    static final int MAX_PADS = 4;
    /** USB ids and names of the controller in each slot, so DroidDeck can ignore the Android duplicate of it. */
    static final int[] vids = new int[MAX_PADS], pids = new int[MAX_PADS];
    static final String[] names = new String[MAX_PADS];

    private static volatile int rumbleStrong, rumbleWeak;

    private NativeBridge() {}

    /** Called from the native SDL thread. */
    static void onFrame(float[] f, int slot) {
        if (slot == 0 && f.length >= FRAME_LEN) f[0] = Settings.mapMask((int) f[0]);
        for (Listener l : listeners) l.onFrame(f, slot);
    }

    /** Called from the native SDL thread when a controller is opened in a slot. */
    static void onPadInfo(int slot, int vid, int pid, String name) {
        if (slot < 0 || slot >= MAX_PADS) return;
        vids[slot] = vid;
        pids[slot] = pid;
        names[slot] = name;
    }

    /** Called from the native SDL thread when a slot's controller goes away. */
    static void onPadGone(int slot) {
        if (slot < 0 || slot >= MAX_PADS) return;
        vids[slot] = 0;
        pids[slot] = 0;
        names[slot] = null;
        for (Listener l : listeners) l.onPadGone(slot);
    }

    /** Called from the native SDL thread. */
    static void onStatus(String s) {
        lastStatus = s;
        for (Listener l : listeners) l.onStatus(s);
    }

    /** Rumble for any player slot (0..3). */
    static void setRumble(int slot, int strong, int weak) {
        if (slot <= 0) { setRumble(strong, weak); return; }
        try { nativeSetRumble(slot, strong, weak); } catch (UnsatisfiedLinkError e) { }
    }

    static native void nativeSetRumble(int slot, int rumbleStrong, int rumbleWeak);

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
