package com.example.dsedge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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

    private static final float RAD_TO_DEG = 57.29578f;
    private static final float G = 9.80665f;
    private static final float PAD_W = 1920f, PAD_H = 1080f;

    private final CopyOnWriteArrayList<Messenger> clients = new CopyOnWriteArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_REGISTER:
                    if (m.replyTo != null) { clients.add(m.replyTo); sendStatus(m.replyTo, NativeBridge.lastStatus); }
                    break;
                case MSG_UNREGISTER:
                    if (m.replyTo != null) clients.remove(m.replyTo);
                    break;
                case MSG_OUTPUT:
                    mergeOutput(m.getData());
                    break;
            }
        }
    };
    private final Messenger messenger = new Messenger(handler);

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
                    .setContentTitle("DualSense bridge running")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, n);
        } catch (RuntimeException e) {
            // Without a foreground notification the bridge may be stopped in the background; keep running anyway.
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        NativeBridge.listeners.remove(this);
        super.onDestroy();
    }

    // ---------- client -> controller ----------

    private void mergeOutput(Bundle b) {
        if (b == null) return;
        if (b.containsKey("rl") || b.containsKey("rr")) {
            NativeBridge.setRumble(clamp(b.getInt("rl")), clamp(b.getInt("rr")));
        }
        // A client that sets the lightbar overrides the saved colour until the bridge screen changes it again.
        if (b.containsKey("r") || b.containsKey("g") || b.containsKey("b")) {
            if (b.containsKey("r")) Settings.lightR = clamp(b.getInt("r"));
            if (b.containsKey("g")) Settings.lightG = clamp(b.getInt("g"));
            if (b.containsKey("b")) Settings.lightB = clamp(b.getInt("b"));
            NativeBridge.applyOutput();
        }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    // ---------- controller -> clients ----------

    @Override public void onFrame(float[] f) {
        if (clients.isEmpty() || f.length < NativeBridge.FRAME_LEN) return;
        Bundle b = new Bundle();
        b.putByteArray("raw", new byte[0]);
        b.putLong("t", SystemClock.elapsedRealtimeNanos());
        int mask = (int) f[0]; // already includes the L4/R4/L5/R5 remapping
        b.putInt("mask", mask);
        b.putInt("lx", (int) f[1]); b.putInt("ly", (int) f[2]);
        b.putInt("rx", (int) f[3]); b.putInt("ry", (int) f[4]);
        b.putInt("l2", (int) f[5]); b.putInt("r2", (int) f[6]);
        b.putFloatArray("gyro", new float[]{f[7] * RAD_TO_DEG, f[8] * RAD_TO_DEG, f[9] * RAD_TO_DEG}); // deg/s
        b.putFloatArray("accel", new float[]{f[10] / G, f[11] / G, f[12] / G});                         // g

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
        int mode = Settings.touchMode;
        if (mode != Settings.TOUCH_OFF) {
            for (int i = 0; i < 2; i++) {
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
        b.putFloatArray("padL", padL);
        b.putFloatArray("padR", padR);
        b.putInt("padClick", click);

        int state = (int) f[20];
        b.putInt("vid", NativeBridge.vid);
        b.putInt("pid", NativeBridge.pid);
        b.putInt("battery", Math.max(0, (int) f[19]));
        b.putString("charge", state == 3 ? "charging" : state == 4 ? "full" : state == 1 ? "discharging" : "unknown");
        for (Messenger c : clients) {
            try {
                Message m = Message.obtain(null, MSG_FRAME);
                m.setData(b);
                c.send(m);
            } catch (RemoteException e) { clients.remove(c); }
        }
    }

    @Override public void onStatus(String s) {
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
