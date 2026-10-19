package com.example.dsedge;

import android.app.PendingIntent;
import android.content.Intent;
import android.hardware.usb.UsbInterface;
import android.os.Build;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
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

        // ---- Gyro ----
        root.addView(header("Gyro (sent to DroidDeck)"));
        addSlider(root, "Gyro speed % (100 = 1:1)", 800, Settings.gyroGain, v -> Settings.gyroGain = Math.max(1, v));
        addSpinner(root, "Invert gyro axes", Settings.GYRO_INV_NAMES, Settings.gyroInvert, v -> {
            Settings.gyroInvert = v;
            Settings.save();
        });
        for (int i = 0; i < 3; i++) {
            final int out = i;
            addSpinner(root, "DroidDeck gyro " + "XYZ".charAt(i) + " comes from", Settings.AXIS_NAMES, Settings.gyroMap[i], v -> {
                Settings.gyroMap[out] = v;
                Settings.save();
            });
        }
        addSpinner(root, "Apply remap and invert to accelerometer too", new String[] { "Yes (recommended)", "No" },
                Settings.accelFollows ? 0 : 1, v -> {
            Settings.accelFollows = v == 0;
            Settings.save();
        });
        root.addView(text("Takes effect immediately. If aiming feels too slow, raise it; if an axis moves the wrong way, invert it.", 11f));

        // ---- Touchpad ----
        root.addView(header("Touchpad"));
        addCheck(root, "Invert touchpad X (left/right)", Settings.touchInvX, on -> Settings.touchInvX = on);
        addCheck(root, "Invert touchpad Y (up/down)", Settings.touchInvY, on -> Settings.touchInvY = on);
        addCheck(root, "Swap touchpad X and Y (applied before invert)", Settings.touchSwap, on -> Settings.touchSwap = on);
        addSpinner(root, "Layout", Settings.TOUCH_NAMES, Settings.touchMode, v -> {
            Settings.touchMode = v;
            Settings.save();
            pad.postInvalidate();
        });

        // ---- Extra buttons ----
        root.addView(header("Extra buttons (L4 / R4 / L5 / R5)"));
        root.addView(text("The Edge's own back and Fn buttons always count. You can also let another button press them, "
                + "which helps if the controller does not report the Edge buttons.", 11f));
        for (int i = 0; i < 4; i++) {
            final int target = i;
            addSpinner(root, Settings.TARGET_NAMES[i] + " is also pressed by", Settings.SRC_NAMES, Settings.src[i], v -> {
                Settings.src[target] = v;
                Settings.save();
            });
        }

        // ---- Light ----
        root.addView(header("Light"));
        addSlider(root, "Red", 255, Settings.lightR, v -> { Settings.lightR = v; lightChanged(); });
        addSlider(root, "Green", 255, Settings.lightG, v -> { Settings.lightG = v; lightChanged(); });
        addSlider(root, "Blue", 255, Settings.lightB, v -> { Settings.lightB = v; lightChanged(); });
        addSlider(root, "Brightness %", 100, Settings.brightness, v -> { Settings.brightness = v; lightChanged(); });
        addSpinner(root, "Player LEDs", Settings.PLAYER_NAMES, Settings.player, v -> { Settings.player = v; lightChanged(); });

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
        NativeBridge.applyForce();
        NativeBridge.applyOutput(); // apply the saved light settings
        checkUpdate();
        ui.post(usbRefresh);
    }

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

    private void lightChanged() {
        NativeBridge.applyOutput();
    }

    // ---------- USB diagnostics ----------

    private final Runnable usbRefresh = new Runnable() {
        @Override public void run() {
            usbView.setText(usbInfo());
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

    // ---------- tests ----------

    private void rumbleTest() {
        NativeBridge.setRumble(220, 220);
        ui.postDelayed(() -> NativeBridge.setRumble(0, 0), 700);
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

    @Override protected void onPause() {
        super.onPause();
        keepControllerClaimed();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        keepControllerClaimed();
    }

    private void keepControllerClaimed() {
        if (mHIDDeviceManager != null) mHIDDeviceManager.setFrozen(false);
    }

    // ---------- NativeBridge.Listener ----------

    @Override public void onFrame(float[] f, int slot) {
        if (slot != 0) return; // the test screen shows player 1
        pad.update(f);
        long now = System.nanoTime();
        if (now - lastSummary > 300_000_000L) {
            lastSummary = now;
            ui.post(() -> summaryView.setText(pad.summary()));
        }
    }

    @Override public void onStatus(final String s) {
        ui.post(() -> {
            statusView.setText("SDL3BridgeDroidDeck\n" + s);
            if (s.startsWith("Controller disconnected") || s.startsWith("Waiting")) pad.setDisconnected();
        });
    }
}
