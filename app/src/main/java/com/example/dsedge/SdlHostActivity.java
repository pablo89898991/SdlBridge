package com.example.dsedge;

import android.os.Bundle;

import org.libsdl.app.SDLActivity;

/**
 * Fallback host for SDL3: the old way, SDL running inside an (invisible) SDLActivity kept in the background.
 * Used only when SDL3 could not run in the background service on this phone (SdlEngine decides, and says why).
 */
public class SdlHostActivity extends SDLActivity {
    static volatile boolean running;

    @Override protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override protected void onCreate(Bundle state) {
        running = true;
        NativeBridge.appContext = getApplicationContext();
        Settings.init(this);
        super.onCreate(state);
    }

    @Override protected void onResume() {
        super.onResume();
        keepControllerClaimed();
        moveTaskToBack(true);   // never take the screen: it only hosts SDL3
    }

    @Override protected void onPause() {
        super.onPause();
        keepControllerClaimed();   // SDL releases the USB controller on pause; keep it while DroidDeck is in front
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        keepControllerClaimed();
    }

    @Override protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private static void keepControllerClaimed() {
        if (mHIDDeviceManager != null) mHIDDeviceManager.setFrozen(!Settings.driverEnabled);
    }

    static void setHidFrozen(boolean frozen) {
        if (mHIDDeviceManager != null) mHIDDeviceManager.setFrozen(frozen);
    }
}
