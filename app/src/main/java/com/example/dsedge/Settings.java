package com.example.dsedge;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings, shared by the screen and the background service (same process). */
final class Settings {
    static final int TOUCH_SPLIT = 0, TOUCH_SINGLE = 1, TOUCH_OFF = 2;
    static final String[] TOUCH_NAMES = {
        "Split (decoupled): left half = left trackpad, right half = right trackpad",
        "Single: whole touchpad = right trackpad",
        "Off"
    };

    /** Virtual buttons DroidDeck sees as Steam Deck back grips. */
    static final String[] TARGET_NAMES = { "L4", "R4", "L5", "R5" };
    static final int[] TARGET_BITS = { 19, 20, 21, 22 };
    /** What can press them (besides the real Edge buttons, which always work when the controller reports them). */
    static final String[] SRC_NAMES = {
        "Edge button only (default)", "Mic mute button", "Touchpad click", "Create button",
        "Options button", "L3 (left stick click)", "R3 (right stick click)"
    };
    static final int[] SRC_BITS = { -1, 14, 13, 8, 9, 10, 11 };

    static final String[] PLAYER_NAMES = { "Off", "Player 1", "Player 2", "Player 3", "Player 4" };

    static volatile int touchMode = TOUCH_SPLIT;
    static final int[] src = new int[4];
    static volatile boolean forceUsb = true;
    static volatile boolean driverEnabled = true;   // bubble tap toggles this: on = forced SDL3 USB, off = driver released
    static volatile boolean separateController = false;  // present to DroidDeck as its own controller, not the Steam Deck pad
    static volatile int pollMs = 2;                      // SDL loop period (ms): 4=250Hz, 2=500Hz, 1=1000Hz
    static final String[] RATE_NAMES = { "250 Hz (low CPU)", "500 Hz (recommended)", "1000 Hz (max)" };
    static final int[] RATE_MS = { 4, 2, 1 };
    static volatile boolean bubble = false;          // floating draw-over bubble
    static volatile int bubbleX = 24, bubbleY = 160;  // last bubble position (px)
    static volatile boolean touchInvX, touchInvY, touchSwap;
    /** Gyro speed sent to DroidDeck, in percent (100 = 1:1), and which axes are flipped (bit 0 = X, 1 = Y, 2 = Z). */
    static volatile int gyroGain = 100, gyroInvert = 0;
    static volatile int gyroDeadzone = 0;           // deg/s; |gyro| below this is treated as zero (anti-drift)
    static final float[] gyroBias = new float[3];    // resting drift per controller axis (deg/s), from Calibrate
    /** Output axis i is taken from input axis gyroMap[i] (0 = X, 1 = Y, 2 = Z). */
    static final int[] gyroMap = { 0, 1, 2 };
    static final String[] AXIS_NAMES = { "Controller X (pitch)", "Controller Y (yaw)", "Controller Z (roll)" };
    static volatile boolean accelFollows = true;
    static final String[] GYRO_INV_NAMES = { "None", "X", "Y", "Z", "X + Y", "X + Z", "Y + Z", "X + Y + Z" };
    static volatile int lightR = 0, lightG = 0, lightB = 255, brightness = 100, player = 0;

    private static SharedPreferences prefs;

    private Settings() {}

    static synchronized void init(Context c) {
        if (prefs != null) return;
        prefs = c.getApplicationContext().getSharedPreferences("bridge", Context.MODE_PRIVATE);
        touchMode = Math.max(0, Math.min(2, prefs.getInt("touchMode", TOUCH_SPLIT)));
        for (int i = 0; i < 4; i++) {
            src[i] = Math.max(0, Math.min(SRC_BITS.length - 1, prefs.getInt("src" + i, 0)));
        }
        forceUsb = prefs.getBoolean("forceUsb", true);
        driverEnabled = prefs.getBoolean("driverEnabled", true);
        separateController = prefs.getBoolean("separateController", false);
        pollMs = Math.max(1, Math.min(16, prefs.getInt("pollMs", 2)));
        bubble = prefs.getBoolean("bubble", false);
        bubbleX = prefs.getInt("bubbleX", 24);
        bubbleY = prefs.getInt("bubbleY", 160);
        touchInvX = prefs.getBoolean("touchInvX", false);
        touchInvY = prefs.getBoolean("touchInvY", false);
        touchSwap = prefs.getBoolean("touchSwap", false);
        gyroGain = Math.max(1, Math.min(800, prefs.getInt("gyroGain", 100)));
        gyroInvert = Math.max(0, Math.min(7, prefs.getInt("gyroInvert", 0)));
        gyroDeadzone = Math.max(0, Math.min(50, prefs.getInt("gyroDeadzone", 0)));
        gyroBias[0] = prefs.getFloat("gyroBiasX", 0f);
        gyroBias[1] = prefs.getFloat("gyroBiasY", 0f);
        gyroBias[2] = prefs.getFloat("gyroBiasZ", 0f);
        for (int i = 0; i < 3; i++) gyroMap[i] = Math.max(0, Math.min(2, prefs.getInt("gyroMap" + i, i)));
        accelFollows = prefs.getBoolean("accelFollows", true);
        lightR = prefs.getInt("lightR", 0);
        lightG = prefs.getInt("lightG", 0);
        lightB = prefs.getInt("lightB", 255);
        brightness = Math.max(0, Math.min(100, prefs.getInt("brightness", 100)));
        player = Math.max(0, Math.min(4, prefs.getInt("player", 0)));
    }

    static void save() {
        SharedPreferences p = prefs;
        if (p == null) return;
        SharedPreferences.Editor e = p.edit();
        e.putInt("touchMode", touchMode);
        for (int i = 0; i < 4; i++) e.putInt("src" + i, src[i]);
        e.putBoolean("forceUsb", forceUsb);
        e.putBoolean("driverEnabled", driverEnabled);
        e.putBoolean("separateController", separateController);
        e.putInt("pollMs", pollMs);
        e.putBoolean("bubble", bubble).putInt("bubbleX", bubbleX).putInt("bubbleY", bubbleY);
        e.putBoolean("touchInvX", touchInvX).putBoolean("touchInvY", touchInvY).putBoolean("touchSwap", touchSwap);
        for (int i = 0; i < 3; i++) e.putInt("gyroMap" + i, gyroMap[i]);
        e.putInt("gyroDeadzone", gyroDeadzone);
        e.putFloat("gyroBiasX", gyroBias[0]).putFloat("gyroBiasY", gyroBias[1]).putFloat("gyroBiasZ", gyroBias[2]);
        e.putBoolean("accelFollows", accelFollows);
        e.putInt("gyroGain", gyroGain).putInt("gyroInvert", gyroInvert);
        e.putInt("lightR", lightR).putInt("lightG", lightG).putInt("lightB", lightB);
        e.putInt("brightness", brightness).putInt("player", player);
        e.apply();
    }

    /**
     * Adds virtual L4/R4/L5/R5 presses from the chosen source buttons. A source that is assigned
     * to a virtual button stops acting as itself, so one press is not counted twice.
     */
    static int mapMask(int mask) {
        int out = mask;
        int consumed = 0;
        for (int t = 0; t < 4; t++) {
            int bit = SRC_BITS[src[t]];
            if (bit >= 0 && ((mask >> bit) & 1) != 0) {
                out |= 1 << TARGET_BITS[t];
                consumed |= 1 << bit;
            }
        }
        return out & ~consumed;
    }
}
