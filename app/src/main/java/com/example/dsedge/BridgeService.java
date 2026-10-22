package com.example.dsedge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Streams controller frames (from the SDL3 loop) to bound clients such as DroidDeck, and passes their
 * rumble / lightbar requests back. Same wire protocol as the first bridge, plus decoupled touchpad data.
 */
public class BridgeService extends Service implements NativeBridge.Listener {
    static final int MSG_REGISTER = 1;
    static final int MSG_UNREGISTER = 2;
    static final int MSG_OUTPUT = 3;
    static final int MSG_FRAME = 10;
    static final int MSG_STATUS = 11;
    static final int MSG_PAD_GONE = 12;

    private static final float RAD_TO_DEG = 57.29578f;
    private static final float G = 9.80665f;
    private static final float PAD_W = 1920f, PAD_H = 1080f;

    private final CopyOnWriteArrayList<Messenger> clients = new CopyOnWriteArrayList<>();
    static volatile int clientCount;   // how many clients (DroidDeck) are bound + registered

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_REGISTER:
                    if (m.replyTo != null) { clients.add(m.replyTo); clientCount = clients.size(); sendStatus(m.replyTo, NativeBridge.lastStatus); }
                    break;
                case MSG_UNREGISTER:
                    if (m.replyTo != null) { clients.remove(m.replyTo); clientCount = clients.size(); }
                    break;
                case MSG_OUTPUT:
                    mergeOutput(m.getData());
                    break;
            }
        }
    };
    private final Messenger messenger = new Messenger(handler);

    // ---- floating bubble ----
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private int bubbleSize;
    private View removeZone;
    private boolean longPressFired;
    private Runnable bubbleLongPress;
    private volatile boolean connectedState;

    @Override public void onCreate() {
        super.onCreate();
        Settings.init(this);
        NativeBridge.listeners.add(this);
    }

    @Override public IBinder onBind(Intent i) { return messenger.getBinder(); }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("bridge", "Controller bridge", NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, "bridge")
                    .setContentTitle("SDL3 controller bridge running")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, n);
        } catch (RuntimeException e) {
            // Without a foreground notification the bridge may be stopped in the background; keep running anyway.
        }
        if (i != null && "BUBBLE".equals(i.getAction())) {
            if (i.getBooleanExtra("on", false)) showBubble(); else hideBubble();
        } else if (Settings.bubble) {
            showBubble();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        NativeBridge.listeners.remove(this);
        hideBubble();
        super.onDestroy();
    }

    // ---------- floating bubble (chat-head) ----------
    //   tap           -> toggle the SDL3 forced driver (green = on, red = off)
    //   hold 3s       -> open the bridge app
    //   drag to the bottom remove zone -> dismiss the bubble

    @SuppressWarnings("ClickableViewAccessibility")
    private void showBubble() {
        if (bubble != null) return;
        if (!android.provider.Settings.canDrawOverlays(this)) return; // no permission yet
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        bubbleSize = dp(52);
        TextView b = new TextView(this);
        b.setText("SDL3");
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(12f);
        b.setWidth(bubbleSize);
        b.setHeight(bubbleSize);
        b.setBackground(bubbleBg(Settings.driverEnabled));
        b.setElevation(dp(4));

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Settings.bubbleX;
        lp.y = Settings.bubbleY;

        final int slop = dp(10);
        b.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = bubbleLp.x; startY = bubbleLp.y; moved = false; longPressFired = false;
                        bubbleLongPress = () -> { longPressFired = true; openBridgeApp(); };
                        main.postDelayed(bubbleLongPress, 3000);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int nx = startX + (int) (e.getRawX() - downX);
                        int ny = startY + (int) (e.getRawY() - downY);
                        if (!moved && Math.abs(nx - startX) + Math.abs(ny - startY) > slop) {
                            moved = true;
                            if (bubbleLongPress != null) main.removeCallbacks(bubbleLongPress);
                            showRemoveZone();
                        }
                        bubbleLp.x = nx; bubbleLp.y = ny;
                        try { wm.updateViewLayout(bubble, bubbleLp); } catch (RuntimeException ignored) { }
                        if (moved) highlightRemoveZone(overRemoveZone());
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        if (bubbleLongPress != null) main.removeCallbacks(bubbleLongPress);
                        boolean removed = false;
                        if (moved && overRemoveZone()) { removeBubbleByUser(); removed = true; }
                        hideRemoveZone();
                        if (!removed) {
                            Settings.bubbleX = bubbleLp.x; Settings.bubbleY = bubbleLp.y; Settings.save();
                            if (!moved && !longPressFired) toggleDriver();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(b, lp);
            bubble = b;
            bubbleLp = lp;
        } catch (RuntimeException e) {
            bubble = null;
        }
    }

    private void openBridgeApp() {
        try {
            startActivity(new Intent(BridgeService.this, BridgeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    /** Tap action: flip the SDL3 forced driver and recolor the bubble. */
    private void toggleDriver() {
        Settings.driverEnabled = !Settings.driverEnabled;
        if (Settings.driverEnabled) Settings.forceUsb = true;   // on = forced SDL3 USB
        Settings.save();
        NativeBridge.applyDriver();
        try { BridgeActivity.setHidFrozen(!Settings.driverEnabled); } catch (Throwable ignored) { }
        updateBubble();
    }

    private void removeBubbleByUser() {
        Settings.bubble = false;
        Settings.save();
        hideBubble();
    }

    private void hideBubble() {
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (RuntimeException ignored) { }
        }
        bubble = null;
        hideRemoveZone();
    }

    // ---- remove zone (the drop target at the bottom) ----

    private void showRemoveZone() {
        if (removeZone != null || wm == null) return;
        int zs = dp(64);
        TextView z = new TextView(this);
        z.setText("✕");
        z.setTextColor(Color.WHITE);
        z.setGravity(Gravity.CENTER);
        z.setTextSize(22f);
        z.setWidth(zs);
        z.setHeight(zs);
        z.setBackground(removeZoneBg(false));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                bubbleLp.type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(48);
        try { wm.addView(z, lp); removeZone = z; } catch (RuntimeException e) { removeZone = null; }
    }

    private void hideRemoveZone() {
        if (removeZone != null && wm != null) {
            try { wm.removeView(removeZone); } catch (RuntimeException ignored) { }
        }
        removeZone = null;
    }

    private void highlightRemoveZone(boolean over) {
        if (removeZone instanceof TextView) {
            removeZone.setBackground(removeZoneBg(over));
            ((TextView) removeZone).setScaleX(over ? 1.25f : 1f);
            ((TextView) removeZone).setScaleY(over ? 1.25f : 1f);
        }
    }

    private GradientDrawable removeZoneBg(boolean over) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(over ? 0xFFC62828 : 0xCC424242);
        g.setStroke(dp(2), 0xCCFFFFFF);
        return g;
    }

    /** True when the bubble's centre is over the bottom remove zone. */
    private boolean overRemoveZone() {
        if (removeZone == null) return false;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float bx = bubbleLp.x + bubbleSize / 2f;
        float by = bubbleLp.y + bubbleSize / 2f;
        float zx = dm.widthPixels / 2f;
        float zy = dm.heightPixels - dp(48) - dp(32);   // zone centre (bottom margin + half height)
        float dx = bx - zx, dy = by - zy;
        return Math.sqrt(dx * dx + dy * dy) < dp(80);
    }

    private GradientDrawable bubbleBg(boolean driverOn) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(driverOn ? 0xFF2E7D32 : 0xFFC62828);   // green = SDL3 driver on, red = off
        g.setStroke(dp(2), 0xCCFFFFFF);
        return g;
    }

    private void updateBubble() {
        if (bubble == null) return;
        main.post(() -> { if (bubble != null) bubble.setBackground(bubbleBg(Settings.driverEnabled)); });
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ---------- client -> controller ----------

    /** Applies the touchpad swap / invert settings to one trackpad's [down, x, y]. */
    private static void orient(float[] p, int player) {
        if (Settings.touchSwap[player]) { float t = p[1]; p[1] = p[2]; p[2] = t; }
        if (Settings.touchInvX[player]) p[1] = -p[1];
        if (Settings.touchInvY[player]) p[2] = -p[2];
    }

    private void mergeOutput(Bundle b) {
        if (b == null) return;
        if (b.containsKey("rl") || b.containsKey("rr")) {
            NativeBridge.setRumble(b.getInt("slot", 0), clamp(b.getInt("rl")), clamp(b.getInt("rr")));
        }
        // A client that sets the lightbar overrides the saved colour until the bridge screen changes it again.
        if (b.containsKey("r") || b.containsKey("g") || b.containsKey("b")) {
            int ps = Math.max(0, Math.min(Settings.PLAYERS - 1, b.getInt("slot", 0)));
            if (b.containsKey("r")) Settings.lightR[ps] = clamp(b.getInt("r"));
            if (b.containsKey("g")) Settings.lightG[ps] = clamp(b.getInt("g"));
            if (b.containsKey("b")) Settings.lightB[ps] = clamp(b.getInt("b"));
            NativeBridge.applyOutput(ps);
        }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    // ---------- controller -> clients ----------

    @Override public void onFrame(float[] f, int slot) {
        if (clients.isEmpty() || f.length < NativeBridge.FRAME_LEN) return;
        Bundle b = new Bundle();
        b.putByteArray("raw", new byte[0]);
        b.putLong("t", SystemClock.elapsedRealtimeNanos());
        int rawMask = (int) f[0];
        int mask2 = f.length >= 22 ? (int) f[21] : 0;          // extra buttons (MISC2..6, e.g. Flydigi C/Z)
        int mask = Settings.applyGripRemap(rawMask, mask2, slot);  // user button -> L4/R4/L5/R5
        b.putInt("mask", mask);
        b.putInt("lx", (int) f[1]); b.putInt("ly", (int) f[2]);
        b.putInt("rx", (int) f[3]); b.putInt("ry", (int) f[4]);
        b.putInt("l2", (int) f[5]); b.putInt("r2", (int) f[6]);
        float gk = Settings.gyroGain[slot] / 100f;
        int inv = Settings.gyroInvert[slot];
        float dz = Settings.gyroDeadzone[slot];
        float[] bias = Settings.gyroBias[slot];
        int[] gmap = Settings.gyroMap[slot];
        boolean accFollows = Settings.accelFollows[slot];
        float[] gy = {f[7] * RAD_TO_DEG, f[8] * RAD_TO_DEG, f[9] * RAD_TO_DEG};   // deg/s
        float[] ac = {f[10] / G, f[11] / G, f[12] / G};                          // g
        for (int k = 0; k < 3; k++) {
            gy[k] -= bias[k];                              // remove resting drift (from Calibrate)
            if (Math.abs(gy[k]) < dz) gy[k] = 0f;          // deadzone, in real deg/s (before gain)
        }
        float[] go = new float[3], ao = new float[3];
        for (int i = 0; i < 3; i++) {
            float sign = (inv & (1 << i)) != 0 ? -1f : 1f;
            int src = gmap[i];
            go[i] = gy[src] * gk * sign;
            ao[i] = accFollows ? ac[src] * sign : ac[i];
        }
        // Sony family only (DualShock 4 = 3, DualSense = 4, DualSense Edge = 5): their report has ONE
        // full-width multitouch pad and remaps the IMU axes (X, Z, -Y) vs the Deck IMU. The Steam Deck (0)
        // and Steam Controller (1) have two trackpads and use the Deck IMU convention, so they are left as-is.
        int presentType = Settings.PRESENT_PAD_TYPE[Settings.presentAs];
        boolean sony = Settings.isSonyPadType(presentType);
        if (sony) {
            float tg = go[1]; go[1] = -go[2]; go[2] = tg;
            float ta = ao[1]; ao[1] = -ao[2]; ao[2] = ta;
        }
        b.putFloatArray("gyro", go);
        b.putFloatArray("accel", ao);


        // Raw fingers (kept for older clients) ...
        List<Integer> t = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            if (f[13 + 3 * i] > 0.5f) { t.add(i + 1); t.add((int) f[14 + 3 * i]); t.add((int) f[15 + 3 * i]); }
        }
        int[] touch = new int[t.size()];
        for (int i = 0; i < touch.length; i++) touch[i] = t.get(i);
        b.putIntArray("touch", touch);

        // ... and the decoupled version: each half is its own trackpad with local -1..1 coordinates.
        float[] padL = new float[3], padR = new float[3];
        int mode = Settings.touchMode[slot];
        // A 2015 Steam Controller has two real trackpads: always split the touchpad into left/right halves.
        if (presentType == Settings.PAD_TYPE_STEAM_2015 && mode != Settings.TOUCH_OFF) mode = Settings.TOUCH_SPLIT;
        // A native Sony pad (DualShock 4, DualSense, DualSense Edge) has ONE full-width multitouch
        // surface, so ignore the Deck split there: keep SDL's finger slots (finger 0 is the primary
        // touch the game tracks) and map both across the whole pad. padL carries finger 0, padR finger 1.
        // Only the Steam Deck pad (type 0) keeps the left/right split, since it has two real trackpads.
        if (mode != Settings.TOUCH_OFF) {
            if (sony) {
                for (int i = 0; i < 2; i++) {
                    if (f[13 + 3 * i] <= 0.5f) continue;
                    float[] p = (i == 0) ? padL : padR;
                    p[0] = 1f; p[1] = f[14 + 3 * i] / PAD_W * 2f - 1f; p[2] = f[15 + 3 * i] / PAD_H * 2f - 1f;
                }
            } else for (int i = 0; i < 2; i++) {
                if (f[13 + 3 * i] <= 0.5f) continue;
                float x = f[14 + 3 * i], y = f[15 + 3 * i];
                float ny = y / PAD_H * 2f - 1f;
                if (mode == Settings.TOUCH_SINGLE) {
                    if (padR[0] == 0f) { padR[0] = 1f; padR[1] = x / PAD_W * 2f - 1f; padR[2] = ny; }
                } else if (x < PAD_W / 2f) {
                    if (padL[0] == 0f) { padL[0] = 1f; padL[1] = x / (PAD_W / 2f) * 2f - 1f; padL[2] = ny; }
                } else {
                    if (padR[0] == 0f) { padR[0] = 1f; padR[1] = (x - PAD_W / 2f) / (PAD_W / 2f) * 2f - 1f; padR[2] = ny; }
                }
            }
        }
        int click = -1; // -1 none, 0 left trackpad, 1 right trackpad
        if (((mask >> 13) & 1) != 0 && mode != Settings.TOUCH_OFF) {
            click = (mode == Settings.TOUCH_SPLIT && padL[0] > 0f && padR[0] == 0f) ? 0 : 1;
        }
        // Button -> trackpad touch / click remaps (targets 4..7)
        int[] rm = Settings.remap[slot];
        if (Settings.srcDown(rawMask, mask2, rm[6]) && padL[0] == 0f) { padL[0] = 1f; padL[1] = 0f; padL[2] = 0f; }
        if (Settings.srcDown(rawMask, mask2, rm[7]) && padR[0] == 0f) { padR[0] = 1f; padR[1] = 0f; padR[2] = 0f; }
        if (Settings.srcDown(rawMask, mask2, rm[4])) { if (padL[0] == 0f) padL[0] = 1f; click = 0; }
        if (Settings.srcDown(rawMask, mask2, rm[5])) { if (padR[0] == 0f) padR[0] = 1f; click = 1; }
        orient(padL, slot);
        orient(padR, slot);
        b.putFloatArray("padL", padL);
        b.putFloatArray("padR", padR);
        b.putInt("padClick", click);

        int state = (int) f[20];
        b.putInt("slot", slot);
        b.putInt("ptype", Settings.PRESENT_PAD_TYPE[Settings.presentAs]);
        b.putInt("vid", NativeBridge.vids[slot]);
        b.putInt("pid", NativeBridge.pids[slot]);
        b.putInt("battery", Math.max(0, (int) f[19]));
        b.putString("charge", state == 3 ? "charging" : state == 4 ? "full" : state == 1 ? "discharging" : "unknown");
        for (Messenger c : clients) {
            try {
                Message m = Message.obtain(null, MSG_FRAME);
                m.setData(b);
                c.send(m);
            } catch (RemoteException e) { clients.remove(c); clientCount = clients.size(); }
        }
    }

    @Override public void onPadGone(int slot) {
        for (Messenger c : clients) {
            try {
                Bundle b = new Bundle();
                b.putInt("slot", slot);
                Message m = Message.obtain(null, MSG_PAD_GONE);
                m.setData(b);
                c.send(m);
            } catch (RemoteException e) { clients.remove(c); }
        }
    }

    @Override public void onStatus(String s) {
        connectedState = s != null && s.startsWith("Connected");
        updateBubble();
        for (Messenger c : clients) sendStatus(c, s);
    }

    private void sendStatus(Messenger c, String s) {
        try {
            Bundle b = new Bundle();
            b.putString("text", s);
            Message m = Message.obtain(null, MSG_STATUS);
            m.setData(b);
            c.send(m);
        } catch (RemoteException e) { clients.remove(c); }
    }
}
