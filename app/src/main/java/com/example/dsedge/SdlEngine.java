package com.example.dsedge;

import android.content.Context;
import android.util.Log;

import org.libsdl.app.HIDDeviceManager;
import org.libsdl.app.SDL;
import org.libsdl.app.SDLActivity;

/**
 * SDL3 running headless inside the bridge's background service -- no screen has to open for the bridge to work.
 *
 * It does what SDL's own SDLActivity does at start-up, minus the window: load the libraries, set up SDL's Java
 * side, take USB/HID access (HIDDeviceManager), then run SDL_main (main.c) on its own thread. The bridge only uses
 * SDL's gamepad / joystick / hidapi parts, which need no Activity: SDL keeps its Activity context (only used for
 * APK assets and the phone's own vibrator) null, as SDL.initialize() leaves it.
 *
 * The bridge's screen (BridgeActivity) is now a plain Activity on top of this; it can open and close at any time
 * while the engine keeps running, until the app is force-stopped.
 */
final class SdlEngine {
    private static final String TAG = "SDL3Bridge";
    private static volatile boolean started;
    private static volatile HIDDeviceManager hid;
    /** Why the engine could not start (shown in the status), or null. */
    static volatile String error;

    private SdlEngine() {}

    static boolean isRunning() { return started; }

    /** Starts SDL once per process (any thread; safe to call again). */
    static synchronized void start(Context context) {
        if (started) return;
        final Context app = context.getApplicationContext();
        NativeBridge.appContext = app;
        Settings.init(app);
        try {
            System.loadLibrary("SDL3");
            System.loadLibrary("main");
            SDL.setupJNI();
            SDL.initialize();
            hid = HIDDeviceManager.acquire(app);
            hid.setFrozen(!Settings.driverEnabled);
        } catch (Throwable e) {
            error = "SDL3 could not start: " + e;
            Log.e(TAG, error, e);
            return;
        }
        final String main = app.getApplicationInfo().nativeLibraryDir + "/libmain.so";
        Thread t = new Thread(() -> {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); } catch (RuntimeException ignored) { }
            try {
                SDLActivity.nativeInitMainThread();
                int rc = SDLActivity.nativeRunMain(main, "SDL_main", new String[0]);
                SDLActivity.nativeCleanupMainThread();
                Log.w(TAG, "SDL main loop ended (" + rc + ")");
            } catch (Throwable e) {
                error = "SDL3 stopped: " + e;
                Log.e(TAG, error, e);
            }
            started = false;
        }, "SDLThread");
        started = true;
        error = null;
        t.start();
        // Settings the native loop reads (driver, hook-up, rate, lights), as the bridge screen used to apply.
        NativeBridge.applyDriver();
        NativeBridge.applyRate();
        NativeBridge.applyOutput();
        Log.i(TAG, "SDL3 started in the background service");
    }

    /** Driver off = release the USB interface so Android can use the controller; on = claim it again. */
    static void setHidFrozen(boolean frozen) {
        HIDDeviceManager h = hid;
        if (h != null) h.setFrozen(frozen);
    }
}
