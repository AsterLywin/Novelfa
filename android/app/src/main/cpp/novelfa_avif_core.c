/* See novelfa_avif_core.h for the contract. */

#include "novelfa_avif_core.h"

#include <stdlib.h>
#include <string.h>

#include "avif/avif.h"

/* Upper bound for the transform path's temp buffer: 128M pixels
 * (512MB RGBA). The viewer's own decode ceiling is 8192 x 16384
 * (134M px), so anything above this would be scaled down after
 * decode anyway; failing it here avoids a native malloc bomb on
 * a hostile giant file. The direct (no-transform) path has no
 * temp buffer and is unaffected.
 */
#define MAX_TRANSFORM_PIXELS ((int64_t)128 * 1024 * 1024)

typedef struct {
    avifDecoder * decoder;
    avifImage * view; /* crop view, NULL when the whole image is used */
    avifCropRect crop; /* crop rect in stored coordinates */
} DecoderState;

static void destroy_state(DecoderState * st) {
    if (st->view != NULL) {
        avifImageDestroy(st->view);
        st->view = NULL;
    }
    if (st->decoder != NULL) {
        avifDecoderDestroy(st->decoder);
        st->decoder = NULL;
    }
}

/* Creates the decoder and parses the boxes. Pixel data is NOT
 * decoded here (avifDecoderParse only reads the container), so
 * novelfa_avif_info stays cheap. Returns 1 on success; the
 * caller still owns destroy_state() on failure (the struct was
 * zeroed first, so destroying a half-built state is safe).
 */
static int create_and_parse(const uint8_t * data, size_t size,
                            DecoderState * st) {
    memset(st, 0, sizeof(*st));
    st->decoder = avifDecoderCreate();
    if (st->decoder == NULL) {
        return 0;
    }
    /* Two threads: libavif's own Android sample found this the
     * empirical sweet spot over "all cores" for still images. */
    st->decoder->maxThreads = 2;
    /* Metadata we never use: skipping it avoids the EXIF/XMP
     * parse cost on every page. */
    st->decoder->ignoreXMP = AVIF_TRUE;
    st->decoder->ignoreExif = AVIF_TRUE;
    /* Same relaxations as libavif's Android sample: a bad clap
     * box or a missing pixi property must degrade to "show the
     * whole image", not fail the page (older libheif files lack
     * pixi). */
    st->decoder->strictFlags &= ~AVIF_STRICT_CLAP_VALID;
    st->decoder->strictFlags &= ~AVIF_STRICT_PIXI_REQUIRED;
    if (avifDecoderSetIOMemory(st->decoder, data, size) != AVIF_RESULT_OK) {
        return 0;
    }
    if (avifDecoderParse(st->decoder) != AVIF_RESULT_OK) {
        return 0;
    }
    if (st->decoder->image == NULL) {
        return 0;
    }
    return 1;
}

/* Crop rect: the clean-aperture box when valid, else the whole
 * image. Same rule as libavif's Android sample (a crop that
 * would need upsampling is ignored, not applied).
 */
static void compute_crop(DecoderState * st) {
    const avifImage * image = st->decoder->image;
    avifDiagnostics diag;
    if ((image->transformFlags & AVIF_TRANSFORM_CLAP) != 0 &&
        avifCropRectFromCleanApertureBox(&st->crop, &image->clap,
                                         image->width, image->height,
                                         &diag) &&
        !avifCropRectRequiresUpsampling(&st->crop, image->yuvFormat)) {
        return;
    }
    st->crop.width = image->width;
    st->crop.height = image->height;
    st->crop.x = 0;
    st->crop.y = 0;
}

/* Display size: cropped size, swapped when irot is 90/270
 * (angle 1 or 3). imir never changes dimensions. */
static void display_size(const DecoderState * st, int * w, int * h) {
    const avifImage * image = st->decoder->image;
    int angle = 0;
    if ((image->transformFlags & AVIF_TRANSFORM_IROT) != 0) {
        angle = image->irot.angle & 3;
    }
    if (angle == 1 || angle == 3) {
        *w = (int)st->crop.height;
        *h = (int)st->crop.width;
    } else {
        *w = (int)st->crop.width;
        *h = (int)st->crop.height;
    }
}

int novelfa_avif_info(const uint8_t * data, size_t size,
                      int * outWidth, int * outHeight) {
    DecoderState st;
    if (data == NULL || size == 0 || outWidth == NULL ||
        outHeight == NULL) {
        return 0;
    }
    if (!create_and_parse(data, size, &st)) {
        destroy_state(&st);
        return 0;
    }
    compute_crop(&st);
    display_size(&st, outWidth, outHeight);
    int ok = (*outWidth > 0 && *outHeight > 0);
    destroy_state(&st);
    return ok;
}

int novelfa_avif_decode_rgba(const uint8_t * data, size_t size,
                             uint8_t * outPixels, int outRowBytes,
                             int outWidth, int outHeight) {
    DecoderState st;
    if (data == NULL || size == 0 || outPixels == NULL ||
        outRowBytes <= 0 || outWidth <= 0 || outHeight <= 0) {
        return 0;
    }
    if (!create_and_parse(data, size, &st)) {
        destroy_state(&st);
        return 0;
    }
    int ok = 0;
    /* First frame. Grid tiles are composited into one image by
     * libavif itself; an animated file yields frame 0. */
    if (avifDecoderNextImage(st.decoder) == AVIF_RESULT_OK) {
        compute_crop(&st);
        int dispW = 0, dispH = 0;
        display_size(&st, &dispW, &dispH);
        if (dispW == outWidth && dispH == outHeight) {
            const avifImage * stored = st.decoder->image;
            avifImage * img = st.decoder->image;
            int needsView = (st.crop.width != stored->width ||
                             st.crop.height != stored->height ||
                             st.crop.x != 0 || st.crop.y != 0);
            if (needsView) {
                st.view = avifImageCreateEmpty();
                if (st.view == NULL ||
                    avifImageSetViewRect(st.view, stored, &st.crop) !=
                        AVIF_RESULT_OK) {
                    /* A crop we cannot apply must fail the page,
                     * never silently show the wrong area. */
                    goto done;
                }
                img = st.view;
            }
            const int sw = (int)img->width;
            const int sh = (int)img->height;
            int angle = 0;
            if ((stored->transformFlags & AVIF_TRANSFORM_IROT) != 0) {
                angle = stored->irot.angle & 3;
            }
            int mirror = 0;
            int axis = 0;
            if ((stored->transformFlags & AVIF_TRANSFORM_IMIR) != 0) {
                mirror = 1;
                axis = stored->imir.axis;
            }
            if (angle == 0 && !mirror) {
                /* Common path, exactly libavif's Android sample:
                 * YUV -> RGB straight into the output buffer.
                 * Depth is forced to 8: the output is an
                 * ARGB_8888 bitmap whatever the source depth. */
                avifRGBImage rgb;
                avifRGBImageSetDefaults(&rgb, img);
                rgb.depth = 8;
                rgb.pixels = outPixels;
                rgb.rowBytes = outRowBytes;
                rgb.alphaPremultiplied = AVIF_TRUE;
                ok = (avifImageYUVToRGB(img, &rgb) == AVIF_RESULT_OK);
            } else {
                /* Transform path: YUV -> RGB into a temp buffer,
                 * then ONE copy with rotation + mirror folded
                 * into the index math (no second full pass).
                 * Inverse mapping, display -> stored: un-mirror
                 * first (mirror is its own inverse, and it was
                 * applied after rotation), then un-rotate.
                 * Forward rotations (anti-clockwise):
                 *   90 CCW: (sx,sy) -> (sy, sw-1-sx)
                 *   180:    (sx,sy) -> (sw-1-sx, sh-1-sy)
                 *   270 CCW:(sx,sy) -> (sh-1-sy, sx)
                 * so the inverses below are:
                 *   90 CCW: sx = sw-1-ry, sy = rx
                 *   180:    sx = sw-1-rx, sy = sh-1-ry
                 *   270 CCW:sx = ry, sy = sh-1-rx
                 * where (rx,ry) is the un-mirrored display
                 * coordinate and (sx,sy) the stored one. */
                if ((int64_t)sw * sh > MAX_TRANSFORM_PIXELS) {
                    goto done;
                }
                uint8_t * tmp =
                    (uint8_t *)malloc((size_t)sw * sh * 4);
                if (tmp == NULL) {
                    goto done;
                }
                avifRGBImage rgb;
                avifRGBImageSetDefaults(&rgb, img);
                rgb.depth = 8;
                rgb.pixels = tmp;
                rgb.rowBytes = sw * 4;
                rgb.alphaPremultiplied = AVIF_TRUE;
                if (avifImageYUVToRGB(img, &rgb) == AVIF_RESULT_OK) {
                    for (int dy = 0; dy < outHeight; dy++) {
                        uint8_t * dstRow =
                            outPixels + (size_t)dy * outRowBytes;
                        for (int dx = 0; dx < outWidth; dx++) {
                            int rx = dx, ry = dy;
                            if (mirror) {
                                if (axis == 1) {
                                    rx = outWidth - 1 - dx;
                                } else {
                                    ry = outHeight - 1 - dy;
                                }
                            }
                            int sx, sy;
                            switch (angle) {
                                case 1:
                                    sx = sw - 1 - ry;
                                    sy = rx;
                                    break;
                                case 2:
                                    sx = sw - 1 - rx;
                                    sy = sh - 1 - ry;
                                    break;
                                case 3:
                                    sx = ry;
                                    sy = sh - 1 - rx;
                                    break;
                                default:
                                    sx = rx;
                                    sy = ry;
                                    break;
                            }
                            memcpy(dstRow + (size_t)dx * 4,
                                   tmp + ((size_t)sy * sw + sx) * 4, 4);
                        }
                    }
                    ok = 1;
                }
                free(tmp);
            }
        }
    }
done:
    destroy_state(&st);
    return ok;
}
