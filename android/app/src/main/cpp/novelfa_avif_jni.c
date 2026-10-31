/* Thin JNI layer over novelfa_avif_core.c — nothing here
 * decodes anything itself. Method names must match
 * com.novelfa.reader.reader.AvifDecoder's external funs exactly
 * (a Kotlin object compiles to a class of the same name, so the
 * JNI prefix is Java_com_novelfa_reader_reader_AvifDecoder_).
 *
 * Failure contract: every failure returns JNI_FALSE and the
 * Kotlin side turns it into null (page failure placeholder),
 * never an exception. Bitmap pixels are always unlocked before
 * returning, on every path.
 */

#include <jni.h>
#include <stdint.h>

#include <android/bitmap.h>

#include "novelfa_avif_core.h"

JNIEXPORT jboolean JNICALL
Java_com_novelfa_reader_reader_AvifDecoder_decodeInfo(
    JNIEnv * env, jobject thiz, jbyteArray data, jintArray out) {
    (void)thiz;
    if (data == NULL || out == NULL) {
        return JNI_FALSE;
    }
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) {
        return JNI_FALSE;
    }
    jbyte * bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) {
        return JNI_FALSE;
    }
    int w = 0, h = 0;
    int ok = novelfa_avif_info((const uint8_t *)bytes, (size_t)len, &w, &h);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (!ok) {
        return JNI_FALSE;
    }
    jint wh[2];
    wh[0] = w;
    wh[1] = h;
    (*env)->SetIntArrayRegion(env, out, 0, 2, wh);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_novelfa_reader_reader_AvifDecoder_decodeInto(
    JNIEnv * env, jobject thiz, jbyteArray data, jobject bitmap) {
    (void)thiz;
    if (data == NULL || bitmap == NULL) {
        return JNI_FALSE;
    }
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        return JNI_FALSE;
    }
    /* ARGB_8888 in Kotlin is RGBA_8888 at the NDK level — the
     * same 4-byte RGBA layout the core writes. Anything else
     * (565, F16) is a caller bug, fail rather than corrupt. */
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }
    if (info.width <= 0 || info.height <= 0) {
        return JNI_FALSE;
    }
    void * pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) !=
        ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    jboolean result = JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte * bytes =
        (len > 0) ? (*env)->GetByteArrayElements(env, data, NULL) : NULL;
    if (bytes != NULL) {
        /* The core re-derives the display size and fails on a
         * mismatch, so a bitmap created from stale dimensions
         * can never receive wrong-sized pixels. */
        int ok = novelfa_avif_decode_rgba(
            (const uint8_t *)bytes, (size_t)len, (uint8_t *)pixels,
            (int)info.stride, (int)info.width, (int)info.height);
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
        result = ok ? JNI_TRUE : JNI_FALSE;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return result;
}
