/* AVIF decode core for Novelfa's native comic viewer.
 *
 * Pure C on top of libavif (BSD-2-Clause) + libaom: no Android
 * dependencies, so the exact shipped code is testable on the host
 * (see avif_build/host_test.c). The JNI wrapper is a thin layer
 * over these two functions (novelfa_avif_jni.c).
 *
 * What "decode" means here, precisely:
 * - First frame only. An animated AVIF shows its first frame, the
 *   same rule the viewer already applies to WebP (still WebP
 *   keeps the bitmap pipeline; animated WebP takes the drawable
 *   path). AVIF animation is out of scope for v1.
 * - The clean-aperture (clap) crop IS applied: it defines the
 *   image's intended visible area (libavif's own Android sample
 *   does the same).
 * - The irot rotation and imir mirror ARE applied, in MIAF
 *   order (rotate first, then mirror). libavif's decoder only
 *   parses these boxes into metadata; it does NOT apply them to
 *   pixels, and its own Android sample ignores them. A photo
 *   stored sideways with an irot box would otherwise display
 *   sideways, exactly the EXIF bug the viewer already fixed for
 *   JPEG. irot angle is anti-clockwise (ISO/IEC 23008-12):
 *   0 = 0, 1 = 90 CCW, 2 = 180, 3 = 270 CCW. imir axis 0 =
 *   top/bottom exchanged, 1 = left/right exchanged.
 * - EXIF orientation embedded in an AVIF (exif item, no irot
 *   box) is NOT honored: libavif parses it only as metadata and
 *   the standard AVIF rotation mechanism is irot. Same gap as
 *   libavif's own sample; AVIF files with EXIF-but-no-irot are
 *   rare (encoders write irot).
 * - Output is always 8-bit RGBA, alpha-premultiplied — the exact
 *   layout of an Android ARGB_8888 bitmap's locked pixels, so
 *   the JNI layer memcpys nothing twice in the common case.
 *   10/12-bit sources are downconverted by libavif.
 */

#ifndef NOVELFA_AVIF_CORE_H
#define NOVELFA_AVIF_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Display size of the first frame, after the clap crop and the
 * irot rotation (a 90/270 rotation swaps width and height).
 * Parse only, no pixel decode: cheap enough for the dual-page
 * split decision and the thumbnail path. Returns 1 on success
 * (and sets *outWidth/*outHeight, both > 0), 0 on failure.
 */
int novelfa_avif_info(const uint8_t * data, size_t size,
                      int * outWidth, int * outHeight);

/* Decodes the first frame into outPixels: RGBA, 8-bit,
 * premultiplied alpha, rowBytes = outRowBytes, size
 * outWidth x outHeight — which MUST be the display size from
 * novelfa_avif_info (the function re-derives it and fails on a
 * mismatch, so a stale bitmap can never receive wrong-sized
 * pixels). clap + irot + imir are applied, in that order.
 * Returns 1 on success, 0 on failure (corrupt file, unsupported
 * feature, allocation failure). A failure here means the page
 * takes the viewer's normal failure placeholder, never a crash.
 */
int novelfa_avif_decode_rgba(const uint8_t * data, size_t size,
                             uint8_t * outPixels, int outRowBytes,
                             int outWidth, int outHeight);

#ifdef __cplusplus
}
#endif

#endif /* NOVELFA_AVIF_CORE_H */
