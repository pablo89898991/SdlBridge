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
