package com.novelfa.reader

import android.net.Uri
import android.util.Base64

object Routes {
    const val LIBRARY = "library"
    const val READER = "reader/{bookId}"
    const val SETTINGS = "settings"
    const val COLLECTIONS = "collections"
    // Instant reading (خوندن درجا): a file opened from outside the
    // app lands on this route, never on the library. The Uri rides
    // as a Base64 URL-safe arg — a raw Uri string would break the
    // route on its '/' and '?' characters.
    const val OPENING = "opening/{uri}"

    fun openingRoute(uri: Uri): String =
        "opening/" + Base64.encodeToString(
            uri.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )

    fun decodeOpeningUri(encoded: String): Uri? = runCatching {
        Uri.parse(
            String(
                Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8
            )
        )
    }.getOrNull()
}
