package com.novelfa.reader.reader

import com.novelfa.reader.theme.ReaderTheme

/**
 * Port of MainWindow.xaml.cs BuildChapterDivider (2046-2050).
 */
object ChapterDivider {

    fun divider(title: String, theme: ReaderTheme): String {
        val accent = if (theme.key.startsWith("rz.")) theme.thumb else "#A78BFA"
        return "<div style='margin:40px 0 24px 0;padding-top:20px;border-top:2px solid " +
            theme.divider + ";text-align:center;color:" + accent +
            ";font-weight:bold;font-size:16px;'>" +
            HtmlText.htmlEscape(title) + "</div>"
    }
}
