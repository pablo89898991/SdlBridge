/*
 * SDL3 controller loop. Reads the first gamepad (DualSense, DualSense Edge, Xbox, Switch Pro, ... anything SDL3
 * supports) and pushes frames to Java (NativeBridge.onFrame). Rumble/lightbar requests come back through
 * NativeBridge.nativeSetOutput.
 */
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <jni.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>

#define FRAME_LEN 21

static JavaVM *g_vm;
static jclass g_cls;
static jmethodID g_frame, g_status;

static _Atomic int g_rl, g_rr, g_r, g_g, g_b = 255;
static _Atomic int g_dirty = 1;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = (*env)->FindClass(env, "com/example/dsedge/NativeBridge");
    if (!c) return JNI_ERR;
    g_cls = (jclass)(*env)->NewGlobalRef(env, c);
    g_frame = (*env)->GetStaticMethodID(env, g_cls, "onFrame", "([F)V");
    g_status = (*env)->GetStaticMethodID(env, g_cls, "onStatus", "(Ljava/lang/String;)V");
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeSetOutput(
    JNIEnv *env, jclass cls, jint rl, jint rr, jint r, jint g, jint b)
{
    (void)env; (void)cls;
    atomic_store(&g_rl, rl); atomic_store(&g_rr, rr);
    atomic_store(&g_r, r); atomic_store(&g_g, g); atomic_store(&g_b, b);
    atomic_store(&g_dirty, 1);
}

static void send_status(JNIEnv *env, const char *msg)
{
    jstring s = (*env)->NewStringUTF(env, msg);
    (*env)->CallStaticVoidMethod(env, g_cls, g_status, s);
    (*env)->DeleteLocalRef(env, s);
}

/* SDL button -> bit in the frame mask. Bit layout is shared with the first bridge and DroidDeck's client. */
static const struct { SDL_GamepadButton button; int bit; } BUTTONS[] = {
    { SDL_GAMEPAD_BUTTON_SOUTH, 0 },          /* Cross */
    { SDL_GAMEPAD_BUTTON_EAST, 1 },           /* Circle */
    { SDL_GAMEPAD_BUTTON_WEST, 2 },           /* Square */
    { SDL_GAMEPAD_BUTTON_NORTH, 3 },          /* Triangle */
    { SDL_GAMEPAD_BUTTON_LEFT_SHOULDER, 4 },
    { SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER, 5 },
    { SDL_GAMEPAD_BUTTON_BACK, 8 },           /* Create */
    { SDL_GAMEPAD_BUTTON_START, 9 },          /* Options */
    { SDL_GAMEPAD_BUTTON_LEFT_STICK, 10 },
    { SDL_GAMEPAD_BUTTON_RIGHT_STICK, 11 },
    { SDL_GAMEPAD_BUTTON_GUIDE, 12 },         /* PS */
    { SDL_GAMEPAD_BUTTON_TOUCHPAD, 13 },
    { SDL_GAMEPAD_BUTTON_MISC1, 14 },         /* Mute */
    { SDL_GAMEPAD_BUTTON_DPAD_UP, 15 },
    { SDL_GAMEPAD_BUTTON_DPAD_DOWN, 16 },
    { SDL_GAMEPAD_BUTTON_DPAD_LEFT, 17 },
    { SDL_GAMEPAD_BUTTON_DPAD_RIGHT, 18 },
    { SDL_GAMEPAD_BUTTON_LEFT_PADDLE1, 19 },  /* Edge left back button  */
    { SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1, 20 }, /* Edge right back button */
    { SDL_GAMEPAD_BUTTON_LEFT_PADDLE2, 21 },  /* Edge left Fn  */
    { SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2, 22 }, /* Edge right Fn */
};

static float stick(Sint16 v) { return (float)(((int)v + 32768) >> 8); }                /* 0..255, 128 = centre */
static float trigger(Sint16 v) { return (float)((int)v * 255 / 32767); }               /* 0..255 */

static void send_frame(JNIEnv *env, SDL_Gamepad *pad, jfloatArray arr)
{
    float f[FRAME_LEN];
    memset(f, 0, sizeof(f));

    int mask = 0;
    for (size_t i = 0; i < SDL_arraysize(BUTTONS); i++) {
        if (SDL_GetGamepadButton(pad, BUTTONS[i].button)) mask |= 1 << BUTTONS[i].bit;
    }
    f[1] = stick(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFTX));
    f[2] = stick(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFTY));
    f[3] = stick(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_RIGHTX));
    f[4] = stick(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_RIGHTY));
    f[5] = trigger(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFT_TRIGGER));
    f[6] = trigger(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER));
    if (f[5] > 30.0f) mask |= 1 << 6;
    if (f[6] > 30.0f) mask |= 1 << 7;
    f[0] = (float)mask; /* < 2^24, exact in a float */

    float v[3];
    if (SDL_GetGamepadSensorData(pad, SDL_SENSOR_GYRO, v, 3)) { f[7] = v[0]; f[8] = v[1]; f[9] = v[2]; }
    if (SDL_GetGamepadSensorData(pad, SDL_SENSOR_ACCEL, v, 3)) { f[10] = v[0]; f[11] = v[1]; f[12] = v[2]; }

    if (SDL_GetNumGamepadTouchpads(pad) > 0) {
        for (int i = 0; i < 2; i++) {
            bool down = false;
            float x = 0, y = 0, p = 0;
            if (SDL_GetGamepadTouchpadFinger(pad, 0, i, &down, &x, &y, &p) && down) {
                f[13 + 3 * i] = 1.0f;
                f[14 + 3 * i] = x * 1919.0f;   /* DualSense touchpad is 1920 x 1080 */
                f[15 + 3 * i] = y * 1079.0f;
            }
        }
    }

    int pct = -1;
    SDL_PowerState ps = SDL_GetGamepadPowerInfo(pad, &pct);
    f[19] = (float)pct;
    f[20] = (float)ps;

    (*env)->SetFloatArrayRegion(env, arr, 0, FRAME_LEN, f);
    (*env)->CallStaticVoidMethod(env, g_cls, g_frame, arr);
}

static SDL_Gamepad *open_pad(JNIEnv *env, SDL_JoystickID id, SDL_JoystickID *out_id)
{
    SDL_Gamepad *pad = SDL_OpenGamepad(id);
    if (!pad) return NULL;
    *out_id = id;
    if (SDL_GamepadHasSensor(pad, SDL_SENSOR_GYRO)) SDL_SetGamepadSensorEnabled(pad, SDL_SENSOR_GYRO, true);
    if (SDL_GamepadHasSensor(pad, SDL_SENSOR_ACCEL)) SDL_SetGamepadSensorEnabled(pad, SDL_SENSOR_ACCEL, true);
    atomic_store(&g_dirty, 1); /* re-apply lightbar */
    char msg[200];
    const char *name = SDL_GetGamepadName(pad);
    snprintf(msg, sizeof(msg), "Connected: %s", name ? name : "gamepad");
    send_status(env, msg);
    return pad;
}

int main(int argc, char *argv[])
{
    (void)argc; (void)argv;
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_ENHANCED_REPORTS, "1");
    SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "0");

    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    if (!env || !g_cls) return 1;

    if (!SDL_Init(SDL_INIT_GAMEPAD)) {
        char msg[200];
        snprintf(msg, sizeof(msg), "SDL init failed: %s", SDL_GetError());
        send_status(env, msg);
        return 1;
    }
    send_status(env, "Waiting for a controller (plug it in via USB-C)");

    jfloatArray arr = (*env)->NewFloatArray(env, FRAME_LEN);
    SDL_Gamepad *pad = NULL;
    SDL_JoystickID pad_id = 0;
    Uint64 last_scan = 0, last_rumble = 0;
    int rumble_on = 0;

    for (;;) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            if (e.type == SDL_EVENT_GAMEPAD_ADDED && !pad) {
                pad = open_pad(env, e.gdevice.which, &pad_id);
            } else if (e.type == SDL_EVENT_GAMEPAD_REMOVED && pad && e.gdevice.which == pad_id) {
                SDL_CloseGamepad(pad);
                pad = NULL;
                rumble_on = 0;
                send_status(env, "Controller disconnected");
            }
        }

        Uint64 now = SDL_GetTicks();
        if (!pad && now - last_scan > 1000) { /* fallback in case the ADDED event was missed */
            last_scan = now;
            int n = 0;
            SDL_JoystickID *ids = SDL_GetGamepads(&n);
            if (ids && n > 0) pad = open_pad(env, ids[0], &pad_id);
            SDL_free(ids);
        }

        if (pad) {
            SDL_UpdateGamepads();

            int dirty = atomic_exchange(&g_dirty, 0);
            int rl = atomic_load(&g_rl), rr = atomic_load(&g_rr);
            if (dirty) SDL_SetGamepadLED(pad, (Uint8)atomic_load(&g_r), (Uint8)atomic_load(&g_g), (Uint8)atomic_load(&g_b));
            if (rl || rr) {
                if (dirty || now - last_rumble > 400) { /* SDL rumble has a duration, so refresh it */
                    SDL_RumbleGamepad(pad, (Uint16)(rl * 257), (Uint16)(rr * 257), 1000);
                    last_rumble = now;
                }
                rumble_on = 1;
            } else if (rumble_on) {
                SDL_RumbleGamepad(pad, 0, 0, 0);
                rumble_on = 0;
            }

            send_frame(env, pad, arr);
        }
        SDL_Delay(4);
    }
    return 0;
}
