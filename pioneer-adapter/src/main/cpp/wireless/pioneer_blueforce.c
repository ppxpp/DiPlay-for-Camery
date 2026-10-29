/* Firmware-specific ARM32 ABI adapter. No vendor binaries are packaged.
 * See docs/2026-10-26-0723-先锋车机蓝牙接入无线CarPlay技术路线与验证报告.md for the verified layout and hashes.
 * Notifications contain borrowed pointers: copy before returning to Blueforce.
 */
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <time.h>

#define JNI_FN(name) Java_com_shilapi_xcertplay_btprobe_PioneerBlueforceNative_##name
#define CAPACITY (256 * 1024)
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static unsigned char queue[CAPACITY];
static size_t head, used;
static int active, overflow, registered, last_event, event_count;
static unsigned long rx_bytes, tx_events;
static int tx_result;
static void *library, *manage;
static void **vtable;
static void fail(JNIEnv *env, const char *message) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), message);
}
static void destructor(void *self) { (void)self; }
static int notify(void *self, int event, int count, const intptr_t *args) {
    (void)self;
    pthread_mutex_lock(&lock);
    if (active) {
        last_event = event; event_count++;
        /* onReceiveParam3 stores [result, portIndex, deviceHandle]. */
        if (event == 504 && count == 3 && args && args[1] == 0) {
            tx_events++; tx_result = (int)args[0]; pthread_cond_broadcast(&changed);
        }
        if (event == 500 && count == 3 && args && args[0] == 0) {
            intptr_t length = args[2];
            if (!args[1] || length < 1 || length > 4096 || (size_t)length > CAPACITY - used) {
                overflow = 1;
            } else {
                const unsigned char *bytes = (const unsigned char *)args[1];
                for (intptr_t i = 0; i < length; i++) queue[(head + used + (size_t)i) % CAPACITY] = bytes[i];
                used += (size_t)length; rx_bytes += (unsigned long)length;
            }
            pthread_cond_broadcast(&changed);
        }
    }
    pthread_mutex_unlock(&lock);
    return 0;
}
/* The vendor add/del methods store/remove this primary interface pointer without
 * RefBase ownership operations. It remains valid for the process lifetime,
 * including callbacks already in flight during unregister. */
static void *notify_slots[] = { (void *)destructor, (void *)destructor, (void *)notify };
static struct { void **slots; } callback = { notify_slots };

JNIEXPORT jstring JNICALL JNI_FN(nativeOpen)(JNIEnv *env, jobject self) {
    (void)self;
    if (sizeof(void *) != 4) return (*env)->NewStringUTF(env, "unsupported ABI: requires armeabi-v7a");
    if (registered) return (*env)->NewStringUTF(env, "previous native callback still registered; restart probe");
    if (!library) library = dlopen("/system/lib/libblueforce.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return (*env)->NewStringUTF(env, dlerror());
    void *(*get_factory)(void) = dlsym(library, "_ZN7android17CBlueforceFactory11getInstanceEv");
    int (*create)(void *, void **) = dlsym(library, "_ZN7android17CBlueforceFactory12createManageERPNS_16IBlueforceManageE");
    if (!get_factory || !create) return (*env)->NewStringUTF(env, "factory ABI symbols missing");
    if (!manage) {
        int result = create(get_factory(), &manage);
        if (result || !manage) return (*env)->NewStringUTF(env, "createManage failed");
        vtable = *(void ***)manage;
        result = ((int (*)(void *, int))vtable[2])(manage, -1);
        if (result) {
            char message[80]; snprintf(message, sizeof(message), "Blueforce init(-1)=%d", result);
            manage = NULL; return (*env)->NewStringUTF(env, message);
        }
    }
    pthread_mutex_lock(&lock);
    head = used = 0; overflow = 0; active = 1; rx_bytes = 0; event_count = 0; last_event = -1; tx_events = 0; tx_result = 0;
    pthread_mutex_unlock(&lock);
    int result = ((int (*)(void *, void *))vtable[4])(manage, &callback);
    if (result) {
        pthread_mutex_lock(&lock); active = 0; pthread_mutex_unlock(&lock);
        char message[80]; snprintf(message, sizeof(message), "registerNotify=%d", result);
        return (*env)->NewStringUTF(env, message);
    }
    registered = 1;
    return (*env)->NewStringUTF(env, "OK");
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeRead)(JNIEnv *env, jobject self, jint maximum, jint timeout) {
    (void)self;
    if (maximum < 1 || maximum > 65536 || timeout < 0 || timeout > 1000) { fail(env, "invalid read arguments"); return NULL; }
    struct timespec until;
    clock_gettime(CLOCK_REALTIME, &until);
    until.tv_sec += timeout / 1000; until.tv_nsec += (timeout % 1000) * 1000000L;
    if (until.tv_nsec >= 1000000000L) { until.tv_sec++; until.tv_nsec -= 1000000000L; }
    pthread_mutex_lock(&lock);
    while (active && !used && !overflow) {
        if (pthread_cond_timedwait(&changed, &lock, &until)) break;
    }
    if (overflow) { pthread_mutex_unlock(&lock); fail(env, "Blueforce RX overflow/invalid payload; byte stream invalidated"); return NULL; }
    if (active && !used) { pthread_mutex_unlock(&lock); return NULL; }
    size_t length = used < (size_t)maximum ? used : (size_t)maximum;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)length);
    if (result) {
        size_t first = length < CAPACITY - head ? length : CAPACITY - head;
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)first, (jbyte *)(queue + head));
        if (length > first) (*env)->SetByteArrayRegion(env, result, (jsize)first, (jsize)(length-first), (jbyte *)queue);
        head = (head + length) % CAPACITY; used -= length;
    }
    pthread_mutex_unlock(&lock); return result;
}
JNIEXPORT jint JNICALL JNI_FN(nativeWrite)(JNIEnv *env, jobject self, jbyteArray data) {
    (void)self;
    jsize length = (*env)->GetArrayLength(env, data);
    if (!manage || !registered || length < 1 || length > 4096) return -1;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return -1;
    int result = ((int (*)(void *, int, const unsigned char *, int))vtable[0x21c / 4])(manage, 0, (unsigned char *)bytes, length);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return result;
}
JNIEXPORT jstring JNICALL JNI_FN(nativeAddress)(JNIEnv *env, jobject self) {
    (void)self;
    if (!manage || !registered) { fail(env, "native bridge not open"); return NULL; }
    void *utils = dlopen("/system/lib/libutils.so", RTLD_NOW | RTLD_NOLOAD);
    void (*ctor)(void *) = utils ? dlsym(utils, "_ZN7android7String8C1Ev") : NULL;
    void (*dtor)(void *) = utils ? dlsym(utils, "_ZN7android7String8D1Ev") : NULL;
    if (!ctor || !dtor) { if (utils) dlclose(utils); fail(env, "String8 ABI missing"); return NULL; }
    struct { const char *text; } string8;
    ctor(&string8);
    int result = ((int (*)(void *, int, void *))vtable[0x230 / 4])(manage, 0, &string8);
    jstring text = NULL;
    if (!result && string8.text) text = (*env)->NewStringUTF(env, string8.text);
    dtor(&string8); dlclose(utils);
    if (result) fail(env, "serialportGetDeviceAddress failed");
    return text;
}
JNIEXPORT jstring JNICALL JNI_FN(nativeStats)(JNIEnv *env, jobject self) {
    (void)self; char message[160];
    pthread_mutex_lock(&lock);
    snprintf(message, sizeof(message), "events=%d lastEvent=%d rx=%lu queued=%zu overflow=%d", event_count, last_event, rx_bytes, used, overflow);
    pthread_mutex_unlock(&lock);
    return (*env)->NewStringUTF(env, message);
}
JNIEXPORT jint JNICALL JNI_FN(nativeClose)(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    pthread_mutex_lock(&lock); active = 0; used = 0; pthread_cond_broadcast(&changed); pthread_mutex_unlock(&lock);
    int result = 0;
    if (registered) {
        result = ((int (*)(void *, void *))vtable[5])(manage, &callback);
        if (!result) registered = 0;
    }
    /* Keep initialized manager/library alive: vendor Binder callbacks may be in
     * flight. Closing a stream never disconnects a system-owned serial port. */
    return result;
}
