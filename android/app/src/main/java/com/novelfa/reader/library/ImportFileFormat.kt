package com.novelfa.reader.library

/**
 * Book format of a picked file, derived from its display name for UI
 * purposes ONLY (the import progress dialog's per-file label). The
 * importer itself remains the authority on what a file really is: it
 * validates content, not extensions, so a mislabeled extension here
 * can never change what gets imported — at worst the dialog shows
 * the wrong label for one file, and unknown extensions show none.
 */
enum class ImportFileFormat {
    EPUB,
    PDF,
    TXT,
    MARKDOWN,
    WORD,
    CBZ,
    ZIP,
    FB2,
    RTF,
    UNKNOWN
}

/**
 * Pure extension mapping (case-insensitive; a missing, blank or
 * unknown extension is [ImportFileFormat.UNKNOWN], never a guess).
 */
fun importFormatOfFileName(name: String?): ImportFileFormat {
    val ext = name?.substringAfterLast('.', "")?.trim()?.lowercase()
        ?.takeIf { it.isNotEmpty() } ?: return ImportFileFormat.UNKNOWN
    return when (ext) {
        "epub" -> ImportFileFormat.EPUB
        "pdf" -> ImportFileFormat.PDF
        "txt" -> ImportFileFormat.TXT
        "md", "markdown" -> ImportFileFormat.MARKDOWN
        "docx" -> ImportFileFormat.WORD
        "cbz" -> ImportFileFormat.CBZ
        "zip" -> ImportFileFormat.ZIP
        "fb2", "fbz" -> ImportFileFormat.FB2
        "rtf" -> ImportFileFormat.RTF
        else -> ImportFileFormat.UNKNOWN
    }
}
