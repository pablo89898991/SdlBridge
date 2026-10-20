package com.example.dsedge;

import android.app.PendingIntent;
import android.content.Intent;
import android.hardware.usb.UsbInterface;
import android.os.Build;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

/**
 * Hosts SDL3 (which owns USB/HID controller access on Android), shows a live controller test screen and
 * settings, and keeps the controller claimed while another app (DroidDeck) is in front.
 */
public class BridgeActivity extends SDLActivity implements NativeBridge.Listener {
    private interface IntSink { void set(int v); }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusView, summaryView, usbView;
    private Button updateButton;
    private ControllerView pad;
    private String updateUrl;
    private long lastSummary;
    private static final float RAD_TO_DEG = 57.29578f;
    private static final int CALIB_SAMPLES = 180;      // ~0.7s at 4ms/frame
    private volatile int calibCount = -1;              // -1 = not calibrating
    private final float[] calibSum = new float[3];
    private int calibPlayer = 0;
    private int uiPlayer = 0;
    private LinearLayout playerBox;
    private Button[] playerTabs;
    private TextView gyroCalibView;
    private boolean pendingBubble;   // user asked for the bubble; waiting for overlay permission
    private TextView audioView;

    @Override protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Settings.init(this);
        UpdateChecker.BuildInfo info = UpdateChecker.readBuildInfo(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(16, 24, 16, 16);

        statusView = text("SDL3BridgeDroidDeck  " + info.tag + "  SDL " + info.sdl + "\n" + NativeBridge.lastStatus, 13f);
        usbView = text("USB: checking...", 12f);
        summaryView = text("Press every button and move everything to test it.", 12f);
        pad = new ControllerView(this);
        updateButton = button("Checking for updates...", v -> { if (updateUrl != null) openUrl(updateUrl); });

        root.addView(statusView);
        root.addView(usbView);
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(summaryView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Rumble", v -> rumbleTest()), weight());
        row.addView(button("Reset", v -> { pad.resetTested(); summaryView.setText(pad.summary()); }), weight());
        row.addView(button("Update", v -> checkUpdate()), weight());
        root.addView(row);

        // ---- Per-player settings (tabs) ----
        root.addView(header("Player settings"));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        playerTabs = new Button[Settings.PLAYERS];
        for (int i = 0; i < Settings.PLAYERS; i++) {
            final int pi = i;
            Button t = button("P" + (i + 1), v -> showPlayer(pi));
            playerTabs[i] = t;
            tabs.addView(t, weight());
        }
        root.addView(tabs);
        playerBox = new LinearLayout(this);
        playerBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(playerBox);
        showPlayer(0);

        // ---- SDL3 driver ----
        root.addView(header("SDL3 driver"));
        addSpinner(root, "Controller source", new String[] {
            "Forced: only SDL3's USB driver (full features, needs USB cable)",
            "Auto: also accept Android basic input (no gyro/back buttons on Edge)"
        }, Settings.forceUsb ? 0 : 1, v -> {
            Settings.forceUsb = v == 0;
            Settings.save();
            NativeBridge.applyForce();
        });
        root.addView(button("Force SDL3 USB driver", v -> forceUsbDriver()));
        root.addView(text("Plug the controller in with USB-C, tap the button and allow USB access "
                + "(choose 'always' to skip this next time). Works for any SDL3-supported controller.", 11f));

        // ---- Floating bubble ----
        root.addView(header("Floating bubble"));
        addCheck(root, "Show floating bubble over other apps", Settings.bubble, on -> {
            if (on) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    pendingBubble = true;   // enable automatically once permission is granted
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException ignored) { }
                    return;
                }
                Settings.bubble = true;
                startService(new Intent(this, BridgeService.class).setAction("BUBBLE").putExtra("on", true));
            } else {
                Settings.bubble = false;
                startService(new Intent(this, BridgeService.class).setAction("BUBBLE").putExtra("on", false));
            }
        });
        root.addView(text("A draggable bubble (like a chat head) that shows bridge status and opens this screen "
                + "on tap. Needs the \"Draw over other apps\" permission; if the switch does nothing, grant it and toggle again.", 11f));

        // ---- USB audio ----
        root.addView(header("USB audio"));
        audioView = text(audioInfo(), 12f);
        root.addView(audioView);
        root.addView(button("Switch audio output", v -> openAudioOutput()));
        root.addView(text("When the controller is plugged in via USB-C, Android may route audio to its headset jack/speaker. "
                + "Android controls routing, so this opens the system output picker to switch between the controller and the phone.", 11f));

        // ---- Controller mode ----
        root.addView(header("Controller mode"));
        addSpinner(root, "Present controller as", Settings.PRESENT_NAMES, Settings.presentAs, v -> {
            Settings.presentAs = v;
            Settings.save();
        });
        root.addView(text("Steam Deck: the controller is DroidDeck's Deck pad (default, full features). "
                + "DualSense / DualShock 4: Steam sees a NATIVE Sony controller (hidraw) with gyro, touchpad and back buttons. "
                + "Give Steam a few seconds, or restart the session, after changing.", 11f));
        addSpinner(root, "Poll rate", Settings.RATE_NAMES, rateIndex(), v -> {
            Settings.pollMs = Settings.RATE_MS[v];
            Settings.save();
            NativeBridge.applyRate();
        });
        root.addView(text("Higher rate = lower gyro/trackpad latency, a little more CPU and battery. No accuracy is lost either way.", 11f));

        root.addView(updateButton);
        root.addView(text("Orange = held now, green = worked at least once. "
                + "Leave this app open once, then switch to DroidDeck.", 11f));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(root);
        addContentView(scroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        NativeBridge.listeners.add(this);
        try {
            startForegroundService(new Intent(this, BridgeService.class));
        } catch (RuntimeException ignored) { }
        NativeBridge.applyDriver();   // restore the on/off driver state (bubble tap)
        NativeBridge.applyRate();
        NativeBridge.applyOutput(); // apply the saved light settings
        checkUpdate();
        ui.post(usbRefresh);
    }

    private void showPlayer(int p) {
        uiPlayer = p;
        for (int i = 0; i < playerTabs.length; i++)
            playerTabs[i].setBackgroundColor(i == p ? 0xFF1976D2 : 0xFF333333);
        playerBox.removeAllViews();
        buildPlayerSection(playerBox, p);
    }

    private void buildPlayerSection(LinearLayout box, final int p) {
        box.addView(header("Gyro \u2014 Player " + (p + 1)));
        addSlider(box, "Gyro speed % (100 = 1:1)", 800, Settings.gyroGain[p], v -> Settings.gyroGain[p] = Math.max(1, v));
        addSpinner(box, "Invert gyro axes", Settings.GYRO_INV_NAMES, Settings.gyroInvert[p], v -> { Settings.gyroInvert[p] = v; Settings.save(); });
        for (int i = 0; i < 3; i++) {
            final int out = i;
            addSpinner(box, "DroidDeck gyro " + "XYZ".charAt(i) + " comes from", Settings.AXIS_NAMES, Settings.gyroMap[p][i], v -> { Settings.gyroMap[p][out] = v; Settings.save(); });
        }
        addSpinner(box, "Apply remap/invert to accelerometer too", new String[] { "Yes (recommended)", "No" },
                Settings.accelFollows[p] ? 0 : 1, v -> { Settings.accelFollows[p] = v == 0; Settings.save(); });
        addSlider(box, "Gyro deadzone (deg/s)", 50, Settings.gyroDeadzone[p], v -> Settings.gyroDeadzone[p] = v);
        gyroCalibView = text(calibLabel(p), 11f);
        LinearLayout gc = new LinearLayout(this);
        gc.setOrientation(LinearLayout.HORIZONTAL);
        gc.addView(button("Calibrate (hold still)", v -> startGyroCalibration()), weight());
        gc.addView(button("Reset calibration", v -> resetGyroCalibration()), weight());
        box.addView(gc);
        box.addView(gyroCalibView);
        box.addView(text("Calibration samples the controller shown above (player 1 slot). Deadzone ignores tiny drift.", 11f));

        box.addView(header("Touchpad \u2014 Player " + (p + 1)));
        addCheck(box, "Invert touchpad X (left/right)", Settings.touchInvX[p], on -> Settings.touchInvX[p] = on);
        addCheck(box, "Invert touchpad Y (up/down)", Settings.touchInvY[p], on -> Settings.touchInvY[p] = on);
        addCheck(box, "Swap touchpad X and Y (before invert)", Settings.touchSwap[p], on -> Settings.touchSwap[p] = on);
        addSpinner(box, "Layout", Settings.TOUCH_NAMES, Settings.touchMode[p], v -> { Settings.touchMode[p] = v; Settings.save(); pad.postInvalidate(); });

        box.addView(header("Button remap \u2014 Player " + (p + 1)));
        box.addView(text("Map any controller button to a Deck grip (L4/R4/L5/R5) or a trackpad touch/click. "
                + "Useful for extra buttons like a Flydigi's C and Z.", 11f));
        for (int i = 0; i < Settings.REMAP_COUNT; i++) {
            final int target = i;
            addSpinner(box, Settings.REMAP_TARGETS[i] + " pressed by", Settings.SRC_NAMES, Settings.remap[p][i], v -> { Settings.remap[p][target] = v; Settings.save(); });
        }

        box.addView(header("Light \u2014 Player " + (p + 1)));
        addSlider(box, "Red", 255, Settings.lightR[p], v -> { Settings.lightR[p] = v; applyLight(p); });
        addSlider(box, "Green", 255, Settings.lightG[p], v -> { Settings.lightG[p] = v; applyLight(p); });
        addSlider(box, "Blue", 255, Settings.lightB[p], v -> { Settings.lightB[p] = v; applyLight(p); });
        addSlider(box, "Brightness %", 100, Settings.brightness[p], v -> { Settings.brightness[p] = v; applyLight(p); });
        addSpinner(box, "Player LEDs", Settings.PLAYER_NAMES, Settings.playerLed[p], v -> { Settings.playerLed[p] = v; Settings.save(); applyLight(p); });
    }

    private void applyLight(int p) { NativeBridge.applyOutput(p); }

    // ---------- small UI helpers ----------

    private TextView text(String s, float size) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(size);
        t.setGravity(Gravity.START);
        t.setPadding(0, 6, 0, 6);
        t.setText(s);
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 16f);
        t.setPadding(0, 28, 0, 6);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void addSpinner(LinearLayout root, String label, String[] names, int selected, final IntSink sink) {
        root.addView(text(label, 12f));
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        sp.setSelection(selected);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { sink.set(position); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        root.addView(sp);
    }

    private void addSlider(LinearLayout root, final String label, int max, int value, final IntSink sink) {
        final TextView t = text(label + ": " + value, 12f);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(value);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                t.setText(label + ": " + v);
                if (fromUser) sink.set(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { Settings.save(); }
        });
        root.addView(t);
        root.addView(sb);
    }

    private interface BoolSink { void set(boolean on); }

    private void addCheck(LinearLayout root, String label, boolean value, final BoolSink sink) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(Color.WHITE);
        c.setChecked(value);
        c.setOnCheckedChangeListener((v, on) -> { sink.set(on); Settings.save(); });
        root.addView(c);
    }

    // ---------- USB diagnostics ----------

    private final Runnable usbRefresh = new Runnable() {
        @Override public void run() {
            int n = BridgeService.clientCount;
            usbView.setText(usbInfo() + "\nDroidDeck link: " + (n > 0 ? (n + " app(s) bound — sending frames") : "NOT bound (open a DroidDeck game session)"));
            ui.postDelayed(this, 2000);
        }
    };

    /** Android's view of the controller on USB: is it there, and may this app talk to it? */
    private String usbInfo() {
        try {
            UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
            StringBuilder sb = new StringBuilder("USB: ");
            boolean any = false;
            for (UsbDevice d : um.getDeviceList().values()) {
                if (d.getVendorId() != 0x054C) continue;
                any = true;
                sb.append(String.format(java.util.Locale.ROOT, "Sony %04x:%04x access=%s  ",
                        d.getVendorId(), d.getProductId(), um.hasPermission(d) ? "yes" : "NO (unplug and replug, tap OK)"));
            }
            if (!any) sb.append("no Sony controller on USB (use a USB-C data cable)");
            return sb.toString();
        } catch (RuntimeException e) {
            return "USB: unavailable";
        }
    }

    /** Lists any USB / headset audio outputs Android currently sees (e.g. the controller's audio device). */
    private int rateIndex() {
        for (int i = 0; i < Settings.RATE_MS.length; i++) if (Settings.RATE_MS[i] == Settings.pollMs) return i;
        return 1;
    }

    private String audioInfo() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            StringBuilder sb = new StringBuilder("Audio out: ");
            boolean any = false;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                    any = true;
                    CharSequence nm = d.getProductName();
                    sb.append(nm != null ? nm : (t == AudioDeviceInfo.TYPE_USB_HEADSET ? "USB headset" : "USB audio")).append("  ");
                }
            }
            if (!any) sb.append("no USB audio device (plug the controller in with USB-C, or no audio interface)");
            return sb.toString();
        } catch (RuntimeException e) {
            return "Audio out: unavailable";
        }
    }

    /** Opens Android's media output switcher so the user can route audio to the controller or phone. */
    private void openAudioOutput() {
        // The media output panel: works on most devices; fall back to Sound settings.
        Intent panel = new Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(panel);
            return;
        } catch (RuntimeException ignored) { }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    // ---------- tests ----------

    private void rumbleTest() {
        NativeBridge.setRumble(uiPlayer, 220, 220);
        ui.postDelayed(() -> NativeBridge.setRumble(uiPlayer, 0, 0), 700);
    }

    // ---------- updates ----------

    private void checkUpdate() {
        updateUrl = null;
        updateButton.setText("Checking for updates...");
        UpdateChecker.check(this, (msg, url) -> {
            updateUrl = url;
            updateButton.setText(msg);
        });
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            updateButton.setText("Could not open the download link.");
        }
    }

    // ---------- SDL USB handling ----------

    @Override protected void onDestroy() {
        ui.removeCallbacks(usbRefresh);
        NativeBridge.listeners.remove(this);
        super.onDestroy();
    }

    // SDL releases the USB controller when the activity pauses or loses focus. Undo that so input
    // keeps flowing while DroidDeck is in front.
    /** Asks Android for USB access to every game-controller-like device, using SDL's own permission
     *  action so SDL's receiver opens the device straight away, then restarts SDL's enumeration. */
    private void forceUsbDriver() {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        int asked = 0, have = 0, seen = 0;
        if (um != null) {
            for (UsbDevice d : um.getDeviceList().values()) {
                if (!looksLikeController(d)) continue;
                seen++;
                if (um.hasPermission(d)) { have++; continue; }
                Intent i = new Intent("org.libsdl.app.USB_PERMISSION").setPackage(getPackageName());
                int flags = Build.VERSION.SDK_INT >= 31 ? 0x02000000 : 0; // FLAG_MUTABLE
                try {
                    um.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
                    asked++;
                } catch (RuntimeException ignored) { }
            }
        }
        usbView.setText(seen == 0
                ? "No USB controller found. Use a USB-C cable (Bluetooth is not enough) and try again."
                : "USB controllers: " + seen + ", already allowed: " + have + ", asked now: " + asked);
        keepControllerClaimed();
        ui.postDelayed(NativeBridge::rescan, asked > 0 ? 4000 : 300);
    }

    private static boolean looksLikeController(UsbDevice d) {
        if (d.getVendorId() == 0x054C) return true; // Sony
        for (int n = 0; n < d.getInterfaceCount(); n++) {
            UsbInterface f = d.getInterface(n);
            int c = f.getInterfaceClass();
            if (c == 255 || (c == 3 && f.getInterfaceProtocol() == 0)) return true; // vendor-specific (Xbox) or generic HID
        }
        return false;
    }

    @Override protected void onResume() {
        super.onResume();
        if (android.provider.Settings.canDrawOverlays(this)) {
            if (pendingBubble) {
                pendingBubble = false;
                Settings.bubble = true;
                Settings.save();
            }
            if (Settings.bubble) {
                startService(new Intent(this, BridgeService.class).setAction("BUBBLE").putExtra("on", true));
            }
        }
    }

    @Override protected void onPause() {
        super.onPause();
        keepControllerClaimed();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        keepControllerClaimed();
    }

    private void keepControllerClaimed() {
        // When the driver is off, let go of the controller (freeze = release the USB interface) so Android can use it.
        if (mHIDDeviceManager != null) mHIDDeviceManager.setFrozen(!Settings.driverEnabled);
    }

    /** Called from the service when the bubble toggles the driver: release or re-claim the USB interface. */
    static void setHidFrozen(boolean frozen) {
        if (mHIDDeviceManager != null) mHIDDeviceManager.setFrozen(frozen);
    }

    // ---------- NativeBridge.Listener ----------

    private String calibLabel(int p) {
        float[] b = Settings.gyroBias[p];
        if (b[0] == 0f && b[1] == 0f && b[2] == 0f) return "Calibration: none";
        return String.format(java.util.Locale.ROOT, "Calibration bias (deg/s): x=%.2f y=%.2f z=%.2f", b[0], b[1], b[2]);
    }

    private void startGyroCalibration() {
        calibPlayer = uiPlayer;
        calibSum[0] = calibSum[1] = calibSum[2] = 0f;
        calibCount = 0;
        if (gyroCalibView != null) gyroCalibView.setText("Hold the controller still...");
    }

    private void resetGyroCalibration() {
        calibCount = -1;
        float[] b = Settings.gyroBias[uiPlayer];
        b[0] = b[1] = b[2] = 0f;
        Settings.save();
        if (gyroCalibView != null) gyroCalibView.setText(calibLabel(uiPlayer));
    }

    @Override public void onFrame(float[] f, int slot) {
        if (slot != 0) return; // the test screen shows player 1
        if (calibCount >= 0 && f.length >= 13) {
            calibSum[0] += f[7]; calibSum[1] += f[8]; calibSum[2] += f[9];
            if (++calibCount >= CALIB_SAMPLES) {
                float[] b = Settings.gyroBias[calibPlayer];
                b[0] = calibSum[0] / CALIB_SAMPLES * RAD_TO_DEG;
                b[1] = calibSum[1] / CALIB_SAMPLES * RAD_TO_DEG;
                b[2] = calibSum[2] / CALIB_SAMPLES * RAD_TO_DEG;
                Settings.save();
                calibCount = -1;
                ui.post(() -> { if (gyroCalibView != null && uiPlayer == calibPlayer) gyroCalibView.setText(calibLabel(calibPlayer)); });
            }
        }
        pad.update(f);
        long now = System.nanoTime();
        if (now - lastSummary > 300_000_000L) {
            lastSummary = now;
            ui.post(() -> summaryView.setText(pad.summary()));
        }
    }

    @Override public void onPadCaps(int slot, int present, int axes, int touchpads, String kind) {
        if (slot != 0) return;
        pad.setCaps(present, axes, touchpads, kind);
    }

    @Override public void onStatus(final String s) {
        ui.post(() -> {
            statusView.setText("SDL3BridgeDroidDeck\n" + s);
            if (s.startsWith("Controller disconnected") || s.startsWith("Waiting")) pad.setDisconnected();
        });
    }
}
