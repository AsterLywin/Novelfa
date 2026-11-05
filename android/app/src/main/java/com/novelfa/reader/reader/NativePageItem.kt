package com.novelfa.reader.reader

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind
import com.novelfa.reader.data.CropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manual crop editor: the current page at fit size, with a
 * draggable rect over it — corners resize, dragging inside
 * moves the whole rect (ComicScreen's CropImageView behaviour,
 * reimplemented: no third-party crop widget, just a Canvas
 * overlay and drag gestures). Coordinates are fractions of
 * the displayed page, so the saved rect applies at any decode
 * size. The dim outside the rect is four strips (no blend
 * modes, no layers); the handles are primary squares.
 */
@Composable
internal fun CropEditorDialog(
    source: NativePageSource,
    page: Int,
    viewRotation: Int,
    screenWidthPx: Int,
    initialRect: CropRect?,
    onApply: (CropRect) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source, page, viewRotation) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val raw = source.renderPage(page, screenWidthPx)
                if (viewRotation != 0) {
                    val rotated = rotateBitmap(raw, viewRotation)
                    if (rotated !== raw) raw.recycle()
                    rotated
                } else {
                    raw
                }
            }.getOrNull()
        }
        bitmap = decoded
    }
    DisposableEffect(Unit) {
        onDispose { bitmap?.recycle() }
    }
    // Immutable data class: no copy needed, the drag code
    // replaces the state instead of mutating it.
    var rect by remember {
        mutableStateOf(initialRect ?: CropRect(0f, 0f, 1f, 1f))
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize()) {
                var areaSize by remember { mutableStateOf(IntSize.Zero) }
                val density = LocalDensity.current
                val scrollState = rememberScrollState()
                // 80% of the screen width, vertically scrollable:
                // a tall webtoon strip used to be contain-fit,
                // so it shrank to a narrow ribbon; now it is
                // wide and the strip scrolls. A short page is
                // still centred (the wrapper below is at least
                // the area height).
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(scrollState)
                        .onSizeChanged { areaSize = it },
                    contentAlignment = Alignment.TopCenter
                ) {
                    val bmp = bitmap
                    if (bmp != null && areaSize.width > 0 && areaSize.height > 0) {
                        val ratio = bmp.width.toFloat() / bmp.height
                        val imageHeightPx =
                            0.8f * areaSize.width.toFloat() / ratio
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .height(
                                    with(density) {
                                        maxOf(
                                            imageHeightPx,
                                            areaSize.height.toFloat()
                                        ).toDp()
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                        // The box IS the displayed image, so
                        // overlay fractions map to it exactly.
                        val boxModifier = Modifier
                            .fillMaxWidth(0.8f)
                            .aspectRatio(ratio)
                        Box(
                            modifier = boxModifier.pointerInput(Unit) {
                                // Stable key on purpose, and a
                                // hand-written loop, not
                                // detectDragGestures: the old
                                // pointerInput(rect) restarted
                                // the detector on every drag
                                // event, so a corner drag died
                                // after its first pixel and the
                                // rect never visibly moved (the
                                // reported "crop does nothing").
                                // The loop reads the live rect
                                // state each event instead.
                                // Target: nearest corner within
                                // 8% of the small side, else a
                                // move when inside and the rect
                                // has room to move. Anything
                                // else is never consumed, so
                                // the vertical scroll above
                                // takes the drag; a tap outside
                                // the rect does nothing.
                                awaitPointerEventScope {
                                    while (true) {
                                        val down =
                                            awaitFirstDown(requireUnconsumed = false)
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        if (w <= 0f || h <= 0f) continue
                                        val threshold =
                                            minOf(w, h) * 0.08f
                                        val fx = down.position.x / w
                                        val fy = down.position.y / h
                                        val corners = listOf(
                                            rect.left to rect.top,
                                            rect.right to rect.top,
                                            rect.left to rect.bottom,
                                            rect.right to rect.bottom
                                        )
                                        var target = corners.indexOfFirst { (cx, cy) ->
                                            val dx = (fx - cx) * w
                                            val dy = (fy - cy) * h
                                            dx * dx + dy * dy < threshold * threshold
                                        }
                                        if (target < 0 &&
                                            fx in rect.left..rect.right &&
                                            fy in rect.top..rect.bottom
                                        ) {
                                            target = if (rect.left > 0f ||
                                                rect.top > 0f ||
                                                rect.right < 1f ||
                                                rect.bottom < 1f
                                            ) 4 else -1
                                        }
                                        if (target < 0) {
                                            // Not a crop gesture:
                                            // drain without
                                            // consuming, so the
                                            // scroll handles it.
                                            var done = false
                                            while (!done) {
                                                val ev = awaitPointerEvent()
                                                if (ev.changes.none { it.pressed }) {
                                                    done = true
                                                }
                                            }
                                            continue
                                        }
                                        val min = 0.05f
                                        var active = true
                                        while (active) {
                                            val ev = awaitPointerEvent()
                                            val pressed =
                                                ev.changes.filter { it.pressed }
                                            if (pressed.isEmpty()) {
                                                active = false
                                                break
                                            }
                                            val change = pressed[0]
                                            val dragAmount =
                                                change.positionChange()
                                            if (dragAmount ==
                                                androidx.compose.ui.geometry.Offset.Zero
                                            ) continue
                                            val dx = dragAmount.x / w
                                            val dy = dragAmount.y / h
                                            var next = rect
                                            when (target) {
                                                0 -> next = next.copy(
                                                    left = (next.left + dx).coerceIn(0f, next.right - min),
                                                    top = (next.top + dy).coerceIn(0f, next.bottom - min)
                                                )
                                                1 -> next = next.copy(
                                                    right = (next.right + dx).coerceIn(next.left + min, 1f),
                                                    top = (next.top + dy).coerceIn(0f, next.bottom - min)
                                                )
                                                2 -> next = next.copy(
                                                    left = (next.left + dx).coerceIn(0f, next.right - min),
                                                    bottom = (next.bottom + dy).coerceIn(next.top + min, 1f)
                                                )
                                                3 -> next = next.copy(
                                                    right = (next.right + dx).coerceIn(next.left + min, 1f),
                                                    bottom = (next.bottom + dy).coerceIn(next.top + min, 1f)
                                                )
                                                else -> {
                                                    // Move: clamp
                                                    // the whole rect
                                                    // into the frame.
                                                    val shiftX = dx.coerceIn(
                                                        -next.left, 1f - next.right
                                                    )
                                                    val shiftY = dy.coerceIn(
                                                        -next.top, 1f - next.bottom
                                                    )
                                                    next = next.copy(
                                                        left = next.left + shiftX,
                                                        right = next.right + shiftX,
                                                        top = next.top + shiftY,
                                                        bottom = next.bottom + shiftY
                                                    )
                                                }
                                            }
                                            rect = next
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                            // MaterialTheme.colorScheme is a
                            // @Composable read: it must happen
                            // here, in the composable scope — not
                            // inside the Canvas lambda below,
                            // which is a DrawScope and cannot
                            // call composables (that was a
                            // compile error).
                            val cropPrimary =
                                MaterialTheme.colorScheme.primary
                            Canvas(Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val l = rect.left * w
                                val t = rect.top * h
                                val r = rect.right * w
                                val b = rect.bottom * h
                                val dim = Color.Black.copy(alpha = 0.55f)
                                drawRect(
                                    dim,
                                    androidx.compose.ui.geometry.Offset.Zero,
                                    androidx.compose.ui.geometry.Size(w, t)
                                )
                                drawRect(
                                    dim,
                                    androidx.compose.ui.geometry.Offset(0f, b),
                                    androidx.compose.ui.geometry.Size(w, h - b)
                                )
                                drawRect(
                                    dim,
                                    androidx.compose.ui.geometry.Offset.Zero,
                                    androidx.compose.ui.geometry.Size(l, h)
                                )
                                drawRect(
                                    dim,
                                    androidx.compose.ui.geometry.Offset(r, 0f),
                                    androidx.compose.ui.geometry.Size(w - r, h)
                                )
                                drawRect(
                                    Color.White,
                                    androidx.compose.ui.geometry.Offset(l, t),
                                    androidx.compose.ui.geometry.Size(r - l, b - t),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 2.dp.toPx()
                                    )
                                )
                                val handle = 14.dp.toPx()
                                listOf(
                                    l to t, r to t, l to b, r to b
                                ).forEach { (cx, cy) ->
                                    drawRect(
                                        cropPrimary,
                                        androidx.compose.ui.geometry.Offset(
                                            cx - handle / 2, cy - handle / 2
                                        ),
                                        androidx.compose.ui.geometry.Size(handle, handle)
                                    )
                                }
                            }
                        }
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
                Text(
                    text = stringResource(R.string.comic_crop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onApply(rect) }) {
                        Text(stringResource(R.string.comic_crop_apply))
                    }
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.comic_crop_reset))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

/**
 * A cheap page thumbnail (scrub preview, overview grid):
 * a sampled decode through the source's thumbnail path —
 * never the full pipeline — recycled when the cell leaves
 * composition. The dispose read is live on purpose (same
 * pattern as NativePageItem): the state object this closure
 * captures belongs to this remember instance, so a page
 * change disposes against the OLD instance's bitmap while
 * the effect restart above already recycled it.
 */
@Composable
internal fun PageThumbnail(
    source: NativePageSource?,
    page: Int,
    modifier: Modifier,
    contentScale: ContentScale
) {
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source, page) {
        val old = thumb
        thumb = null
        old?.recycle()
        if (source == null) return@LaunchedEffect
        thumb = withContext(Dispatchers.IO) {
            runCatching { source.thumbnail(page, 240) }.getOrNull()
        }
    }
    DisposableEffect(Unit) {
        onDispose { thumb?.recycle() }
    }
    val bmp = thumb
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier.background(Color(0xFF1E1E1E))
        )
    } else {
        Box(
            modifier = modifier.background(Color(0xFF1E1E1E)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

/**
 * One full-width choice row for the view settings sheet
 * (mode, fit, direction). The old sheet squeezed several
 * long Persian option labels into one horizontal row, and
 * they wrapped word-per-line; one option per row, with the
 * active one highlighted and ticked, cannot squeeze. A
 * disabled row keeps its tick if it is the current value,
 * dimmed like its label, so "current but not changeable
 * in this mode" still reads.
 */
@Composable
internal fun SheetChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: AppIconKind? = null,
    enabled: Boolean = true
) {
    val contentColor = if (enabled) {
        LocalContentColor.current
    } else {
        LocalContentColor.current.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            AppIcon(
                icon,
                tint = contentColor,
                size = 18.dp
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            AppIcon(
                AppIconKind.Check,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    contentColor
                },
                size = 18.dp
            )
        }
    }
}

/** Pixel budget for the software image steps (resize, sharpen):
 * above this, the transient IntArray copies would risk an OOM
 * on tall pages, so the native bitmap is kept for GPU display. */
private const val MAX_SOFTWARE_PIXELS = 8_000_000L

/**
 * An animated page (GIF) in a classic ImageView. Compose's Image
 * cannot play an AnimatedImageDrawable, so the drawable lives in
 * a View: update sets it and starts it, onRelease stops whatever
 * the view currently holds (robust against the drawable state
 * changing under it) and clears the view. The caller sizes the
 * view with the same modifiers as the bitmap path, so fit modes,
 * padding shrink and the unified-width slot behave identically —
 * FIT_CENTER draws the drawable into its aspect-correct box,
 * FIT_XY only for the stretch mode that ignores aspect.
 */
@Composable
private fun AnimatedPageImage(
    drawable: Drawable,
    modifier: Modifier,
    scaleType: ImageView.ScaleType,
    rotation: Float = 0f
) {
    AndroidView(
        factory = { context ->
            ImageView(context).apply { this.scaleType = scaleType }
        },
        update = { view ->
            view.rotation = rotation
            view.setImageDrawable(drawable)
            (drawable as? Animatable)?.start()
        },
        onRelease = { view ->
            (view.drawable as? Animatable)?.stop()
            view.setImageDrawable(null)
        },
        modifier = modifier.background(Color.White)
    )
}

/**
 * ComicScreen's page-return park (its pager adapter's drawable
 * map, fx1), as a bitmap cache for this viewer. A finished
 * bitmap is parked here when its item leaves composition, and
 * a new item for the same page and the same render parameters
 * takes it back instead of re-running the whole pipeline.
 *
 * This is NOT a shared bitmap: take() removes the bitmap from
 * the park, so at any moment exactly one live item owns it —
 * a parked bitmap is drawn by nobody, and the old rule that
 * forbade sharing a bitmap between two live items still holds
 * (a recycled bitmap being drawn is a crash, and this cache
 * can never produce one: eviction recycles only parked, i.e.
 * undrawn, bitmaps, and take() never returns a recycled one).
 * The park is bounded — 4 entries, and only bitmaps within
 * the software pixel budget park at all, so a giant tall page
 * keeps today's recycle-on-leave behaviour instead of
 * bloating the park — and it is main-thread-only by
 * construction: takes happen in remember initial values and
 * parks in onDispose, both on the main thread, while the
 * decode pipeline hands its finished bitmap over through
 * state. close() is the viewer's exit: it also makes any
 * late park (an item disposing after the viewer) recycle on
 * the spot instead of leaking into a dead cache.
 *
 * What it does NOT do, on purpose: it does not keep a disk
 * cache of extracted entries (ComicScreen's kp4 extraction
 * cache) — the zip stream decode is the cheap part here, and
 * the park already skips the expensive part (decode + rotate
 * + crop + resize + filters) entirely on a return — and it
 * does not park animated drawables, whose playback lifecycle
 * a bitmap does not have.
 */
internal class NativePageBitmapCache(
    private val maxEntries: Int = 4
) {
    /** Every render parameter the item's remember keys carry:
     * a parked bitmap is only valid for exactly this set. */
    data class Key(
        val page: Int,
        val renderWidthPx: Int,
        val cropMargin: Boolean,
        val cropPadH: Int,
        val cropPadV: Int,
        val fitMode: String,
        val sharpen: Boolean,
        val sharpenLevel: Int,
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        val halfSide: Int,
        val upscaleMode: String,
        val filterGray: Boolean,
        val filterContrast: Boolean,
        val filterInvert: Boolean,
        val viewRotation: Int,
        val horizontalStrip: Boolean,
        val manualCropRect: CropRect?
    )

    // Access-ordered, so the eldest entry is the least
    // recently returned-to page — the one a reader is least
    // likely to flip back to next.
    private val parked = object : LinkedHashMap<Key, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, Bitmap>
        ): Boolean {
            if (size > maxEntries) {
                eldest.value.takeUnless { it.isRecycled }?.recycle()
                return true
            }
            return false
        }
    }
    private var closed = false

    /** Take the parked bitmap for [key], if any. Ownership
     * moves to the caller: the bitmap leaves the park. */
    fun take(key: Key): Bitmap? {
        if (closed) return null
        val bitmap = parked.remove(key) ?: return null
        return if (bitmap.isRecycled) null else bitmap
    }

    /** Park [bitmap] under [key]. False means it was NOT
     * parked (closed cache, recycled bitmap, or over the pixel
     * budget) and the caller must recycle it itself. */
    fun park(key: Key, bitmap: Bitmap): Boolean {
        if (closed || bitmap.isRecycled) return false
        if (bitmap.width.toLong() * bitmap.height > MAX_SOFTWARE_PIXELS) return false
        parked[key] = bitmap
        return true
    }

    /** Free every parked bitmap and refuse later parks. */
    fun close() {
        if (closed) return
        closed = true
        parked.values.forEach { if (!it.isRecycled) it.recycle() }
        parked.clear()
    }
}

/**
 * One original page. The bitmap is owned by THIS item: rendered on
 * IO when the item is composed, recycled when it leaves
 * composition — unless the viewer's bitmap park holds it for a
 * return visit (see NativePageBitmapCache: take() moves
 * ownership to this item, and onDispose parks it back instead
 * of recycling, so going back to a page shows its finished
 * bitmap at once instead of re-rendering it from scratch).
 *
 * In vertical mode the strip is ComicScreen's: every page fills
 * the strip width, one below the other, no horizontal scroll —
 * zoom and pan live on the whole strip (pinch, see
 * ZoomablePageContainer and the strip detector): one shared
 * zoom for the whole strip — a pinch zooms every page together,
 * because the zoom changes the layout (each item is fit size x
 * the shared scale), so the pages stay stuck together.
 * In paged mode horizontal drag is the page turn at 1x; while
 * a page is zoomed the drag pans that page only and the pager
 * is locked, and the zoom resets when the displayed page
 * changes (see ZoomablePageContainer).
 */
/**
 * Padding shrink (ComicScreen's set_img_padding_ratio): the
 * displayed page shrinks by ratio * 1.25 — the 1.25
 * compensating a portrait screen so the inset looks even —
 * and the freed space becomes the margin. 0 means off. One
 * function so the item, the placeholder and the viewer's strip
 * detector all compute the same fraction.
 */
internal fun paddingShrink(paddingRatio: Float): Float =
    if (paddingRatio > 0f) (1f - 1.25f * paddingRatio).coerceAtLeast(0.5f) else 1f

@Composable
internal fun NativePageItem(
    source: NativePageSource,
    page: Int,
    renderWidthPx: Int,
    paged: Boolean,
    zoomFactor: Float = 1f,
    cropMargin: Boolean = false,
    cropPadH: Int = 0,
    cropPadV: Int = 0,
    paddingRatio: Float = 0f,
    pageBg: Color = Color(0xFF1E1E1E),
    screenWidthPx: Int = 0,
    screenHeightPx: Int = 0,
    fitMode: String = "width",
    sharpen: Boolean = false,
    sharpenLevel: Int = 0,
    // Upscale interpolation ("linear" / "cubic" / "lanczos",
    // default cubic = the behaviour before this setting).
    upscaleMode: String = "cubic",
    // ComicScreen's colour filters, all default off, applied
    // after sharpen, bitmap pages only.
    filterGray: Boolean = false,
    filterContrast: Boolean = false,
    filterInvert: Boolean = false,
    // View rotation, degrees clockwise (0/90/180/270). The
    // Rotate action cycles it; 180 also mirrors navigation
    // direction (see the viewer). Animated pages rotate via
    // the ImageView, bitmap pages in the pipeline below.
    viewRotation: Int = 0,
    // Horizontal strip: the page fills the strip HEIGHT (not
    // width) and the resize target is height-based. Paged is
    // always false with this on; it only changes the strip
    // geometry, never the page order.
    horizontalStrip: Boolean = false,
    // Manual crop rect, in FRACTIONS of the displayed page
    // (0..1, from the crop editor): when set it replaces the
    // auto-crop for this page — the user drew it by hand.
    // Null = no manual crop (auto-crop as before).
    manualCropRect: CropRect? = null,
    // Dual-page half: 0 = the whole page, 1 = its left half,
    // 2 = its right half (IMAGE coordinates — the pair lays the
    // halves out in reading order). A half item renders its
    // half at the slot width (half the screen), never the full
    // page squeezed into the slot.
    halfSide: Int = 0,
    // Tap on this page: the viewer passes handlePageTap, which
    // toggles the chrome after the 300ms double-tap window.
    // Reported by the passive listener on this item's outer
    // Box (see there) — the ONLY tap source now, so a tap
    // can never fire twice. A double-tap zoom is reported
    // separately (onDoubleTap): the viewer drops the pending
    // toggle ONLY for a confirmed double-tap, never for a
    // second tap on its own (see the viewer note).
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    // Per-page-box finger zoom (see ZoomablePageContainer):
    // a paged box remembers its own zoom state, and a strip
    // box renders the ONE shared strip state the viewer owns
    // for the whole strip (passed in below as
    // externalZoomState, with externalSettledZoom publishing
    // the gesture-end scale for the sharp re-render
    // threshold) — the one-canvas model (2026-11-05): the zoom
    // changes the layout, so every page zooms together and the
    // pages stay stuck together. The viewer passes the
    // identity keys below: sessionKey drops the state when the
    // reader's content changes, slotKey/activeSlotKey drive
    // the paged reset (both null in strips, where no
    // auto-reset applies), zoomPageId labels the box inside
    // this session. Defaults keep old callers compiling.
    sessionKey: Any? = null,
    slotKey: Int? = null,
    activeSlotKey: Int? = null,
    zoomPageId: String = "",
    // Strip finger zoom (see ZoomablePageContainer): the viewer
    // passes the ONE shared strip state (one scale + one shared
    // cross-axis pan for the whole strip — the one-canvas model,
    // 2026-11-05). Null = per-box zoom (paged mode, unchanged).
    // externalSettledZoom publishes the strip's gesture-end
    // scale (see onPinchScale below): one settled scale for all
    // pages, so every visible page crosses the sharp re-render
    // threshold together.
    externalZoomState: MutableState<PageZoomState>? = null,
    externalSettledZoom: MutableFloatState? = null,
    // One-canvas strip host (see ZoomableImage.StripZoomHost):
    // the viewer passes its strip host here; the strip
    // containers below hand it to ZoomablePageContainer. Null
    // in paged mode.
    stripHost: StripZoomHost? = null,
    // The viewer's page-return park (see
    // NativePageBitmapCache). Null = today's behaviour exactly:
    // render on compose, recycle on dispose, no parking.
    bitmapCache: NativePageBitmapCache? = null
) {
    // One-canvas strip zoom (2026-11-05): the shared strip
    // scale, read here so the strip branches below lay this
    // item out at fit size x the scale. Paged mode passes no
    // external state, so this stays 1 there. Reading the state
    // subscribes this item to scale changes: a pinch frame
    // recomposes the visible items (layout only — the bitmap
    // is remembered, never re-decoded mid-pinch; the sharp
    // re-render still runs once, at gesture end).
    val stripScale = externalZoomState?.value?.scale ?: 1f
    // The park key: exactly the remember keys below, so a
    // parked bitmap is only ever reused for the identical
    // render. A mode switch composes the new item before the
    // old one disposes, so a switch can miss the park (the old
    // bitmap parks a moment too late) — a wasted re-render
    // once, never a bitmap shared between two live items.
    val cacheKey = NativePageBitmapCache.Key(
        page, renderWidthPx, cropMargin, cropPadH, cropPadV,
        fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx,
        halfSide, upscaleMode, filterGray, filterContrast, filterInvert,
        viewRotation, horizontalStrip, manualCropRect
    )
    var bitmap by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableStateOf(bitmapCache?.take(cacheKey)) }
    // True only on a park hit: the first effect run for this
    // item instance must then skip the pipeline — the bitmap
    // IS the finished render. Every later run (a zoom
    // re-render, a retry) must decode again: a zoom re-render
    // that skipped would freeze the page at 1x, and a retry
    // that skipped would re-show the failure. The flag is
    // keyed like the bitmap, so a parameter change gets a
    // fresh flag with its fresh bitmap state.
    var skipFirstRender by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableStateOf(bitmap != null) }
    // An animated drawable (GIF / animated WebP pages only).
    // When one is present the bitmap path is skipped entirely:
    // the drawable IS the page, frames and all — a bitmap would
    // freeze it on frame one, which is what the viewer did
    // before this change.
    var drawable by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableStateOf<Drawable?>(null) }
    var failed by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableStateOf(false) }
    // The render error itself, shown under the failure text: the
    // old code threw it away (getOrNull), so a phone-only tester
    // could only ever report "it failed" and the cause stayed
    // invisible. The message names the entry and the reason
    // (unreadable image, missing entry, unsupported compression),
    // which is what a screenshot of this screen needs to carry.
    var failMessage by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableStateOf<String?>(null) }
    var retry by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableIntStateOf(0) }
    // Zoom re-render (ComicScreen's tile refinement,
    // coarse-grained): the pipeline sizes the bitmap to the
    // display width, so a 2x pinch would otherwise just upscale
    // that bitmap on the GPU and go soft. When the pinch passes
    // 1.75x the page re-renders at 2x display width (bicubic
    // from the native pixels, not from the 1x bitmap), and when
    // it falls back under 1.25x it re-renders at 1x. The
    // hysteresis stops flapping at the threshold. renderScale
    // is deliberately NOT in the bitmap/drawables' remember
    // keys: a re-render must swap the bitmap under the live
    // zoom container (which keeps its scale), not dispose the
    // container and snap the page back to 1x mid-pinch.
    var renderScale by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableIntStateOf(1) }
    // Threshold-guarded: the container reports only the
    // settled scale (once per gesture), and only a threshold
    // crossing re-renders — so a re-render never runs
    // mid-pinch and stalls the gesture. The settled scale
    // stays inside THIS item: there is no global zoom to
    // report to anymore, so zooming one page never moves
    // another page's render.
    val onPinchScale: (Float) -> Unit = { s ->
        if (s > 1.75f && renderScale != 2) {
            renderScale = 2
        } else if (s < 1.25f && renderScale != 1) {
            renderScale = 1
        }
        // Strip: publish the settled scale (this callback only
        // ever fires at gesture end, never mid-pinch) to the
        // ONE shared settled holder, so a page that only just
        // composed while already zoomed decodes sharp from the
        // start instead of staying soft until first touched —
        // and so every visible page crosses the sharp
        // re-render threshold together. Guarded against
        // no-change writes, so the observing effect below can
        // never loop back here.
        externalSettledZoom?.let { if (it.floatValue != s) it.floatValue = s }
    }
    // The strip's settled scale (one shared holder): when a
    // strip pinch ends, every item crosses the sharp re-render
    // threshold together — including a page that only just
    // composed while already zoomed, which then decodes sharp
    // from the start instead of staying soft until first
    // touched. Keyed on the settled holder only, so a mid-pinch
    // scale never re-renders anything.
    val settledPageScale = externalSettledZoom?.floatValue
    LaunchedEffect(settledPageScale) {
        if (settledPageScale != null) onPinchScale(settledPageScale)
    }
    // Known page ratio (width / height) from the source's
    // bounds-only size — ComicScreen's dimension prefetch:
    // CBZ reads image headers, PDF reads PDFium points, both
    // without decoding pixels, so the strip placeholder can
    // already be the page's real height before the bitmap
    // arrives. Without it the placeholder is a fixed 200dp
    // and every page jumps to its real height when its
    // bitmap lands — the fling-breaking jump in fast scroll.
    // Null until the query returns (or the source has no
    // size), in which case the old 200dp placeholder stands.
    // View rotation swaps it below (90/270), crop does not:
    // a cropped page's ratio is only known after the crop,
    // so a cropped page still jumps once — crop is opt-in,
    // and the uncropped ratio is still closer than 200dp.
    var knownRatio by remember(page) { mutableStateOf<Float?>(null) }
    LaunchedEffect(page) {
        knownRatio = withContext(Dispatchers.IO) {
            runCatching { source.nativePageSize(page) }.getOrNull()
                ?.takeIf { it.first > 0 && it.second > 0 }
                ?.let { it.first.toFloat() / it.second.toFloat() }
        }
    }

    LaunchedEffect(page, renderWidthPx, retry, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect, renderScale) {
        // No bitmap/drawable nulling here on purpose: every key
        // above except renderScale is also a remember key, so a
        // page/parameter change already resets the states to
        // null through remember — and a renderScale change must
        // keep the old bitmap on screen until the sharper one
        // arrives, or the zoom container would dispose mid-pinch
        // (see renderScale). Only the failure flags reset: a
        // re-render that fails must not leave a stale failure
        // screen over a still-valid old bitmap (the branch below
        // shows the bitmap before the failure, so the old page
        // simply stays until its replacement arrives).
        failed = false
        failMessage = null
        // Park hit: this item already holds its finished
        // bitmap (taken from the cache in remember above), so
        // there is nothing to render — and running the
        // pipeline anyway would throw the parked bitmap away
        // and re-decode it, which is exactly the bug this
        // cache fixes. Consumed once: later runs of this
        // effect (zoom re-render, retry) must decode.
        if (skipFirstRender) {
            skipFirstRender = false
            return@LaunchedEffect
        }
        // The bitmap this run replaces, recycled once its
        // replacement lands (not in onDispose: the dispose
        // effect below is keyed without renderScale, so it
        // would otherwise recycle the replacement too).
        val previousBitmap = bitmap
        // Animated path first: for a GIF page the drawable
        // replaces the bitmap path wholesale. For every other
        // format it returns null immediately (extension check)
        // and the bitmap path below runs exactly as before.
        // A drawable failure also falls through to the bitmap
        // path, so a corrupt GIF still shows its first frame
        // instead of a failure screen.
        val animated = withContext(Dispatchers.IO) {
            runCatching { source.animatedDrawable(page) }.getOrNull()
        }
        if (animated != null) {
            drawable = animated
            return@LaunchedEffect
        }
        val rendered = withContext(Dispatchers.IO) {
            runCatching {
                // The pipeline mirrors ComicScreen's per-page
                // filter order — view rotation first (so crop
                // detection sees the page as displayed), then
                // crop, upscale, sharpen, colour filters.
                // Each step replaces the bitmap and recycles the
                // previous one, so only one full-size bitmap per
                // item is ever alive.
                var current = source.renderPage(page, renderWidthPx)
                if (viewRotation != 0) {
                    val rotated = rotateBitmap(current, viewRotation)
                    if (rotated !== current) {
                        current.recycle()
                        current = rotated
                    }
                }
                if (manualCropRect != null) {
                    // Manual crop wins over auto-crop for this
                    // page: the rect is fractions of the
                    // (already rotated) page, from the editor.
                    val l = (manualCropRect.left * current.width).toInt()
                        .coerceIn(0, current.width - 1)
                    val t = (manualCropRect.top * current.height).toInt()
                        .coerceIn(0, current.height - 1)
                    val r = (manualCropRect.right * current.width).toInt()
                        .coerceIn(l + 1, current.width)
                    val b = (manualCropRect.bottom * current.height).toInt()
                        .coerceIn(t + 1, current.height)
                    if (r > l && b > t) {
                        val cropped = Bitmap.createBitmap(current, l, t, r - l, b - t)
                        current.recycle()
                        current = cropped
                    }
                } else if (cropMargin) {
                    val rect = detectContentRect(current, cropPadH, cropPadV)
                    if (rect != null) {
                        // createBitmap copies: the uncropped
                        // original must go now, or every cropped
                        // page leaks a full-size bitmap beside
                        // its crop.
                        val cropped = Bitmap.createBitmap(
                            current, rect.left, rect.top,
                            rect.width(), rect.height()
                        )
                        current.recycle()
                        current = cropped
                    }
                }
                // Resize to display size. Vertical mode sizes
                // every page to the screen width (ComicScreen's
                // vertical strip), so it resizes exactly like
                // paged fit-width; paged fit-height resizes to
                // the screen height; original/stretch never
                // resample (original is native by definition,
                // stretch is a GPU fill).
                // Smaller than display: bicubic upscale (a small
                // page stretched by the GPU goes soft). Larger
                // than display: area-average downscale — the GPU
                // downscales with plain bilinear filtering and no
                // mipmaps, which drops thin lines on line art,
                // and that display downscale is format-blind, so
                // it was the remaining softness for PNG/WebP/GIF
                // (their decodes are already pixel-exact; the
                // quality decode flag only changes JPEG).
                // Padding's shrink is ignored here on purpose:
                // resizing to the full screen size and letting
                // the GPU shave the padding off is still far
                // sharper than resampling from the wrong size,
                // and it keeps the padding slider from
                // re-rendering on every drag.
                // MEMORY BUDGET: every software step below
                // materializes 2-3 full-size IntArray copies
                // beside the bitmap itself. On a tall manhwa
                // strip (1200x12000 = 14M pixels) that is
                // ~170MB transient on top of the pager's
                // preloaded neighbours — enough to OOM the
                // heap, and runCatching turns the OOM into a
                // failed page instead of an image. So software
                // steps run only up to 8M pixels; bigger pages
                // keep the native bitmap for the GPU path, which
                // is exactly what the build before this change
                // did. The budget is re-checked per step, on
                // the current size: a page resized down first
                // is small by the time sharpen runs.
                // Display width for this item: the screen width
                // times the zoom re-render scale (1x, or 2x once
                // the pinch passes 1.75x), halved for a dual-page
                // half item — its slot is half the screen, and
                // resizing to the full screen would only make
                // the GPU downscale it back in the slot.
                val displayWidthPx = (screenWidthPx * renderScale)
                    .let { if (halfSide != 0) it / 2 else it }
                val resizeTarget: Pair<Int, Int>? = when {
                    // Horizontal strip: the display size is the
                    // strip HEIGHT (screen height x re-render
                    // scale), width follows the aspect — the
                    // mirror of the vertical strip's width-based
                    // target below. Checked first: a strip item
                    // is paged=false, so it would otherwise
                    // fall into the vertical branch.
                    horizontalStrip && screenHeightPx > 0 -> {
                        val displayHeightPx = screenHeightPx * renderScale
                        (displayHeightPx * current.width / current.height)
                            .coerceAtLeast(1) to displayHeightPx
                    }
                    !paged && displayWidthPx > 0 ->
                        displayWidthPx to
                            (displayWidthPx * current.height / current.width)
                                .coerceAtLeast(1)
                    paged && fitMode == "width" && displayWidthPx > 0 ->
                        displayWidthPx to
                            (displayWidthPx * current.height / current.width)
                                .coerceAtLeast(1)
                    paged && fitMode == "height" && screenHeightPx > 0 ->
                        (screenHeightPx * current.width / current.height)
                            .coerceAtLeast(1) to screenHeightPx
                    else -> null
                }
                if (resizeTarget != null) {
                    val (targetW, targetH) = resizeTarget
                    // TARGET-side budget, added after a real
                    // hole was found: the old check only looked
                    // at the current size, so a small page
                    // zoomed to 2x (3M px native -> 23M px
                    // target on a tall strip) passed it and
                    // materialized a 90MB+ bitmap with its
                    // transient copies — an OOM on a phone.
                    // Oversized targets keep the native bitmap
                    // for the GPU path, exactly like oversized
                    // sources.
                    if (current.width.toLong() * current.height <= MAX_SOFTWARE_PIXELS &&
                        targetW.toLong() * targetH <= MAX_SOFTWARE_PIXELS &&
                        current.width != targetW
                    ) {
                        val resized = if (current.width < targetW) {
                            upscaleByMode(current, targetW, targetH, upscaleMode)
                        } else {
                            resizeBox(current, targetW, targetH)
                        }
                        if (resized !== current) {
                            current.recycle()
                            current = resized
                        }
                    }
                }
                if (sharpen && sharpenLevel > 0 &&
                    current.width.toLong() * current.height <= MAX_SOFTWARE_PIXELS
                ) {
                    val sharp = sharpenUnsharp(current, sharpenLevel)
                    if (sharp !== current) {
                        current.recycle()
                        current = sharp
                    }
                }
                // ComicScreen's colour filters, after sharpen:
                // grayscale, contrast (histogram equalisation
                // on luma), invert. Same 8M budget as sharpen;
                // animated pages skip them (they have no
                // bitmap to filter — the drawable branch above
                // never reaches this pipeline).
                if (filterGray &&
                    current.width.toLong() * current.height <= MAX_SOFTWARE_PIXELS
                ) {
                    val gray = grayscale(current)
                    if (gray !== current) {
                        current.recycle()
                        current = gray
                    }
                }
                if (filterContrast &&
                    current.width.toLong() * current.height <= MAX_SOFTWARE_PIXELS
                ) {
                    val contrasted = contrastHistEq(current)
                    if (contrasted !== current) {
                        current.recycle()
                        current = contrasted
                    }
                }
                if (filterInvert &&
                    current.width.toLong() * current.height <= MAX_SOFTWARE_PIXELS
                ) {
                    val inverted = invertColors(current)
                    if (inverted !== current) {
                        current.recycle()
                        current = inverted
                    }
                }
                // Dual-page half, LAST: crop/resize/sharpen all
                // ran on the whole page (they need the full
                // image), and only the finished page is halved.
                // 1 = left half, 2 = right half, in IMAGE
                // coordinates — the pair lays them out in
                // reading order, so this never needs to know the
                // direction.
                if (halfSide != 0) {
                    val halfW = (current.width / 2).coerceAtLeast(1)
                    val x = if (halfSide == 1) 0 else halfW
                    val w = if (halfSide == 1) {
                        halfW
                    } else {
                        (current.width - halfW).coerceAtLeast(1)
                    }
                    val half = Bitmap.createBitmap(current, x, 0, w, current.height)
                    if (half !== current) {
                        current.recycle()
                        current = half
                    }
                }
                current
            }
        }
        rendered.onSuccess {
            bitmap = it
            // A re-render (zoom scale change) replaces a live
            // bitmap: the replaced one goes now, or every zoom
            // re-render leaks a full-size bitmap beside its
            // replacement. The dispose effect below is keyed
            // without renderScale, so it never fires on a
            // re-render — this is the only place the replaced
            // bitmap is freed, and the dispose effect frees the
            // final one.
            if (previousBitmap != null && previousBitmap !== it) {
                previousBitmap.recycle()
            }
        }
        rendered.onFailure {
            failed = true
            failMessage = it.message ?: it.javaClass.simpleName
        }
    }
    // Hand the bitmap this item owns back when it leaves
    // composition: park it in the viewer's cache for a return
    // visit, or recycle it when there is no park (or the park
    // refuses it — closed viewer, oversized bitmap). Only a
    // 1x bitmap parks: a 2x zoom re-render is a zoom artefact,
    // not the page's base render, and parking it would serve
    // a double-size bitmap as the page's next 1x view. The
    // read is live on purpose: the state object this closure
    // captures belongs to this remember instance, so a key
    // change (new page/parameters) disposes against the OLD
    // instance's bitmap under the OLD key, while a zoom
    // re-render — which is NOT a key of this effect — swaps
    // the bitmap without disposing, and the dispose then
    // parks or frees the final bitmap, not a stale one (the
    // swapped-out ones were freed in onSuccess above).
    // Reading state in onDispose is safe here precisely
    // because the state object, not the value, is what the
    // closure holds.
    DisposableEffect(page, renderWidthPx, retry, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) {
        onDispose {
            val owned = bitmap
            if (owned != null && !owned.isRecycled) {
                val parked = bitmapCache != null && renderScale == 1 &&
                    bitmapCache.park(cacheKey, owned)
                if (!parked) owned.recycle()
            }
            (drawable as? Animatable)?.stop()
        }
    }

    val density = LocalDensity.current
    // Standard tap surface (2026-11-02): a plain
    // clickable on the page item itself — the standard
    // Compose tap handler for an item inside a pager/list,
    // no raw pointerInput loop, no consumption check.
    // A drag cancels the click, so swipe/scroll are
    // untouched; a tap fires onTap, which the viewer
    // delays 300ms and a confirmed double-tap suppresses.
    // Zoomable pages report their tap from the Zoomable
    // loop as well; handlePageTap ignores a second report
    // while a toggle is pending, so a tap that reaches
    // both surfaces is still a single toggle.
    Box(
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) { onTap() }
    ) {
    when {
        drawable != null -> {
            // Animated page: the drawable plays as-is. Crop,
            // resize and sharpen are bitmap operations and do
            // not apply here — cropping an animation would mean
            // re-encoding the GIF frame by frame, which is out
            // of scope; the page is shown whole, in the same fit
            // modes and padding as a still page.
            val anim = drawable!!
            val ratio = if (anim.intrinsicWidth > 0 && anim.intrinsicHeight > 0) {
                anim.intrinsicWidth.toFloat() / anim.intrinsicHeight.toFloat()
            } else {
                1f
            }
            val shrink = paddingShrink(paddingRatio)
            if (paged) {
                when (fitMode) {
                    "height" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedPageImage(
                                rotation = viewRotation.toFloat(),
                                drawable = anim,
                                scaleType = ImageView.ScaleType.FIT_CENTER,
                                modifier = Modifier
                                    .fillMaxHeight(shrink)
                                    .aspectRatio(ratio)
                            )
                        }
                    }
                    "original" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val nativeWidthDp = with(density) {
                                    (anim.intrinsicWidth * zoomFactor * shrink)
                                        .toInt().toDp()
                                }
                                AnimatedPageImage(
                                    rotation = viewRotation.toFloat(),
                                    drawable = anim,
                                    scaleType = ImageView.ScaleType.FIT_CENTER,
                                    modifier = Modifier
                                        .width(nativeWidthDp)
                                        .aspectRatio(ratio)
                                )
                            }
                        }
                    }
                    "stretch" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedPageImage(
                                rotation = viewRotation.toFloat(),
                                drawable = anim,
                                scaleType = ImageView.ScaleType.FIT_XY,
                                modifier = Modifier.fillMaxSize(shrink)
                            )
                        }
                    }
                    else -> {
                        // Same top-stick fix as the bitmap
                        // fit-width branch below: a short
                        // animated page centers in a
                        // viewport-tall Box, a tall one still
                        // scrolls inside its page.
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Captured here, not inside the
                            // Column: the Column's own scope
                            // shadows the constraints scope, so
                            // maxHeight is not visible in there.
                            val viewportHeight = maxHeight
                            Column(
                                modifier = Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .background(pageBg),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = viewportHeight),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AnimatedPageImage(
                                        rotation = viewRotation.toFloat(),
                                        drawable = anim,
                                        scaleType = ImageView.ScaleType.FIT_CENTER,
                                        modifier = Modifier
                                            .fillMaxWidth(shrink)
                                            .aspectRatio(ratio)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Vertical mode, ComicScreen's strip: the page
                // fills the strip width, pages stack one below
                // the other, no horizontal scroll at all. Zoom
                // and pan live on the page itself (see
                // ZoomablePageContainer): the sheet's zoom steps
                // no longer change this display, pinch does.
                ZoomablePageContainer(
                    // Horizontal strip: fill the strip height
                    // instead of the width (see horizontalStrip).
                    // One-canvas: the size is the fit size x
                    // the shared strip scale, so the zoomed
                    // strip stays one continuous box.
                    modifier = if (horizontalStrip) {
                        Modifier.fillMaxHeight(shrink * stripScale).aspectRatio(ratio)
                    } else {
                        Modifier.fillMaxWidth(shrink * stripScale).aspectRatio(ratio)
                    },
                    // Animated pages have no bitmap re-render
                    // to trigger, so the settled scale is
                    // reported nowhere: the zoom lives and dies
                    // inside this box (see ZoomablePageContainer).
                    sessionKey = sessionKey,
                    zoomPageId = zoomPageId,
                    externalZoomState = externalZoomState,
                    stripHost = stripHost,
                    stripPageIndex = page,
                    onScaleChange = {},
                    onDoubleTap = onDoubleTap,
                    onTap = onTap,
                ) {
                    AnimatedPageImage(
                        rotation = viewRotation.toFloat(),
                        drawable = anim,
                        scaleType = ImageView.ScaleType.FIT_CENTER,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        bitmap != null -> {
            val bmp = bitmap!!
            val ratio = bmp.width.toFloat() / bmp.height.toFloat()
            // Padding ratio (ComicScreen's set_img_padding_ratio):
            // the displayed page shrinks — horizontally by
            // ratio * 1.25, the 1.25 compensating a portrait
            // screen so the inset looks even — and the freed
            // space becomes the margin. Aspect is preserved:
            // the width drives, the height follows the ratio.
            // 0 means off, exactly the old behaviour.
            val shrink = paddingShrink(paddingRatio)
            if (paged) {
                // Page fit (ComicScreen's fitscreen menu). Width
                // is the old behaviour; height sizes the page to
                // the viewport height and pans sideways when the
                // page is wider; original shows native pixels
                // (times the zoom factor) and pans both ways;
                // stretch fills the screen and ignores aspect.
                // Zoom is deliberately ignored by width/height/
                // stretch: those modes size the page to the
                // screen, so a zoom factor would only fight the
                // fit. Original is the mode where zoom means
                // something, and it applies there.
                when (fitMode) {
                    "height" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxHeight(shrink)
                                    .aspectRatio(ratio)
                                    .background(Color.White)
                            )
                        }
                    }
                    "original" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val nativeWidthDp = with(density) {
                                    (bmp.width * zoomFactor * shrink).toInt().toDp()
                                }
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .width(nativeWidthDp)
                                        .aspectRatio(ratio)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                    "stretch" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .fillMaxSize(shrink)
                                    .background(Color.White)
                            )
                        }
                    }
                    else -> {
                        // Fit-width, now with the same pinch
                        // zoom the strip has (ComicScreen's
                        // PhotoView per page): before this, a
                        // paged page could only zoom through
                        // the sheet stepper in original mode,
                        // and width mode ignored zoom entirely.
                        // The pager lock is the container's
                        // edgeHandoff = false: at 1x a drag is
                        // never consumed, so the pager turns;
                        // while zoomed every drag pans this
                        // page — even at the pan edge — so the
                        // pager can never turn from a pan, and
                        // the zoom resets when the displayed
                        // page changes (see
                        // ZoomablePageContainer). The
                        // Column's vertical scroll is the tall
                        // page's own scroll (ComicScreen wraps
                        // an over-tall page in its own vertical
                        // ScrollView inside the pager), so a
                        // tall page is never cut in paged mode
                        // either: it scrolls vertically inside
                        // its page, exactly like the strip.
                        // But a scrollable Column always lays
                        // its content out from the TOP — so a
                        // page shorter than the viewport used
                        // to stick to the top of the screen
                        // instead of sitting in the middle, the
                        // bug users see on short pages. The
                        // inner Box fixes it without giving up
                        // the scroll: its minimum height is the
                        // viewport height, so when the page is
                        // shorter the Box is viewport-tall and
                        // centers the page inside it, and when
                        // the page is taller the Box is
                        // page-tall and the scroll works exactly
                        // as before. The height/ original/
                        // stretch branches above already center
                        // through their outer Box; only this
                        // fit-width branch needed the fix.
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Captured here, not inside the
                            // Column: the Column's own scope
                            // shadows the constraints scope, so
                            // maxHeight is not visible in there.
                            val viewportHeight = maxHeight
                            Column(
                                modifier = Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .background(pageBg),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = viewportHeight),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ZoomablePageContainer(
                                        modifier = Modifier
                                            .fillMaxWidth(shrink)
                                            .aspectRatio(ratio),
                                        sessionKey = sessionKey,
                                        slotKey = slotKey,
                                        activeSlotKey = activeSlotKey,
                                        zoomPageId = zoomPageId,
                                        // Paged: no edge handover —
                                        // while zoomed every drag
                                        // pans this page, and the
                                        // pager never turns from
                                        // a pan (see
                                        // ZoomablePageContainer).
                                        edgeHandoff = false,
                                        onScaleChange = onPinchScale,
                                        onDoubleTap = onDoubleTap,
                                        onTap = onTap,
                                    ) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.White)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Vertical mode, ComicScreen's strip: the page
                // fills the strip width, pages stack one below
                // the other, no horizontal scroll at all. Zoom
                // and pan live on the page itself (see
                // ZoomablePageContainer): the sheet's zoom steps
                // no longer change this display, pinch does.
                // The bitmap was already resized to the screen
                // width by the pipeline above, so fillMaxWidth
                // draws it 1:1 (up to the padding shrink).
                ZoomablePageContainer(
                    // Horizontal strip: fill the strip height
                    // instead of the width (see horizontalStrip).
                    // One-canvas: the size is the fit size x
                    // the shared strip scale, so the zoomed
                    // strip stays one continuous box.
                    modifier = if (horizontalStrip) {
                        Modifier.fillMaxHeight(shrink * stripScale).aspectRatio(ratio)
                    } else {
                        Modifier.fillMaxWidth(shrink * stripScale).aspectRatio(ratio)
                    },
                    sessionKey = sessionKey,
                    zoomPageId = zoomPageId,
                    externalZoomState = externalZoomState,
                    stripHost = stripHost,
                    stripPageIndex = page,
                    // One-canvas: the main-axis drag is never
                    // consumed, so the list scrolls (that IS
                    // the pan); no auto-reset applies (slot
                    // keys are null).
                    edgeHandoff = true,
                    onScaleChange = onPinchScale,
                    onDoubleTap = onDoubleTap,
                    onTap = onTap,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                            .background(Color.White)
                    )
                }
            }
        }
        failed -> {
            // The placeholder sits on a WHITE background, so its
            // text must be dark explicitly: with the app's dark
            // reader theme the default onSurface colour is light,
            // which used to render this message almost invisible
            // (the screenshot that reported this bug showed it as
            // faint grey on white).
            if (paged) {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.White),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.pdf_page_failed),
                        textAlign = TextAlign.Center,
                        color = Color(0xFF1E1E1E)
                    )
                    failMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            textAlign = TextAlign.Center,
                            color = Color(0xFF555555),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { retry++ }) {
                        Text(stringResource(R.string.pdf_retry))
                    }
                }
            } else {
                Column(
                    modifier = (if (horizontalStrip) Modifier.fillMaxHeight(stripScale).width(200.dp * stripScale) else Modifier.fillMaxWidth(stripScale).height(200.dp * stripScale))
                        .background(Color.White),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.pdf_page_failed),
                        textAlign = TextAlign.Center,
                        color = Color(0xFF1E1E1E)
                    )
                    failMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            textAlign = TextAlign.Center,
                            color = Color(0xFF555555),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { retry++ }) {
                        Text(stringResource(R.string.pdf_retry))
                    }
                }
            }
        }
        else -> {
            // Loading placeholder: when the page ratio is
            // already known (see knownRatio), the placeholder
            // is the page's real size — same shrink, same
            // aspect as the bitmap branch — so the item does
            // NOT jump when the bitmap lands. That jump was
            // the fast-scroll break: every page arriving
            // shifted the list and killed the fling. When the
            // ratio is not known yet, the old fixed 200dp
            // placeholder stands, and the item jumps once,
            // exactly as before.
            val effectiveRatio = knownRatio?.let {
                if (viewRotation == 90 || viewRotation == 270) 1f / it else it
            }
            val placeholderShrink = paddingShrink(paddingRatio)
            Box(
                modifier = (if (paged) {
                    Modifier.fillMaxSize()
                } else if (horizontalStrip) {
                    if (effectiveRatio != null) {
                        Modifier.fillMaxHeight(placeholderShrink * stripScale)
                            .aspectRatio(effectiveRatio)
                    } else {
                        Modifier.fillMaxHeight(stripScale).width(200.dp * stripScale)
                    }
                } else {
                    if (effectiveRatio != null) {
                        Modifier.fillMaxWidth(placeholderShrink * stripScale)
                            .aspectRatio(effectiveRatio)
                    } else {
                        Modifier.fillMaxWidth(stripScale).height(200.dp * stripScale)
                    }
                }),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
    }
}
