package com.example.dsedge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.libsdl.app.HIDDeviceManager;
import org.libsdl.app.SDL;
import org.libsdl.app.SDLActivity;

/**
 * Where SDL3 runs.
 *
 * Normally headless, inside the bridge's background service -- no screen has to open for the bridge to work. It
 * does what SDL's own SDLActivity does at start-up, minus the window: load the libraries, set up SDL's Java side,
 * take USB/HID access (HIDDeviceManager), then run SDL_main (main.c) on its own thread. The bridge only uses SDL's
 * gamepad / joystick / hidapi parts, which need no Activity.
 *
 * Self-protecting: a marker is saved before every background start and cleared when SDL3's loop reports its first
 * status. If SDL3 stays silent for 10 s (the process is then restarted), or the app died during the attempt, the
 * next start uses the fallback instead -- SDL3 inside an invisible screen kept in the background (SdlHostActivity),
 * the way the bridge always worked -- until the app is updated. Every step, and the previous run's crash report
 * if there was one, is sent to DroidDeck's session log ("sdl3bridge: engine: ...").
 */
final class SdlEngine {
    private static final String TAG = "SDL3Bridge";
    private static final long CONFIRM_MS = 10_000;
    private static volatile boolean started, confirmed, fallback, failed;
    private static volatile HIDDeviceManager hid;
    /** Why the engine could not start (shown in the status), or null. */
    static volatile String error;
    /** How SDL3 runs now, for the status / DroidDeck's tab. */
    static volatile String mode = "not started";

    private SdlEngine() {}

    static boolean isRunning() { return started && (fallback ? SdlHostActivity.running : true); }

    private static void log(String line) {
        Log.i(TAG, line);
        NativeBridge.onBridgeLog("engine: " + line);
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("engine", Context.MODE_PRIVATE); }

    private static long version(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** Starts SDL once per process (any thread; safe to call again). */
    static synchronized void start(Context context) {
        final Context app = context.getApplicationContext();
        NativeBridge.appContext = app;
        Settings.init(app);
        if (started) {
            if (fallback && !SdlHostActivity.running) startHost(app);
            return;
        }
        started = true;
        SharedPreferences p = prefs(app);
        long ver = version(app);
        String last = p.getString("state", "");
        boolean sameBuild = p.getLong("version", -1) == ver;
        if (sameBuild && (last.equals("starting") || last.startsWith("failed"))) {
            String why = last.equals("starting") ? "the app stopped while SDL3 was starting in the background last time" : last;
            if (last.equals("starting")) p.edit().putString("state", "failed: " + why).commit();
            fallback = true;
            mode = "hidden screen (fallback: " + why + ")";
            log("SDL3 can't run in the background service on this phone (" + why + "); using the fallback: "
                + "a hidden screen kept in the background. Update SDL3Bridge to try the background service again.");
            forwardPreviousLog();
            startHost(app);
            return;
        }
        p.edit().putString("state", "starting").putLong("version", ver).commit();   // commit: must survive a crash
        log("starting SDL3 in the background service (build " + ver + ")");
        try {
            System.loadLibrary("SDL3");
            System.loadLibrary("main");
            log("libraries loaded");
            SDL.setupJNI();
            SDL.initialize();
            log("SDL3's Java side ready");
            hid = HIDDeviceManager.acquire(app);
            hid.setFrozen(!Settings.driverEnabled);
            log("USB/HID access ready");
        } catch (Throwable e) {
            fail(app, "SDL3 could not start: " + e);
            restartForFallback("SDL3 could not start in the background service");
            return;
        }
        final String main = app.getApplicationInfo().nativeLibraryDir + "/libmain.so";
        Thread t = new Thread(() -> {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); } catch (RuntimeException ignored) { }
            try {
                SDLActivity.nativeInitMainThread();
                int rc = SDLActivity.nativeRunMain(main, "SDL_main", new String[0]);
                SDLActivity.nativeCleanupMainThread();
                fail(app, "SDL3's loop ended (" + rc + ")");
            } catch (Throwable e) {
                fail(app, "SDL3 stopped: " + e);
            }
            restartForFallback("SDL3's loop is not running");
        }, "SDLThread");
        error = null;
        mode = "background service";
        t.start();
        log("SDL3 thread started; waiting for its first status");
        // Settings the native loop reads (driver, hook-up, rate, lights), as the bridge screen used to apply.
        NativeBridge.applyDriver();
        NativeBridge.applyRate();
        NativeBridge.applyOutput();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (confirmed || fallback || failed) return;   // working, or already failed (and restarting)
            fail(app, "SDL3 did not answer within " + (CONFIRM_MS / 1000) + " s in the background service");
            restartForFallback("SDL3 did not answer");
        }, CONFIRM_MS);
    }

    /** SDL3's loop reported a status: the background start worked. */
    static void onNativeStatus() {
        if (confirmed || fallback || !started) return;
        confirmed = true;
        Context c = NativeBridge.appContext;
        if (c != null) prefs(c).edit().putString("state", "ok").apply();
        log("SDL3 is running in the background service");
    }

    /** The process restarts (DroidDeck's connection brings the service back) and the next start uses the fallback. */
    private static void restartForFallback(String why) {
        log(why + "; restarting SDL3Bridge in 2 s so the fallback takes over");
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> android.os.Process.killProcess(android.os.Process.myPid()), 2000);   // lets the log reach DroidDeck
    }

    private static void fail(Context app, String why) {
        failed = true;
        error = why;
        Log.e(TAG, why);
        NativeBridge.onBridgeLog("engine: " + why);
        prefs(app).edit().putString("state", "failed: " + why).commit();
    }

    private static void startHost(Context app) {
        try {
            app.startActivity(new Intent(app, SdlHostActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException e) {
            log("could not start the fallback screen: " + e);
        }
    }

    /** The previous run's own log lines about SDL / the bridge / a crash, into DroidDeck's session log. */
    private static void forwardPreviousLog() {
        new Thread(() -> {
            java.util.ArrayDeque<String> keep = new java.util.ArrayDeque<>();
            try {
                java.lang.Process p = Runtime.getRuntime().exec(new String[] { "logcat", "-d", "-v", "time", "-t", "3000" });
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("AndroidRuntime") || line.contains(" F/") || line.contains("/SDL")
                                || line.contains("SDL3Bridge") || line.contains("libc") || line.contains("DEBUG")) {
                            keep.addLast(line);
                            while (keep.size() > 80) keep.removeFirst();
                        }
                    }
                }
            } catch (Exception e) {
                keep.addLast("(could not read the log: " + e + ")");
            }
            NativeBridge.onBridgeLog("engine: ---- the bridge's own log (last " + keep.size() + " relevant lines) ----");
            for (String l : keep) NativeBridge.onBridgeLog("engine: | " + l);
        }, "engine-log").start();
    }

    /** Driver off = release the USB interface so Android can use the controller; on = claim it again. */
    static void setHidFrozen(boolean frozen) {
        HIDDeviceManager h = hid;
        if (h != null) h.setFrozen(frozen);
        else SdlHostActivity.setHidFrozen(frozen);
    }
}
