package com.example.dsedge;

import java.util.concurrent.CopyOnWriteArrayList;

/** Glue between the native SDL3 loop (libmain.so) and the Java service. */
public final class NativeBridge {
    public interface Listener {
        void onFrame(float[] f, int slot);
        void onStatus(String s);
        /** The controller in this slot (0..3) was unplugged. */
        default void onPadGone(int slot) { }
        /** Capabilities of the controller in this slot: which buttons/axes exist, touchpad count, and kind. */
        default void onPadCaps(int slot, int present, int axes, int touchpads, String kind) { }
    }

    /** Layout of the float[] frame sent from native code. */
    public static final int FRAME_LEN = 22;
    // 0 mask | 1..4 lx ly rx ry (0..255) | 5,6 l2 r2 (0..255) | 7..9 gyro rad/s | 10..12 accel m/s^2
    // 13..15 finger0 (down,x,y) | 16..18 finger1 | 19 battery % (-1 unknown) | 20 SDL power state | 21 extra-button mask (MISC2..6)

    static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    static volatile String lastStatus = "Starting...";

    static final int MAX_PADS = 4;
    /** USB ids and names of the controller in each slot, so DroidDeck can ignore the Android duplicate of it. */
    static final int[] vids = new int[MAX_PADS], pids = new int[MAX_PADS];
    static final String[] names = new String[MAX_PADS];

    private NativeBridge() {}

    /** Called from the native SDL thread. */
    static void onFrame(float[] f, int slot) {
        for (Listener l : listeners) l.onFrame(f, slot);
    }

    /** Called from the native SDL thread when a controller is opened in a slot. */
    static void onPadInfo(int slot, int vid, int pid, String name) {
        if (slot < 0 || slot >= MAX_PADS) return;
        vids[slot] = vid;
        pids[slot] = pid;
        names[slot] = name;
    }

    /** Capabilities of each slot's controller (present-button mask, axis flags, touchpad count, kind). */
    static final int[] present = new int[MAX_PADS];
    static final int[] axesMask = new int[MAX_PADS];
    static final int[] touchpadCount = new int[MAX_PADS];
    static final String[] kind = new String[MAX_PADS];

    /** Called from the native SDL thread when a controller is opened in a slot. */
    static void onPadCaps(int slot, int p, int a, int tp, String k) {
        if (slot < 0 || slot >= MAX_PADS) return;
        present[slot] = p;
        axesMask[slot] = a;
        touchpadCount[slot] = tp;
        kind[slot] = k;
        for (Listener l : listeners) l.onPadCaps(slot, p, a, tp, k);
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
        if (slot < 0 || slot >= MAX_PADS) return;
        try { nativeSetRumble(slot, strong, weak); } catch (UnsatisfiedLinkError e) { }
    }

    static native void nativeSetRumble(int slot, int rumbleStrong, int rumbleWeak);

    /** Pushes each player's lightbar (scaled by brightness) and player LED to its controller. */
    static void applyOutput() {
        try {
            for (int p = 0; p < MAX_PADS; p++) {
                int b = Settings.brightness[p];
                nativeSetLed(p, Settings.lightR[p] * b / 100, Settings.lightG[p] * b / 100, Settings.lightB[p] * b / 100);
                nativeSetPlayerLed(p, Settings.playerLed[p] - 1); // -1 = player LEDs off
            }
        } catch (UnsatisfiedLinkError e) {
            // The bridge screen has not been opened yet, so the native library is not loaded.
        }
    }

    /** Apply just one player's light (on a settings change). */
    static void applyOutput(int p) {
        if (p < 0 || p >= MAX_PADS) return;
        try {
            int b = Settings.brightness[p];
            nativeSetLed(p, Settings.lightR[p] * b / 100, Settings.lightG[p] * b / 100, Settings.lightB[p] * b / 100);
            nativeSetPlayerLed(p, Settings.playerLed[p] - 1);
        } catch (UnsatisfiedLinkError e) { }
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

    /** Hard on/off for the SDL3 driver: on = forced SDL3 USB, off = release the controller to Android. */
    static void applyDriver() {
        try {
            nativeSetDriver(Settings.driverEnabled ? 1 : 0);
            if (Settings.driverEnabled) nativeSetForce(Settings.forceUsb ? 1 : 0);
        } catch (UnsatisfiedLinkError e) { }
    }

    static native void nativeSetDriver(int on);

    /** Controller poll rate (loop period in ms). */
    static void applyRate() {
        try { nativeSetPollMs(Settings.pollMs); } catch (UnsatisfiedLinkError e) { }
    }

    static native void nativeSetPollMs(int ms);

    /** Per-slot lightbar rgb 0..255. */
    static native void nativeSetLed(int slot, int r, int g, int b);

    /** Per-slot player LEDs: -1 = off, 0..3 = player 1..4. */
    static native void nativeSetPlayerLed(int slot, int index);
}
