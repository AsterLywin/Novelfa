package com.novelfa.reader.model

import java.io.File

data class Chapter(val title: String, val file: File)

data class ParsedBook(val title: String, val chapters: List<Chapter>, val coverFile: File?)
