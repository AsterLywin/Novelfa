package com.novelfa.reader.reader

/**
 * Progress rule for the native image viewer (PDF/CBZ/ZIP/RAR/CBR),
 * taken from ComicScreen's behaviour (clean-room: behaviour only,
 * no ComicScreen code):
 *
 * progress follows the FURTHEST page on screen, not the first one.
 * ComicScreen's history builder (`ImgActivity.O`, decompiled
 * ComicScreen 2309) has an explicit special case for exactly this:
 * in 2-page mode, on the last pager item, it stores the spread's
 * SECOND page as the position instead of the first; and its scroll
 * mode reports the first OR the last visible item depending on the
 * scroll direction. Novelfa used to store the first page in both
 * cases, so a book could never reach 100%: the last spread of an
 * even-page book saved page N-2 (e.g. 35/36 = 97%), and a strip
 * whose viewport shows two pages saved the first visible item
 * (e.g. 35/36) even with the last page fully on screen.
 *
 * These two pure functions are the whole rule. The viewer wires
 * them in: [spreadLastPage] for dual-page spreads, [stripLeadingPage]
 * for the strip's leading edge. Single-page paged mode needs no
 * rule — the page itself is the furthest page on screen.
 * `currentPage` keeps its old meaning everywhere (first page of the
 * pair / first visible item) for restore, XP, chapters, bookmarks
 * and crop; only the saved progress, the page indicator, the
 * paged slider and the next-arrow enablement follow the furthest
 * page. The strip's restore anchor is stored separately
 * ([com.novelfa.reader.data.ReaderExtrasStore.stripAnchorPage]),
 * because the saved progress page's scroll offset is not the
 * stored offset's page.
 */

/**
 * Furthest page of a spread: the second page when the item shows
 * two pages side by side, else the only page it shows.
 */
fun spreadLastPage(firstPage: Int, secondPage: Int?): Int = secondPage ?: firstPage

/**
 * Leading edge of a strip in the direction of travel: the last
 * visible item while scrolling down, the first visible item while
 * scrolling up (or before any scroll). [lastVisible] is null when
 * the list has laid out no items yet.
 */
fun stripLeadingPage(firstVisible: Int, lastVisible: Int?, scrollingDown: Boolean): Int =
    if (scrollingDown) (lastVisible ?: firstVisible) else firstVisible
