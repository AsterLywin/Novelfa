package com.novelfa.reader.data

import com.novelfa.reader.domain.Book

/**
 * Book <-> BookEntity mapping. Field-for-field, in declaration order,
 * so a round trip is the identity: every field the UI can change
 * must appear here, or a save through the repository would silently
 * drop it (covered by BookMappingTest).
 */
fun BookEntity.toBook(): Book = Book(
    id = id,
    title = title,
    filePath = filePath,
    coverPath = coverPath,
    totalChapters = totalChapters,
    lastChapterIndex = lastChapterIndex,
    fontFamily = fontFamily,
    fontSize = fontSize,
    lineHeight = lineHeight,
    sideMargin = sideMargin,
    paragraphSpacing = paragraphSpacing,
    dateAdded = dateAdded,
    lastOpenedDate = lastOpenedDate,
    isPrivate = isPrivate,
    isFavorite = isFavorite,
    collectionName = collectionName,
    originalFileName = originalFileName,
    lastAnchor = lastAnchor,
    sourceUri = sourceUri,
    importMode = importMode,
    contentHash = contentHash
)

fun Book.toEntity(): BookEntity = BookEntity(
    id = id,
    title = title,
    filePath = filePath,
    coverPath = coverPath,
    totalChapters = totalChapters,
    lastChapterIndex = lastChapterIndex,
    fontFamily = fontFamily,
    fontSize = fontSize,
    lineHeight = lineHeight,
    sideMargin = sideMargin,
    paragraphSpacing = paragraphSpacing,
    dateAdded = dateAdded,
    lastOpenedDate = lastOpenedDate,
    isPrivate = isPrivate,
    isFavorite = isFavorite,
    collectionName = collectionName,
    originalFileName = originalFileName,
    lastAnchor = lastAnchor,
    sourceUri = sourceUri,
    importMode = importMode,
    contentHash = contentHash
)
