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
        /** Steam Controller (2026) raw passthrough: one input report (id + payload without the id byte). */
        default void onTritonState(int reportId, byte[] payload) { }
        /** The passed-through Steam Controller (2026) appeared / went away. */
        default void onTritonConnected(boolean connected, String what) { }
        /** Answer to a feature read DroidDeck forwarded (null = the controller did not answer). */
        default void onTritonFeature(long request, byte[] report) { }
        /** Steam Native Hook-up: a controller was handed to Steam in [slot]; identity = HookupIdentity bundle. */
        default void onHookupAdded(int slot, android.os.Bundle identity) { }
        default void onHookupInput(int slot, byte[] report) { }
        default void onHookupRemoved(int slot) { }
        default void onHookupReply(int slot, long request, byte[] report) { }
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

    // ---------- Steam Controller (2026) raw passthrough (native/triton.h) ----------
    static volatile boolean tritonConnected;
    static volatile String tritonWhat = "";

    /** Called from the native SDL thread for every state report. */
    static void onTritonState(int reportId, byte[] payload) {
        for (Listener l : listeners) l.onTritonState(reportId, payload);
    }

    /** Called from the native SDL thread. */
    static void onTritonConnected(boolean connected, String what) {
        tritonConnected = connected;
        tritonWhat = what == null ? "" : what;
        for (Listener l : listeners) l.onTritonConnected(connected, tritonWhat);
    }

    /** Called from the native SDL thread. */
    static void onTritonFeature(long request, byte[] report) {
        for (Listener l : listeners) l.onTritonFeature(request, report);
    }

    /** An output report (feature = false) or a feature-report write (feature = true) from Steam, report id first. */
    static void tritonOutput(byte[] report, boolean feature) {
        try { nativeTritonOutput(report, feature); } catch (UnsatisfiedLinkError e) { }
    }

    static void tritonGetFeature(long request, int reportId) {
        try { nativeTritonGetFeature(request, reportId); } catch (UnsatisfiedLinkError e) { }
    }

    /** Game rumble for the passed-through controller, 0..65535 per motor. */
    static void tritonRumble(int left, int right) {
        try { nativeTritonRumble(left, right); } catch (UnsatisfiedLinkError e) { }
    }

    static native void nativeTritonOutput(byte[] report, boolean feature);
    static native void nativeTritonGetFeature(long request, int reportId);
    static native void nativeTritonRumble(int left, int right);

    // ---------- Steam Native Hook-up (native/hookup.h) ----------
    static volatile android.content.Context appContext;
    static final android.os.Bundle[] hookupIdentity = new android.os.Bundle[MAX_PADS];

    static void onHookupAdded(int slot, String path, int vid, int pid, int ifaceIndex) {
        if (slot < 0 || slot >= MAX_PADS) return;
        android.content.Context c = appContext;
        android.os.Bundle id = c == null ? null : HookupIdentity.build(c, slot, path, vid, pid, ifaceIndex);
        if (id == null) return;
        hookupIdentity[slot] = id;
        for (Listener l : listeners) l.onHookupAdded(slot, id);
    }

    /** Called from the native SDL thread just before a controller is opened raw. */
    static void prefetchHookupDescriptor(String path) {
        android.content.Context c = appContext;
        if (c != null) HookupIdentity.prefetch(c, path);
    }

    static void onHookupInput(int slot, byte[] report) {
        for (Listener l : listeners) l.onHookupInput(slot, report);
    }

    static void onHookupRemoved(int slot) {
        if (slot < 0 || slot >= MAX_PADS) return;
        hookupIdentity[slot] = null;
        for (Listener l : listeners) l.onHookupRemoved(slot);
    }

    static void onHookupReply(int slot, long request, byte[] report) {
        for (Listener l : listeners) l.onHookupReply(slot, request, report);
    }

    static void applyHookup() {
        try { nativeSetHookup(Settings.isHookup() ? 1 : 0); } catch (UnsatisfiedLinkError e) { }
    }

    static void hookupOutput(int slot, int kind, byte[] report) {
        try { nativeHookupOutput(slot, kind, report); } catch (UnsatisfiedLinkError e) { }
    }

    static void hookupGet(int slot, long request, int reportId, boolean input, int length) {
        try { nativeHookupGet(slot, request, reportId, input, length); } catch (UnsatisfiedLinkError e) { }
    }

    static native void nativeSetHookup(int on);
    static native void nativeHookupOutput(int slot, int kind, byte[] report);
    static native void nativeHookupGet(int slot, long request, int reportId, boolean input, int length);

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
            nativeSetHookup(Settings.isHookup() ? 1 : 0);
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
