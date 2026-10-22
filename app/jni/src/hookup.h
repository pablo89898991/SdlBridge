/*
 * "Steam Native Hook-up": instead of reading a controller as a gamepad and re-presenting it, hand Steam the
 * REAL controller. SDL3 is still used to find out which USB HID interface is a game controller (any
 * controller SDL3's USB driver supports); that interface is then released by SDL's gamepad layer and opened
 * raw, and every HID report travels unchanged between the controller and DroidDeck's hidraw node -- with the
 * controller's real vendor/product id, name, serial and report descriptor -- so Steam's own driver for that
 * controller runs, exactly like on a PC: a Vader 4 Pro with its back buttons, C/Z and gyro, a DualSense Edge
 * with its touchpad, mute, paddles and Fn buttons, an Xbox controller, a Switch Pro controller, ...
 *
 * Controllers SDL3 can only reach through Android's input (Bluetooth) cannot be handed over raw; they keep
 * working as before (DroidDeck's Steam Deck pad). Included once from main.c; SDL_hid calls run on the SDL thread.
 */
#pragma once

#define HOOKUP_SLOTS 4
#define HOOKUP_REPORT_MAX 128

typedef struct {
    SDL_hid_device *dev;
    char path[256];
    Uint16 vid, pid;
    int iface;
} HookupDev;

static HookupDev g_hk[HOOKUP_SLOTS];
static _Atomic int g_hookup_on;
static int g_hookup_applied = -1;          /* last mode the SDL thread acted on */
static char g_hk_types[1024];              /* "0xVVVV/0xPPPP,..." controller types handed to Steam */
static int g_hk_iface_of_type[32];         /* interface number SDL used, per entry in g_hk_types (index order) */
static Uint16 g_hk_type_vid[32], g_hk_type_pid[32];
static int g_hk_type_count;
static Uint64 g_hk_scan_ms;
static Uint32 g_hk_changes = 0xffffffffu;
static jmethodID g_hk_added, g_hk_input, g_hk_removed, g_hk_reply, g_hk_prefetch, g_hk_log;

/* Logged to logcat AND sent to DroidDeck, which writes it into the session's pad.log ("sdl3bridge: ..."). */
static void hk_log(JNIEnv *env, const char *fmt, ...)
{
    char line[400];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    SDL_Log("%s", line);
    if (env && g_hk_log) {
        jstring s = (*env)->NewStringUTF(env, line);
        if (s) {
            (*env)->CallStaticVoidMethod(env, g_cls, g_hk_log, s);
            (*env)->DeleteLocalRef(env, s);
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
}

/* ---- requests from Java (any thread) -> SDL thread ---- */
enum { HK_OUT = 0, HK_FEATURE_SET = 1, HK_GET_FEATURE = 2, HK_GET_INPUT = 3 };
typedef struct {
    int slot, type, len;
    jlong request;
    Uint8 data[HOOKUP_REPORT_MAX];
} HookupReq;
#define HK_QUEUE 128
static HookupReq g_hk_queue[HK_QUEUE];
static int g_hk_head, g_hk_tail;
static SDL_SpinLock g_hk_lock;

static void hk_push(int slot, int type, const Uint8 *data, int len, jlong request)
{
    if (slot < 0 || slot >= HOOKUP_SLOTS) return;
    if (len < 0) len = 0;
    if (len > HOOKUP_REPORT_MAX) len = HOOKUP_REPORT_MAX;
    SDL_LockSpinlock(&g_hk_lock);
    int next = (g_hk_tail + 1) % HK_QUEUE;
    if (next != g_hk_head) {
        HookupReq *r = &g_hk_queue[g_hk_tail];
        r->slot = slot; r->type = type; r->len = len; r->request = request;
        if (len) memcpy(r->data, data, (size_t)len);
        g_hk_tail = next;
    }
    SDL_UnlockSpinlock(&g_hk_lock);
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeSetHookup(JNIEnv *env, jclass c, jint on)
{
    (void)env; (void)c;
    atomic_store(&g_hookup_on, on ? 1 : 0);
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeHookupOutput(JNIEnv *env, jclass c, jint slot, jint kind, jbyteArray data)
{
    (void)c;
    if (!data) return;
    jsize n = (*env)->GetArrayLength(env, data);
    if (n < 1) return;
    Uint8 buf[HOOKUP_REPORT_MAX];
    if (n > HOOKUP_REPORT_MAX) n = HOOKUP_REPORT_MAX;
    (*env)->GetByteArrayRegion(env, data, 0, n, (jbyte *)buf);
    hk_push(slot, kind == 1 ? HK_FEATURE_SET : HK_OUT, buf, (int)n, 0);
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeHookupGet(JNIEnv *env, jclass c, jint slot, jlong request,
                                                                             jint reportId, jboolean input, jint length)
{
    (void)env; (void)c;
    Uint8 buf[2] = { (Uint8)reportId, (Uint8)(length > HOOKUP_REPORT_MAX ? HOOKUP_REPORT_MAX : length < 1 ? 1 : length) };
    hk_push(slot, input ? HK_GET_INPUT : HK_GET_FEATURE, buf, 2, request);
}

/* ---- SDL thread -> Java ---- */
static void hk_java_init(JNIEnv *env)
{
    if (g_hk_added) return;
    g_hk_added = (*env)->GetStaticMethodID(env, g_cls, "onHookupAdded", "(ILjava/lang/String;III)V");
    g_hk_input = (*env)->GetStaticMethodID(env, g_cls, "onHookupInput", "(I[B)V");
    g_hk_removed = (*env)->GetStaticMethodID(env, g_cls, "onHookupRemoved", "(I)V");
    g_hk_reply = (*env)->GetStaticMethodID(env, g_cls, "onHookupReply", "(IJ[B)V");
    g_hk_prefetch = (*env)->GetStaticMethodID(env, g_cls, "prefetchHookupDescriptor", "(Ljava/lang/String;)V");
    g_hk_log = (*env)->GetStaticMethodID(env, g_cls, "onBridgeLog", "(Ljava/lang/String;)V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static jbyteArray hk_bytes(JNIEnv *env, const Uint8 *data, int len)
{
    if (!data || len <= 0) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)data);
    return arr;
}

static bool hookup_active(void)
{
    for (int i = 0; i < HOOKUP_SLOTS; i++) if (g_hk[i].dev) return true;
    return false;
}

static int hookup_count(void)
{
    int n = 0;
    for (int i = 0; i < HOOKUP_SLOTS; i++) if (g_hk[i].dev) n++;
    return n;
}

static void hk_close(JNIEnv *env, int i, const char *why)
{
    if (!g_hk[i].dev) return;
    hk_log(env, "hookup: slot %d %04x:%04x released (%s)", i, g_hk[i].vid, g_hk[i].pid, why);
    SDL_hid_close(g_hk[i].dev);
    memset(&g_hk[i], 0, sizeof(g_hk[i]));
    if (g_hk_removed) (*env)->CallStaticVoidMethod(env, g_cls, g_hk_removed, (jint)i);
}

static bool hk_path_open(const char *path)
{
    for (int i = 0; i < HOOKUP_SLOTS; i++) if (g_hk[i].dev && !strcmp(g_hk[i].path, path)) return true;
    return false;
}

static bool hk_open(JNIEnv *env, const char *path, Uint16 vid, Uint16 pid, int iface)
{
    if (hk_path_open(path)) return true;
    int slot = -1;
    for (int i = 0; i < HOOKUP_SLOTS; i++) if (!g_hk[i].dev) { slot = i; break; }
    if (slot < 0) return false;
    /* Read the HID report descriptor while nobody holds the interface (Android refuses an interface request
     * on an interface another connection has claimed), so Steam gets the controller's real one. */
    if (g_hk_prefetch) {
        jstring p = (*env)->NewStringUTF(env, path);
        (*env)->CallStaticVoidMethod(env, g_cls, g_hk_prefetch, p);
        (*env)->DeleteLocalRef(env, p);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    SDL_hid_device *dev = SDL_hid_open_path(path);
    if (!dev) {
        hk_log(env, "hookup: could not open %s: %s", path, SDL_GetError());
        return false;
    }
    SDL_hid_set_nonblocking(dev, 1);
    g_hk[slot].dev = dev;
    SDL_strlcpy(g_hk[slot].path, path, sizeof(g_hk[slot].path));
    g_hk[slot].vid = vid;
    g_hk[slot].pid = pid;
    g_hk[slot].iface = iface;
    hk_log(env, "hookup: slot %d is %04x:%04x interface %d (%s), handing it to DroidDeck", slot, vid, pid, iface, path);
    if (g_hk_added) {
        jstring p = (*env)->NewStringUTF(env, path);
        (*env)->CallStaticVoidMethod(env, g_cls, g_hk_added, (jint)slot, p, (jint)vid, (jint)pid, (jint)iface);
        (*env)->DeleteLocalRef(env, p);
    }
    return true;
}

/* SDL's gamepad layer must let go of the hooked-up controller types: the ignore hint (which also keeps the
 * Steam Controller 2026, passed through by triton.h, away) plus a restart of the gamepad subsystem. */
static void hk_apply_ignore_list(JNIEnv *env)
{
    char hint[1200] = "0x28de/0x1302,0x28de/0x1304,0x28de/0x1305";
    if (atomic_load(&g_hookup_on) && g_hk_types[0]) {
        SDL_strlcat(hint, ",", sizeof(hint));
        SDL_strlcat(hint, g_hk_types, sizeof(hint));
    }
    SDL_SetHint(SDL_HINT_GAMECONTROLLER_IGNORE_DEVICES, hint);
}

static bool hk_type_known(Uint16 vid, Uint16 pid)
{
    for (int i = 0; i < g_hk_type_count; i++) if (g_hk_type_vid[i] == vid && g_hk_type_pid[i] == pid) return true;
    return false;
}

static void hk_add_type(Uint16 vid, Uint16 pid, int iface)
{
    if (hk_type_known(vid, pid) || g_hk_type_count >= (int)SDL_arraysize(g_hk_type_vid)) return;
    char one[24];
    snprintf(one, sizeof(one), "%s0x%04x/0x%04x", g_hk_types[0] ? "," : "", vid, pid);
    SDL_strlcat(g_hk_types, one, sizeof(g_hk_types));
    g_hk_type_vid[g_hk_type_count] = vid;
    g_hk_type_pid[g_hk_type_count] = pid;
    g_hk_iface_of_type[g_hk_type_count] = iface;
    g_hk_type_count++;
}

static int hk_type_iface(Uint16 vid, Uint16 pid)
{
    for (int i = 0; i < g_hk_type_count; i++) if (g_hk_type_vid[i] == vid && g_hk_type_pid[i] == pid) return g_hk_iface_of_type[i];
    return -1;
}

static void restart_gamepads(JNIEnv *env);   /* main.c */

/* Interface number of an SDL hid path ("<usb device>/<vid>/<pid>/<interface index>" on Android). */
static int hk_path_iface(const char *path)
{
    const char *slash = strrchr(path, '/');
    return slash ? SDL_atoi(slash + 1) : 0;
}

/* Hand every SDL3 USB-driver gamepad (and later copies of those controller types) over to Steam. */
static void hookup_take_gamepads(JNIEnv *env)
{
    bool handed = false;
    int cnt = 0;
    SDL_JoystickID *ids = SDL_GetGamepads(&cnt);
    for (int i = 0; ids && i < cnt; i++) {
        const char *path = SDL_GetGamepadPathForID(ids[i]);
        if (!path) continue;   /* Android input (Bluetooth): cannot be handed over raw */
        Uint16 vid = SDL_GetGamepadVendorForID(ids[i]), pid = SDL_GetGamepadProductForID(ids[i]);
        if (hk_type_known(vid, pid)) continue;
        hk_log(env, "hookup: taking %04x:%04x \"%s\" from SDL's gamepad layer", vid, pid,
               SDL_GetGamepadNameForID(ids[i]) ? SDL_GetGamepadNameForID(ids[i]) : "?");
        hk_add_type(vid, pid, hk_path_iface(path));
        handed = true;
    }
    SDL_free(ids);
    if (handed) {
        hk_log(env, "hookup: restarting SDL's gamepad layer so it lets go (ignored: %s)", g_hk_types);
        hk_apply_ignore_list(env);
        restart_gamepads(env);     /* SDL closes them; they stay ignored; the scan below opens them raw */
        g_hk_changes = 0xffffffffu;
    }
}

static void hookup_scan(JNIEnv *env)
{
    if (!g_hk_type_count) return;
    SDL_hid_device_info *list = SDL_hid_enumerate(0, 0);
    for (SDL_hid_device_info *d = list; d; d = d->next) {
        if (!d->path || !hk_type_known(d->vendor_id, d->product_id)) continue;
        int want = hk_type_iface(d->vendor_id, d->product_id);
        int iface = hk_path_iface(d->path);
        if (want >= 0 && iface != want) continue;
        if (d->bus_type == SDL_HID_API_BUS_BLUETOOTH) continue;
        if (!hk_path_open(d->path)) hk_log(env, "hookup: found %04x:%04x at %s, opening it raw", d->vendor_id, d->product_id, d->path);
        hk_open(env, d->path, d->vendor_id, d->product_id, iface);
    }
    SDL_hid_free_enumeration(list);
}

static void hookup_run_queue(JNIEnv *env)
{
    for (;;) {
        HookupReq r;
        SDL_LockSpinlock(&g_hk_lock);
        bool have = g_hk_head != g_hk_tail;
        if (have) { r = g_hk_queue[g_hk_head]; g_hk_head = (g_hk_head + 1) % HK_QUEUE; }
        SDL_UnlockSpinlock(&g_hk_lock);
        if (!have) return;
        SDL_hid_device *dev = g_hk[r.slot].dev;
        if (r.type == HK_GET_FEATURE || r.type == HK_GET_INPUT) {
            Uint8 buf[HOOKUP_REPORT_MAX + 1] = { 0 };
            int want = r.data[1] ? r.data[1] : HOOKUP_REPORT_MAX;
            buf[0] = r.data[0];
            int n = -1;
            if (dev) n = r.type == HK_GET_INPUT ? SDL_hid_get_input_report(dev, buf, (size_t)want)
                                                : SDL_hid_get_feature_report(dev, buf, (size_t)want);
            jbyteArray arr = n > 0 ? hk_bytes(env, buf, n) : NULL;
            if (g_hk_reply) (*env)->CallStaticVoidMethod(env, g_cls, g_hk_reply, (jint)r.slot, r.request, arr);
            if (arr) (*env)->DeleteLocalRef(env, arr);
        } else if (dev && r.type == HK_FEATURE_SET) {
            SDL_hid_send_feature_report(dev, r.data, (size_t)r.len);
        } else if (dev && r.type == HK_OUT) {
            SDL_hid_write(dev, r.data, (size_t)r.len);
        }
    }
}

static void hookup_poll(JNIEnv *env, Uint64 now, bool driver_off)
{
    hk_java_init(env);
    int on = atomic_load(&g_hookup_on) && !driver_off;
    if (on != g_hookup_applied) {
        int was = g_hookup_applied;
        g_hookup_applied = on;
        hk_log(env, "hookup: Steam Native Hook-up %s", on ? "ON" : "OFF");
        if (!on && was == 1) {
            for (int i = 0; i < HOOKUP_SLOTS; i++) hk_close(env, i, "Steam Native Hook-up off");
            g_hk_types[0] = 0;
            g_hk_type_count = 0;
            hk_apply_ignore_list(env);
            restart_gamepads(env);   /* SDL takes the controllers back as gamepads */
        }
    }
    if (!on) {
        hookup_run_queue(env);   /* answer stale reads */
        return;
    }
    static Uint64 last_take;
    if (now - last_take > 250) { last_take = now; hookup_take_gamepads(env); }
    Uint32 changes = SDL_hid_device_change_count();
    if (changes != g_hk_changes || now - g_hk_scan_ms > 2000) {
        g_hk_changes = changes;
        g_hk_scan_ms = now;
        hookup_scan(env);
    }
    for (int i = 0; i < HOOKUP_SLOTS; i++) {
        for (int reads = 0; g_hk[i].dev && reads < 64; reads++) {
            Uint8 buf[HOOKUP_REPORT_MAX];
            int n = SDL_hid_read(g_hk[i].dev, buf, sizeof(buf));
            if (n == 0) break;
            if (n < 0) { hk_close(env, i, "unplugged"); break; }
            jbyteArray arr = hk_bytes(env, buf, n);
            if (arr && g_hk_input) (*env)->CallStaticVoidMethod(env, g_cls, g_hk_input, (jint)i, arr);
            if (arr) (*env)->DeleteLocalRef(env, arr);
        }
    }
    hookup_run_queue(env);
}
