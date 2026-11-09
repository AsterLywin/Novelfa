package com.novelfa.reader.library

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.novelfa.reader.R
import com.novelfa.reader.domain.Book
import com.novelfa.reader.domain.isCompleted
import com.novelfa.reader.domain.percentDisplay
import com.novelfa.reader.domain.progressPercent
import androidx.compose.material3.IconButton
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind
import com.novelfa.reader.theme.StarRow
import java.io.File
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.PaddingValues
import com.novelfa.reader.theme.EmblemWatermark
import com.novelfa.reader.theme.LocalAppPaletteExtras
import com.novelfa.reader.theme.StitchDiagonal
import com.novelfa.reader.theme.StitchDiagonalLarge
import com.novelfa.reader.theme.StitchSeam
import com.novelfa.reader.theme.StitchStrip

/**
 * Cover request with a cache-busting signature (same pattern as
 * the avatar): the cover file is overwritten in place when the
 * backfill replaces a generated cover with the real one, so a
 * path-keyed request would keep showing the generated bitmap.
 * The signature (file mtime + length, plus the path itself)
 * changes on every rewrite, forcing a fresh load; it also
 * recomposes the card, since the path string alone never
 * changes on an in-place replacement.
 */
@Composable
private fun coverImageRequest(coverPath: String?): ImageRequest {
    val signature = coverPath?.let { File(it) }
        ?.takeIf { it.isFile }?.let { "${it.lastModified()}-${it.length()}" } ?: ""
    return ImageRequest.Builder(LocalContext.current)
        .data(coverPath)
        .memoryCacheKey("cover-$coverPath-$signature")
        .diskCacheKey("cover-$coverPath-$signature")
        .build()
}

internal fun LazyGridScope.libraryHeaderSpan(
    profileName: String,
    rankTitle: String,
    rankStarFill: Float,
    bookCount: Int,
    avatarPath: String,
    avatarSignature: String,
    onAvatarClick: () -> Unit,
    onRename: () -> Unit,
    onRankClick: () -> Unit,
    total: Int,
    reading: Int,
    done: Int,
    fav: Int,
    hero: Book?,
    allBooks: List<Book>,
    onOpenBook: (String) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    gridView: Boolean,
    onView: (Boolean) -> Unit,
    tab: Tab,
    onTab: (Tab) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    useFileNameTitle: Boolean = false
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        ProfileHeader(
            name = profileName.ifBlank { "…" },
            rankTitle = rankTitle,
            rankStarFill = rankStarFill,
            bookCount = bookCount,
            avatarPath = avatarPath,
            avatarSignature = avatarSignature,
            onAvatarClick = onAvatarClick,
            onRename = onRename,
            onRankClick = onRankClick
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        GreetingHeader()
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        StatsStrip(total = total, reading = reading, done = done, fav = fav, onPick = onTab)
    }
    if (hero != null) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HeroCard(
                book = hero,
                title = displayTitle(hero, allBooks, useFileNameTitle),
                onContinue = { onOpenBook(hero.id) }
            )
        }
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        SortViewRow(sort = sort, onSort = onSort, gridView = gridView, onView = onView)
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        LibraryTabs(tab = tab, onTab = onTab)
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.search_books)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

internal fun LazyListScope.libraryHeaderList(
    profileName: String,
    rankTitle: String,
    rankStarFill: Float,
    bookCount: Int,
    avatarPath: String,
    avatarSignature: String,
    onAvatarClick: () -> Unit,
    onRename: () -> Unit,
    onRankClick: () -> Unit,
    total: Int,
    reading: Int,
    done: Int,
    fav: Int,
    hero: Book?,
    allBooks: List<Book>,
    onOpenBook: (String) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    gridView: Boolean,
    onView: (Boolean) -> Unit,
    tab: Tab,
    onTab: (Tab) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    useFileNameTitle: Boolean = false
) {
    item {
        ProfileHeader(
            name = profileName.ifBlank { "…" },
            rankTitle = rankTitle,
            rankStarFill = rankStarFill,
            bookCount = bookCount,
            avatarPath = avatarPath,
            avatarSignature = avatarSignature,
            onAvatarClick = onAvatarClick,
            onRename = onRename,
            onRankClick = onRankClick
        )
    }
    item {
        GreetingHeader()
    }
    item { StatsStrip(total = total, reading = reading, done = done, fav = fav, onPick = onTab) }
    if (hero != null) {
        item {
            HeroCard(
                book = hero,
                title = displayTitle(hero, allBooks, useFileNameTitle),
                onContinue = { onOpenBook(hero.id) }
            )
        }
    }
    item {
        SortViewRow(sort = sort, onSort = onSort, gridView = gridView, onView = onView)
    }
    item { LibraryTabs(tab = tab, onTab = onTab) }
    item {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.search_books)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LibraryTabs(tab: Tab, onTab: (Tab) -> Unit) {
    ScrollableTabRow(
        selectedTabIndex = TABS.indexOfFirst { it.first == tab },
        edgePadding = 0.dp,
        // Match the Windows version: tabs use the background color (Bg),
        // not the card/surface color.
        containerColor = MaterialTheme.colorScheme.background
    ) {
        TABS.forEach { (t, label) ->
            Tab(
                selected = tab == t,
                onClick = { onTab(t) },
                text = { Text(stringResource(label)) }
            )
        }
    }
}

@Composable
private fun StatsStrip(
    total: Int,
    reading: Int,
    done: Int,
    fav: Int,
    onPick: (Tab) -> Unit
) {
    // Like the WPF stats strip: each card is one unit (count + label) and
    // tapping it selects the corresponding tab.
    val extras = LocalAppPaletteExtras.current
    val cards = listOf(
        Triple(total, Tab.ALL, R.string.all_books),
        Triple(reading, Tab.READING, R.string.currently_reading),
        Triple(done, Tab.COMPLETED, R.string.completed),
        Triple(fav, Tab.FAVORITES, R.string.favorites)
    )
    // Equal-height cards: in English "Currently reading" wraps to two
    // lines and used to make its card taller than its siblings (in
    // Persian the label fits one line, so it never showed). The row
    // takes the tallest card's height and every card stretches to it,
    // with its content vertically centered.
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cards.forEach { (count, t, labelRes) ->
            Card(
                onClick = { onPick(t) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("$count", style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    // Tsugi stitch in the top-end corner of each stat card
                    // (Windows ThemeStitch parity).
                    if (extras.charThemeId == "tsugi") {
                        StitchDiagonal(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 4.dp, end = 6.dp)
                                .size(30.dp)
                        )
                    }
                }
            }
        }
    }
}

/** book_books uses WPF "a|b" plural forms (split at runtime, like Loc.FN). */
@Composable
private fun pluralBooks(count: Int): String {
    val raw = stringResource(R.string.book_books)
    val parts = raw.split("|")
    val form = if (count == 1) parts.first() else parts.last()
    return form.format(count)
}

/**
 * Bite taken out of a completed book's cover (upstream ThemeBiteVisibility
 * parity): three overlapping background-colored circles chewing into the
 * top-end corner. Only shown with the «سوبارو · شکم‌پرستی» theme.
 */
@Composable
private fun BiteOverlay(modifier: Modifier = Modifier) {
    // Windows fills the bite with the window background (BgDark), so the
    // cover looks bitten through to what's behind the card.
    val bg = MaterialTheme.colorScheme.background
    val crumb = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Anchor the bite on the top-end corner: circles centered just
        // outside the corner so their arcs cut into the cover.
        drawCircle(bg, radius = w * 0.34f, center = Offset(w * 1.02f, -h * 0.04f))
        drawCircle(bg, radius = w * 0.26f, center = Offset(w * 0.78f, -h * 0.10f))
        drawCircle(bg, radius = w * 0.22f, center = Offset(w * 1.06f, h * 0.22f))
        // Crumbs scattered just inside the bite (Windows parity).
        listOf(
            Triple(0.58f, 0.80f, 0.055f),
            Triple(0.74f, 0.93f, 0.04f),
            Triple(0.44f, 0.95f, 0.045f)
        ).forEach { (fx, fy, fr) ->
            val cx = w * fx
            val cy = h * fy
            val r = w * fr
            val tri = Path().apply {
                moveTo(cx - r, cy + r * 0.6f)
                lineTo(cx + r * 0.4f, cy - r)
                lineTo(cx + r, cy + r * 0.8f)
                close()
            }
            drawPath(tri, crumb)
        }
    }
}

@Composable
private fun ProfileHeader(
    name: String,
    rankTitle: String,
    rankStarFill: Float,
    bookCount: Int,
    avatarPath: String,
    avatarSignature: String,
    onAvatarClick: () -> Unit,
    onRename: () -> Unit,
    onRankClick: () -> Unit
) {
    Card(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar (like the desktop client): the Novelfa icon by default,
            // a user-picked image when set. Tapping it opens the picker.
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center
            ) {
                val avatarFile = avatarPath.takeIf { it.isNotBlank() }?.let { File(it) }
                if (avatarFile != null && avatarFile.isFile) {
                    AsyncImage(
                        // Cache-busting request: the avatar file is
                        // overwritten in place on every pick, so a
                        // path-keyed request would keep showing the
                        // previous photo. The signature (mtime+length)
                        // changes on every rewrite, forcing a fresh
                        // load; it also recomposes this header, since
                        // the path string alone never changes.
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(avatarFile)
                            .memoryCacheKey("avatar-$avatarSignature")
                            .diskCacheKey("avatar-$avatarSignature")
                            .build(),
                        // An undecodable file used to render a blank
                        // circle; fall back to the default logo instead.
                        error = painterResource(id = R.drawable.app_logo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                // Rank badge with a colored outline, like the desktop client.
                // Tapping it opens the full ranks overview (same dialog as
                // the settings Progress card); the rest of the header still
                // opens rename, since this inner clickable wins on the badge.
                // Title + drawn stars (StarRow): the old glyph stars are
                // gone — fill is fractional, so progress reads precisely.
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clickable(onClick = onRankClick)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rankTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        StarRow(fill = rankStarFill, starSize = 13.dp)
                    }
                }
                Text(
                    pluralBooks(bookCount),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    book: Book,
    title: String,
    onContinue: () -> Unit
) {
    val extras = LocalAppPaletteExtras.current
    val scheme = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Card(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        // Windows parity: 1dp border + hero gradient background
        border = BorderStroke(1.dp, scheme.outline),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(scheme.secondary, scheme.tertiary)
                    )
                )
                // Windows accent glow: a radial wash of the accent color
                // at 32% fading out from the cover-side top corner.
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                scheme.primary.copy(alpha = 0.32f),
                                Color.Transparent
                            ),
                            center = Offset(
                                if (rtl) size.width * 0.88f else size.width * 0.12f,
                                size.height * 0.1f
                            ),
                            radius = size.width * 0.75f
                        )
                    )
                }
        ) {
        // Emblem watermark inside the hero (Windows: 190dp at 14%).
        if (extras.hasCharacterTheme && extras.emblemPath != null) {
            EmblemWatermark(
                path = extras.emblemPath!!,
                grad1 = extras.accentGrad1,
                grad2 = extras.accentGrad2,
                alpha = 0.14f,
                // matchParentSize: decoration only — it must not drive the
                // hero's height (a fixed 190dp here stretched the card and
                // left a void under the content).
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.CenterEnd
            )
        }
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The Windows "tilted book": an accent-gradient plate rotated
            // -10° behind the cover; the cover itself rotated +4°, with
            // 12dp rounded corners, crop fill and a soft shadow. (It used
            // at the card's original compact footprint.)
            val plateColors = if (extras.accentGrad1 == Color.Transparent) {
                listOf(scheme.primary, scheme.secondary)
            } else {
                listOf(extras.accentGrad1, extras.accentGrad2)
            }
            Box(
                modifier = Modifier.width(92.dp).height(106.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 72.dp, height = 96.dp)
                        .rotate(-10f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            Brush.linearGradient(
                                colors = plateColors.map { it.copy(alpha = 0.85f) }
                            )
                        )
                )
                AsyncImage(
                    model = coverImageRequest(book.coverPath),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 72.dp, height = 96.dp)
                        .shadow(10.dp, RoundedCornerShape(9.dp))
                        .rotate(4f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(scheme.surfaceVariant)
                )
            }
            // Tsugi torn seam between the cover and the text (Windows hero).
            if (extras.charThemeId == "tsugi") {
                StitchSeam(
                    textColor = scheme.onSurface,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .width(24.dp)
                        .height(72.dp)
                        .align(Alignment.CenterVertically)
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                // Windows text styles, with explicit colors (an uncolored
                // Text here renders in the default content color, which
                // read as near-black on the hero gradient).
                Text(
                    stringResource(R.string.continue_reading),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.primary
                )
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp, bottom = 2.dp)
                )
                Text(
                    stringResource(
                        R.string.chapter_progress,
                        book.lastChapterIndex + 1,
                        book.totalChapters
                    ),
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.progress),
                        fontSize = 10.sp,
                        color = scheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.percent_value, percentDisplay(book)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface
                    )
                }
                LinearProgressIndicator(
                    progress = { (progressPercent(book) / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = scheme.primary,
                    trackColor = scheme.outline
                )
                // Windows LightButton: in light themes it is accent with
                // on-accent text; in dark themes the text color is the
                // background and the page background is the foreground.
                val lightScheme = scheme.background.luminance() > 0.5f
                val btnFg = if (lightScheme) scheme.onPrimary else scheme.background
                Button(
                    onClick = onContinue,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (lightScheme) scheme.primary else scheme.onSurface,
                        contentColor = btnFg
                    ),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.continue_keep_reading),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Canvas(Modifier.size(12.dp)) {
                        // IcoArrowForward, mirrored with the layout direction.
                        val sc = size.width / 16f
                        val path = Path()
                        if (rtl) {
                            path.moveTo(13f * sc, 8f * sc); path.lineTo(3f * sc, 8f * sc)
                            path.moveTo(7f * sc, 4f * sc); path.lineTo(3f * sc, 8f * sc)
                            path.lineTo(7f * sc, 12f * sc)
                        } else {
                            path.moveTo(3f * sc, 8f * sc); path.lineTo(13f * sc, 8f * sc)
                            path.moveTo(9f * sc, 4f * sc); path.lineTo(13f * sc, 8f * sc)
                            path.lineTo(9f * sc, 12f * sc)
                        }
                        drawPath(
                            path,
                            color = btnFg,
                            style = Stroke(
                                width = 1.6.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }
        }
        // Tsugi stitch in the hero's top-start corner (Windows: 64dp).
        if (extras.charThemeId == "tsugi") {
            StitchDiagonalLarge(
                color = scheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(64.dp)
            )
        }
        }  // Close gradient Box
    }
}

@Composable
private fun SortViewRow(
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    gridView: Boolean,
    onView: (Boolean) -> Unit
) {
    var sortOpen by remember { mutableStateOf(false) }
    val sortLabel = when (sort) {
        SortMode.DEFAULT -> R.string.sort_default
        SortMode.RECENT_ADDED -> R.string.recently_added
        SortMode.LAST_READ -> R.string.last_read
        SortMode.NAME_AZ -> R.string.name_a_to_z
        SortMode.MOST_PROGRESS -> R.string.most_progress
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            TextButton(onClick = { sortOpen = true }) { Text(stringResource(sortLabel)) }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_default)) },
                    onClick = { onSort(SortMode.DEFAULT); sortOpen = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.recently_added)) },
                    onClick = { onSort(SortMode.RECENT_ADDED); sortOpen = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.last_read)) },
                    onClick = { onSort(SortMode.LAST_READ); sortOpen = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.name_a_to_z)) },
                    onClick = { onSort(SortMode.NAME_AZ); sortOpen = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.most_progress)) },
                    onClick = { onSort(SortMode.MOST_PROGRESS); sortOpen = false }
                )
            }
        }
        // Windows-parity view toggle: a segmented pill (card bg, 1dp
        // border, 10dp radius, 2dp padding) holding two 34x30 buttons;
        // the active view gets the AccentSoft background + accent icon,
        // the inactive one stays muted. (It used to be two TextButtons
        // with font glyphs "▦"/"☰" and enabled-as-selection, which
        // looked nothing like the desktop app.)
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewToggleButton(selected = gridView, onClick = { onView(true) }, grid = true)
            ViewToggleButton(selected = !gridView, onClick = { onView(false) }, grid = false)
        }
    }
}

/** One half of the view toggle; [grid] picks the Windows IcoGrid/IcoList path. */
@Composable
private fun ViewToggleButton(selected: Boolean, onClick: () -> Unit, grid: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 30.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.size(16.dp)) {
            val s = size.width / 16f
            val path = Path()
            if (grid) {
                // IcoGrid: four outlined squares in a 16-unit box.
                listOf(2.5f to 2.5f, 9f to 2.5f, 2.5f to 9f, 9f to 9f).forEach { (x, y) ->
                    path.addRect(
                        androidx.compose.ui.geometry.Rect(
                            x * s, y * s, (x + 4.5f) * s, (y + 4.5f) * s
                        )
                    )
                }
            } else {
                // IcoList: three plain horizontal lines.
                listOf(4f, 8f, 12f).forEach { y ->
                    path.moveTo(2.5f * s, y * s)
                    path.lineTo(13.5f * s, y * s)
                }
            }
            drawPath(
                path,
                color = color,
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
internal fun EmptyLibrary(tab: Tab, onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (tab == Tab.ALL) {
            Text(stringResource(R.string.empty_library))
            Text(
                stringResource(R.string.empty_library_hint),
                style = MaterialTheme.typography.labelMedium
            )
            TextButton(onClick = onImport) { Text(stringResource(R.string.add_book)) }
        } else {
            // Per-tab empty message: the library is not empty, this tab just
            // has no books yet (e.g. «you haven't finished any book yet»).
            Text(
                stringResource(
                    when (tab) {
                        Tab.READING -> R.string.empty_tab_reading
                        Tab.COMPLETED -> R.string.empty_tab_completed
                        Tab.RECENT -> R.string.empty_tab_recent
                        Tab.FAVORITES -> R.string.empty_tab_favorites
                        Tab.PRIVATE -> R.string.empty_tab_private
                        Tab.ALL -> R.string.empty_library // unreachable
                    }
                )
            )
        }
    }
}

@Composable
internal fun NoSearchResults() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.no_search_results))
    }
}

@Composable
internal fun PrivateLockedGate(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.private_section))
            TextButton(onClick = onUnlock) {
                Text(stringResource(R.string.enter_your_pin))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: Book,
    title: String,
    onClick: () -> Unit,
    onMenuClick: (() -> Unit)? = null,
    missingFile: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    // Upstream f352105: bite taken out of completed books' covers, visible
    // only with the «سوبارو · شکم‌پرستی» (tsugi) character theme.
    showBite: Boolean = false,
    // Tsugi stitch strip under the cover (Windows grid-card parity).
    showStitch: Boolean = false
) {
    Card(
        // Windows parity: 1dp border, 2dp primary when selected
        border = if (selected)
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.displayMedium
                )
                AsyncImage(
                    model = coverImageRequest(book.coverPath),
                    contentDescription = title,
                    // Crop fills the box completely (no side gaps); the box
                    // is 0.75 aspect so covers fill edge-to-edge.
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        // Cover flush with card edges; top corners rounded to
                        // match the card's shape.
                        .clip(
                            RoundedCornerShape(
                                topStart = 12.dp,
                                topEnd = 12.dp,
                                bottomStart = 0.dp,
                                bottomEnd = 0.dp
                            )
                        )
                )
                if (showBite) {
                    BiteOverlay(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(64.dp)
                    )
                }
                if (book.isFavorite) {
                    // Windows grid card: accent-filled circle, white heart.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(AppIconKind.Heart, tint = Color.White, size = 17.dp, filled = true)
                    }
                }
                if (missingFile) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    ) {
                        AppIcon(AppIconKind.Warning, tint = MaterialTheme.colorScheme.error, size = 16.dp)
                    }
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(6.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(
                            AppIconKind.Check,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            size = 15.dp
                        )
                    }
                }
                if (!book.collectionName.isNullOrBlank()) {
                    Text(
                        text = book.collectionName!!,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    )
                }
                if (onMenuClick != null) {
                    // Windows card button: near-white circle, IcoDots.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xE6FFFFFF))
                            .clickable { onMenuClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(AppIconKind.Dots, tint = Color(0xFF4C4766), size = 16.dp, filled = true)
                    }
                }
            }
            if (showStitch) {
                StitchStrip(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(10.dp)
                )
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = title,
                    // Windows parity (LibraryWindow.xaml: title Height="36"):
                    // the title block is always two lines tall, so every
                    // grid card has the same height whether its title wraps
                    // to one line or two. minLines is the Compose equivalent
                    // of that fixed height — no magic dp needed.
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                LinearProgressIndicator(
                    progress = { (progressPercent(book) / 100.0).toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
                Text(
                    text = if (isCompleted(book)) {
                        stringResource(R.string.completed)
                    } else {
                        stringResource(R.string.percent_value, percentDisplay(book))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun BookRow(
    book: Book,
    title: String,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    missingFile: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    Card(
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(44.dp, 60.dp)) {
                AsyncImage(
                    model = coverImageRequest(book.coverPath),
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (isCompleted(book)) {
                        stringResource(R.string.completed)
                    } else {
                        stringResource(R.string.percent_value, percentDisplay(book))
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                LinearProgressIndicator(
                    progress = { (progressPercent(book) / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
            if (missingFile) {
                AppIcon(AppIconKind.Warning, tint = MaterialTheme.colorScheme.error, size = 15.dp, modifier = Modifier.padding(end = 4.dp))
            }
            if (book.isFavorite) {
                AppIcon(AppIconKind.Heart, tint = MaterialTheme.colorScheme.primary, size = 15.dp, filled = true, modifier = Modifier.padding(end = 4.dp))
            }
            if (selected) {
                AppIcon(AppIconKind.Check, tint = MaterialTheme.colorScheme.secondary, size = 14.dp)
            }
            IconButton(onClick = onMenuClick) {
                AppIcon(AppIconKind.Dots, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp, filled = true)
            }
        }
    }
}

@Composable
internal fun BookMenuDialog(
    book: Book,
    all: List<Book>,
    useFileNameTitle: Boolean = false,
    onDismiss: () -> Unit,
    onFavorite: () -> Unit,
    onCollection: () -> Unit,
    onPrivate: () -> Unit,
    onReset: () -> Unit,
    onRemove: () -> Unit,
    onRelink: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                displayTitle(book, all, useFileNameTitle),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                TextButton(onClick = onFavorite) {
                    Text(
                        if (book.isFavorite) stringResource(R.string.remove_from_favorites)
                        else stringResource(R.string.favorites_2)
                    )
                }
                TextButton(onClick = onCollection) {
                    Text(stringResource(R.string.add_to_collection))
                }
                TextButton(onClick = onPrivate) {
                    Text(
                        if (book.isPrivate) stringResource(R.string.make_public)
                        else stringResource(R.string.make_private)
                    )
                }
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.start_over))
                }
                if (onRelink != null) {
                    TextButton(onClick = onRelink) {
                        Text(stringResource(R.string.relink_file))
                    }
                }
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.remove_book))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * True when the text's first strong-directional character is RTL
 * (Arabic/Persian/Hebrew). Empty or direction-neutral text defaults to RTL,
 * matching the app's Persian UI.
 */
private fun isRtlText(s: String): Boolean {
    for (c in s) {
        when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return true
}

@Composable
internal fun ProfileNameDialog(
    title: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    // Cursor starts AFTER the text (standard edit-field behavior) and
    // the field's layout direction follows the text's own script: a
    // Latin name inside an RTL layout used to show its cursor on the
    // side that looks like the end to an RTL reader while logically
    // sitting at the start, so backspace appeared to do nothing; with
    // a script-matched direction the visual and logical positions
    // agree, for Latin and Persian names alike.
    var field by remember(initial) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.labelMedium)
                CompositionLocalProvider(
                    LocalLayoutDirection provides
                        if (isRtlText(field.text)) LayoutDirection.Rtl
                        else LayoutDirection.Ltr
                ) {
                    OutlinedTextField(
                        value = field,
                        onValueChange = { field = it; error = false },
                        singleLine = true,
                        isError = error,
                        // Align with the text's own direction: Persian names sit
                        // on the right, Latin names on the left.
                        textStyle = LocalTextStyle.current.copy(
                            textAlign = if (isRtlText(field.text)) TextAlign.Right else TextAlign.Left
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = field.text.trim()
                if (clean.length < 2) {
                    error = true
                } else {
                    onSave(clean)
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
internal fun AboutDialog(onDismiss: () -> Unit, onShowGuide: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    // WPF parity: channel / donate / boost rows (DarkMessageBox.cs:636-638).
    val chLabel = stringResource(R.string.link_channel)
    val doLabel = stringResource(R.string.link_donate)
    val boLabel = stringResource(R.string.link_boost)
    val links = remember(chLabel, doLabel, boLabel) {
        listOf(
            Triple(chLabel, "t.me/NoveIFa", "https://t.me/NoveIFa"),
            Triple(doLabel, "Daramet.com/NovelFa", "http://Daramet.com/NovelFa"),
            Triple(boLabel, "t.me/NoveIFa?boost", "https://t.me/NoveIFa?boost")
        )
    }
    val copiedMsg = stringResource(R.string.copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            Column {
                Text(stringResource(R.string.about_body))
                if (version.isNotBlank()) {
                    Text(
                        stringResource(R.string.app_version, version),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // WPF parity: channel / donate / boost rows, each with
                // an open button and a copy-to-clipboard button.
                for ((label, display, url) in links) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = display,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                )
                            }
                        }) {
                            Text(stringResource(R.string.open_link))
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(url))
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        }) {
                            Text(stringResource(R.string.copy_link))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onShowGuide) {
                    Text(stringResource(R.string.view_guide))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        }
    )
}
