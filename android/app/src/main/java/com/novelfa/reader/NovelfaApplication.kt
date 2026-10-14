package com.novelfa.reader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

/**
 * App-wide Coil [ImageLoaderFactory] (Task 9): Coil 2's default disk cache
 * can grow past our budget, so the cover loader is pinned to 100 MB under
 * `cacheDir/covers`. Library/Collections screens keep using
 * `coil.compose.AsyncImage` with the default loader — this factory supplies
 * that default. (Do not confuse with [NovelfaApp], the root `@Composable`.)
 */
class NovelfaApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("covers"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .build()
}
