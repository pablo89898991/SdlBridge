package com.example.dsedge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Receives "controller plugged in" from Android. Accepting Android's prompt gives the bridge USB access to the
 * controller; nothing needs to be shown for that. If the bridge is already running it simply picks the
 * controller up; if not, it is started in the background (its SDL3 loop must run) without coming to the front.
 */
public class UsbAttachActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!BridgeActivity.running) {
            try {
                startActivity(new Intent(this, BridgeActivity.class)
                    .putExtra(BridgeActivity.EXTRA_BACKGROUND, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } catch (RuntimeException ignored) { }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
