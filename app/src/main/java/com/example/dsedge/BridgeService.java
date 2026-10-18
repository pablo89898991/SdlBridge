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
 * rumble / lightbar requests back. Wire protocol is identical to the first (USB) bridge.
 */
public class BridgeService extends Service implements NativeBridge.Listener {
    static final int MSG_REGISTER = 1;
    static final int MSG_UNREGISTER = 2;
    static final int MSG_OUTPUT = 3;
    static final int MSG_FRAME = 10;
    static final int MSG_STATUS = 11;

    private static final float RAD_TO_DEG = 57.29578f;
    private static final float G = 9.80665f;

    private final CopyOnWriteArrayList<Messenger> clients = new CopyOnWriteArrayList<>();
    private int rumbleStrong, rumbleWeak, lr, lg, lb = 255;

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
        if (b.containsKey("rl")) rumbleStrong = clamp(b.getInt("rl"));
        if (b.containsKey("rr")) rumbleWeak = clamp(b.getInt("rr"));
        if (b.containsKey("r")) lr = clamp(b.getInt("r"));
        if (b.containsKey("g")) lg = clamp(b.getInt("g"));
        if (b.containsKey("b")) lb = clamp(b.getInt("b"));
        try {
            NativeBridge.nativeSetOutput(rumbleStrong, rumbleWeak, lr, lg, lb);
        } catch (UnsatisfiedLinkError e) {
            // The bridge screen has not been opened yet, so the native library is not loaded.
        }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    // ---------- controller -> clients ----------

    @Override public void onFrame(float[] f) {
        if (clients.isEmpty() || f.length < NativeBridge.FRAME_LEN) return;
        Bundle b = new Bundle();
        b.putByteArray("raw", new byte[0]);
        b.putLong("t", SystemClock.elapsedRealtimeNanos());
        b.putInt("mask", (int) f[0]);
        b.putInt("lx", (int) f[1]); b.putInt("ly", (int) f[2]);
        b.putInt("rx", (int) f[3]); b.putInt("ry", (int) f[4]);
        b.putInt("l2", (int) f[5]); b.putInt("r2", (int) f[6]);
        b.putFloatArray("gyro", new float[]{f[7] * RAD_TO_DEG, f[8] * RAD_TO_DEG, f[9] * RAD_TO_DEG}); // deg/s
        b.putFloatArray("accel", new float[]{f[10] / G, f[11] / G, f[12] / G});                         // g
        List<Integer> t = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            if (f[13 + 3 * i] > 0.5f) { t.add(i + 1); t.add((int) f[14 + 3 * i]); t.add((int) f[15 + 3 * i]); }
        }
        int[] touch = new int[t.size()];
        for (int i = 0; i < touch.length; i++) touch[i] = t.get(i);
        b.putIntArray("touch", touch);
        int state = (int) f[20];
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
