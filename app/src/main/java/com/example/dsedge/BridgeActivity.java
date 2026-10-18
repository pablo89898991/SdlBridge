package com.example.dsedge;

import android.content.Intent;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

/**
 * Hosts SDL3 (which owns USB/HID controller access on Android), shows a live controller test screen,
 * and keeps the controller claimed while another app (DroidDeck) is in front.
 */
public class BridgeActivity extends SDLActivity implements NativeBridge.Listener {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusView, summaryView, usbView;
    private Button updateButton;
    private ControllerView pad;
    private String updateUrl;
    private long lastSummary;
    private int lr = 0, lg = 0, lb = 255, colorStep = 0;

    @Override protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UpdateChecker.BuildInfo info = UpdateChecker.readBuildInfo(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(16, 24, 16, 16);

        statusView = text("DualSense Bridge (SDL3)  " + info.tag + "  SDL " + info.sdl + "\n" + NativeBridge.lastStatus, 13f);
        usbView = text("USB: checking...", 12f);
        summaryView = text("Press every button and move everything to test it.", 12f);
        pad = new ControllerView(this);

        updateButton = button("Checking for updates...", v -> { if (updateUrl != null) openUrl(updateUrl); });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Rumble", v -> rumbleTest()), weight());
        row.addView(button("Light", v -> nextColor()), weight());
        row.addView(button("Reset", v -> { pad.resetTested(); summaryView.setText(pad.summary()); }), weight());
        row.addView(button("Update", v -> checkUpdate()), weight());

        root.addView(statusView);
        root.addView(usbView);
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(summaryView);
        root.addView(row);
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
        checkUpdate();
        ui.post(usbRefresh);
    }

    private TextView text(String s, float size) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(size);
        t.setGravity(Gravity.START);
        t.setPadding(0, 6, 0, 6);
        t.setText(s);
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

    private void output(int strong, int weak) {
        try {
            NativeBridge.nativeSetOutput(strong, weak, lr, lg, lb);
        } catch (UnsatisfiedLinkError ignored) { }
    }

    private void rumbleTest() {
        output(220, 220);
        ui.postDelayed(() -> output(0, 0), 700);
    }

    private void nextColor() {
        int[][] colors = { {255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 255}, {255, 0, 255} };
        int[] c = colors[colorStep++ % colors.length];
        lr = c[0]; lg = c[1]; lb = c[2];
        output(0, 0);
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

    @Override public void onFrame(float[] f) {
        pad.update(f);
        long now = System.nanoTime();
        if (now - lastSummary > 300_000_000L) {
            lastSummary = now;
            ui.post(() -> summaryView.setText(pad.summary()));
        }
    }

    @Override public void onStatus(final String s) {
        ui.post(() -> {
            statusView.setText("DualSense Bridge (SDL3)\n" + s);
            if (s.startsWith("Controller disconnected") || s.startsWith("Waiting")) pad.setDisconnected();
        });
    }
}
