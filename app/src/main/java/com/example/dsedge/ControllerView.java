package com.example.dsedge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Live gamepad diagram. Orange = held right now, green = has been pressed at least once (works),
 * grey = not tested yet. Fed with the float[] frames from the native SDL loop.
 */
public class ControllerView extends View {
    private static final int ORANGE = 0xFFFF9800, GREEN = 0xFF2E7D32, GRAY = 0xFF37474F, LINE = 0xFF90A4AE;
    private static final int BUTTON_BITS = 23; // bits 0..22 of the frame mask

    // tested-flag bits (not button bits)
    private static final int T_LS = 1, T_RS = 2, T_L2 = 4, T_R2 = 8, T_GYRO = 16, T_ACCEL = 32, T_TOUCH = 64;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private volatile float[] frame = new float[NativeBridge.FRAME_LEN];
    private volatile int mask;
    private volatile int testedButtons;
    private volatile int testedFlags;
    private volatile boolean connected;
    private long lastInvalidate;

    public ControllerView(Context c) {
        super(c);
        setBackgroundColor(0xFF111111);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(0.5f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFECEFF1);
        frame[1] = frame[2] = frame[3] = frame[4] = 128f;
    }

    /** Called from any thread with each native frame. */
    public void update(float[] f) {
        if (f == null || f.length < NativeBridge.FRAME_LEN) return;
        float[] c = f.clone();
        frame = c;
        connected = true;
        int m = (int) c[0];
        mask = m;
        testedButtons |= m;
        int t = testedFlags;
        if (Math.abs(c[1] - 128f) > 60f || Math.abs(c[2] - 128f) > 60f) t |= T_LS;
        if (Math.abs(c[3] - 128f) > 60f || Math.abs(c[4] - 128f) > 60f) t |= T_RS;
        if (c[5] > 100f) t |= T_L2;
        if (c[6] > 100f) t |= T_R2;
        if (Math.abs(c[7]) + Math.abs(c[8]) + Math.abs(c[9]) > 0.6f) t |= T_GYRO;
        if (Math.abs(c[10]) + Math.abs(c[11]) + Math.abs(c[12]) > 3f) t |= T_ACCEL;
        if (c[13] > 0.5f || c[16] > 0.5f) t |= T_TOUCH;
        testedFlags = t;
        long now = System.nanoTime();
        if (now - lastInvalidate > 16_000_000L) {
            lastInvalidate = now;
            postInvalidate();
        }
    }

    public void setDisconnected() {
        connected = false;
        mask = 0;
        postInvalidate();
    }

    public void resetTested() {
        testedButtons = 0;
        testedFlags = 0;
        postInvalidate();
    }

    /** One-line summary of what has been verified so far. */
    public String summary() {
        int t = testedFlags;
        return "Tested: buttons " + Integer.bitCount(testedButtons & ((1 << BUTTON_BITS) - 1)) + "/" + BUTTON_BITS
                + "  sticks " + mark((t & T_LS) != 0 && (t & T_RS) != 0)
                + "  triggers " + mark((t & T_L2) != 0 && (t & T_R2) != 0)
                + "  gyro " + mark((t & T_GYRO) != 0)
                + "  accel " + mark((t & T_ACCEL) != 0)
                + "  touchpad " + mark((t & T_TOUCH) != 0);
    }

    private static String mark(boolean ok) { return ok ? "OK" : "-"; }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) (width * 0.72f));
    }

    // ---------------- drawing (100 x 72 unit canvas) ----------------

    private int col(int bit) {
        if (((mask >> bit) & 1) != 0) return ORANGE;
        if (((testedButtons >> bit) & 1) != 0) return GREEN;
        return GRAY;
    }

    private void label(Canvas c, String s, float x, float y, float size) {
        text.setTextSize(size);
        c.drawText(s, x, y + size * 0.35f, text);
    }

    private void rbtn(Canvas c, float l, float t, float r, float b, int bit, String s) {
        rect.set(l, t, r, b);
        fill.setColor(col(bit));
        c.drawRoundRect(rect, 1f, 1f, fill);
        stroke.setColor(LINE);
        c.drawRoundRect(rect, 1f, 1f, stroke);
        label(c, s, (l + r) / 2f, (t + b) / 2f, 2.2f);
    }

    private void cbtn(Canvas c, float cx, float cy, float rad, int bit, String s) {
        fill.setColor(col(bit));
        c.drawCircle(cx, cy, rad, fill);
        stroke.setColor(LINE);
        c.drawCircle(cx, cy, rad, stroke);
        label(c, s, cx, cy, 2.4f);
    }

    private void meter(Canvas c, float l, float t, float r, float b, float value01, boolean tested) {
        rect.set(l, t, r, b);
        fill.setColor(0xFF263238);
        c.drawRect(rect, fill);
        rect.set(l, t, l + (r - l) * Math.max(0f, Math.min(1f, value01)), b);
        fill.setColor(value01 > 0.02f ? ORANGE : (tested ? GREEN : GRAY));
        c.drawRect(rect, fill);
        rect.set(l, t, r, b);
        stroke.setColor(LINE);
        c.drawRect(rect, stroke);
    }

    private void stick(Canvas c, float cx, float cy, float x, float y, int clickBit, boolean tested) {
        stroke.setColor(((mask >> clickBit) & 1) != 0 ? ORANGE : (tested ? 0xFF66BB6A : LINE));
        stroke.setStrokeWidth(1f);
        c.drawCircle(cx, cy, 9f, stroke);
        stroke.setStrokeWidth(0.5f);
        fill.setColor(((mask >> clickBit) & 1) != 0 ? ORANGE : (tested ? GREEN : GRAY));
        float dx = (x - 128f) / 128f * 6f, dy = (y - 128f) / 128f * 6f;
        c.drawCircle(cx + dx, cy + dy, 3.6f, fill);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float u = getWidth() / 100f;
        c.save();
        c.scale(u, u);
        float[] f = frame;
        int t = testedFlags;

        meter(c, 6, 1, 30, 4, f[5] / 255f, (t & T_L2) != 0);
        meter(c, 70, 1, 94, 4, f[6] / 255f, (t & T_R2) != 0);
        rbtn(c, 6, 5.5f, 30, 10, 4, "L1");
        rbtn(c, 70, 5.5f, 94, 10, 5, "R1");

        // touchpad with finger dots
        rect.set(36, 12, 64, 34);
        fill.setColor(((mask >> 13) & 1) != 0 ? ORANGE : (((testedButtons >> 13) & 1) != 0 ? GREEN : 0xFF263238));
        c.drawRoundRect(rect, 2f, 2f, fill);
        stroke.setColor(LINE);
        c.drawRoundRect(rect, 2f, 2f, stroke);
        if (Settings.touchMode == Settings.TOUCH_SPLIT) {
            stroke.setColor(LINE);
            c.drawLine(50, 12, 50, 34, stroke);
            label(c, "L pad", 43, 32.5f, 2f);
            label(c, "R pad", 57, 32.5f, 2f);
        } else if (Settings.touchMode == Settings.TOUCH_OFF) {
            label(c, "touchpad off", 50, 32.5f, 2f);
        } else {
            label(c, "R pad (whole)", 50, 32.5f, 2f);
        }
        for (int i = 0; i < 2; i++) {
            if (f[13 + 3 * i] > 0.5f) {
                fill.setColor(i == 0 ? 0xFFFFFFFF : 0xFF4FC3F7);
                float px = f[14 + 3 * i] / 1919f, py = f[15 + 3 * i] / 1079f;
                if (Settings.touchInvX) px = 1f - px; // dots show what DroidDeck receives
                if (Settings.touchInvY) py = 1f - py;
                c.drawCircle(36 + px * 28f, 12 + py * 22f, 1.8f, fill);
            }
        }

        rbtn(c, 27, 14, 34, 18, 8, "Create");
        rbtn(c, 66, 14, 73, 18, 9, "Opt");
        rbtn(c, 46, 36, 54, 39.5f, 14, "Mute");
        cbtn(c, 50, 44, 2.8f, 12, "PS");

        // d-pad
        rbtn(c, 18, 20, 22, 24, 15, "");
        rbtn(c, 18, 32, 22, 36, 16, "");
        rbtn(c, 14, 26, 18, 30, 17, "");
        rbtn(c, 22, 26, 26, 30, 18, "");
        label(c, "D-pad", 20, 40, 2.2f);

        // face buttons
        cbtn(c, 80, 21, 3.2f, 3, "T");
        cbtn(c, 80, 35, 3.2f, 0, "X");
        cbtn(c, 73, 28, 3.2f, 2, "S");
        cbtn(c, 87, 28, 3.2f, 1, "O");

        stick(c, 34, 53, f[1], f[2], 10, (t & T_LS) != 0);
        stick(c, 66, 53, f[3], f[4], 11, (t & T_RS) != 0);
        label(c, "L3", 34, 64.5f, 2.2f);
        label(c, "R3", 66, 64.5f, 2.2f);

        // Edge extras
        rbtn(c, 1.5f, 40, 6.5f, 56, 19, "L4");
        rbtn(c, 93.5f, 40, 98.5f, 56, 20, "R4");
        rbtn(c, 9, 60, 21, 64.5f, 21, "L Fn");
        rbtn(c, 79, 60, 91, 64.5f, 22, "R Fn");

        String s1 = connected
                ? String.format(java.util.Locale.ROOT, "Gyro dps  %.0f  %.0f  %.0f", f[7] * 57.29578f, f[8] * 57.29578f, f[9] * 57.29578f)
                : "No controller";
        String s2 = connected
                ? String.format(java.util.Locale.ROOT, "Accel g  %.2f  %.2f  %.2f    Battery %d%%",
                        f[10] / 9.80665f, f[11] / 9.80665f, f[12] / 9.80665f, (int) Math.max(0f, f[19]))
                : "";
        label(c, s1, 50, 68f, 2.6f);
        label(c, s2, 50, 71f, 2.6f);

        c.restore();
    }
}
