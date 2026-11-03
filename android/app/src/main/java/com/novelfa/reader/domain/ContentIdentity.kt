package com.novelfa.reader.domain

/**
 * Phase 2 (ADR-3 / F2): what a freshly computed content hash means
 * against the hash already stored on a book's row. The decision is
 * pure so it is unit-testable and shared by every writer
 * ([com.novelfa.reader.data.RoomBookRepository.noteContentHash],
 * the deferred hash job in
 * [com.novelfa.reader.data.Importer], the readers): no caller may
 * invent its own variant of this rule.
 *
 * - FIRST_KNOWN: the row had no hash yet. Persist the fresh hash;
 *   progress is untouched (this is first knowledge, not a change).
 * - UNCHANGED: the fresh hash equals the stored one. Nothing to do.
 * - CHANGED: the row had a DIFFERENT hash. The content really
 *   changed, so this is a NEW content identity (ADR-3 rule 7):
 *   persist the fresh hash AND reset progress to chapter 0 with
 *   no open date and no anchor — the old progress belongs to the
 *   old content. The old content's XP is never moved: the ledger
 *   is keyed by content hash, so the old entry simply stays
 *   under the old key and the new content starts from zero.
 * - NO_BOOK: no such row. Nothing to do.
 */
enum class ContentHashChange {
    FIRST_KNOWN,
    UNCHANGED,
    CHANGED,
    NO_BOOK
}

/** Pure decision for [ContentHashChange]; null [stored] = never known. */
fun contentHashChange(stored: String?, fresh: String): ContentHashChange =
    when {
        stored.isNullOrBlank() -> ContentHashChange.FIRST_KNOWN
        stored == fresh -> ContentHashChange.UNCHANGED
        else -> ContentHashChange.CHANGED
    }
