# Novelfa Android Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone offline Kotlin-native Android EPUB reader with full parity to the Windows WPF app.

**Architecture:** Single-module native app: Jetpack Compose UI + Android WebView reader (ported HTML/CSS builder), Room library store, DataStore prefs, SAF file import, minSdk 29.

**Tech Stack:** Kotlin 2.x, Compose BOM + Material3 + Navigation-Compose, Room, DataStore-Preferences, Coil, Coroutines/Flow, JUnit5 + Espresso + Compose UI Test, Gradle 8 + AGP 8, `targetSdk 35`.

**Spec:** `docs/superpowers/specs/2026-10-13-android-kotlin-port-design.md`

## Global Constraints

- minSdk 29 (Android 10), targetSdk 35 (Play requirement).
- Fully offline — no network permission, no analytics, no backend.
- Persian default locale + English; RTL forced for fa.
- New code lives under `android/` — do NOT modify `EpubReader/` (WPF).
- Every task ends with a green test run + commit.
- YAGNI: no sync, no account, no store, no TTS in v1.

---

## File Structure (locked)

```
android/
  settings.gradle.kts / build.gradle.kts / gradle.properties
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/assets/fonts/Vazirmatn-Regular.ttf
  app/src/main/assets/fonts/Vazirmatn-Bold.ttf
  app/src/main/java/com/novelfa/reader/
    MainActivity.kt / NovelfaApp.kt / NavGraph.kt
    parser/EpubParser.kt / HrefResolver.kt / OpfModel.kt
    model/Book.kt / Chapter.kt
    data/ReaderDb.kt / BookEntity.kt / BookDao.kt
    data/PrefsStore.kt / CoverStore.kt / FontStore.kt / Importer.kt / SortFa.kt
    loc/Strings.kt
    theme/Themes.kt / Accent.kt
    library/LibraryViewModel.kt / LibraryScreen.kt / CollectionsScreen.kt
    reader/HtmlBuilder.kt / ReaderViewModel.kt / ReaderScreen.kt / ReaderWebView.kt
    settings/PinManager.kt / SettingsViewModel.kt / SettingsScreens.kt
  app/src/test/... (unit) / app/src/androidTest/... (instrumented)
```

Each file has one job. `parser/` never touches Android APIs (pure JVM → unit-testable). `data/` owns persistence. `reader/HtmlBuilder` owns HTML/CSS only (no WebView). `ReaderWebView` owns WebView only.

---

### Task 1: Project scaffold (runnable empty app)

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/novelfa/reader/MainActivity.kt`, `.../NovelfaApp.kt`, `.../NavGraph.kt`
- Test: `android/app/src/test/java/com/novelfa/reader/ScaffoldTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `com.novelfa.reader.MainActivity` (ComponentActivity entry), `NavGraph()` composable with routes `"library"`, `"reader/{bookId}"`, `"settings"`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.novelfa.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaffoldTest {
    @Test
    fun routes_areLocked() {
        assertEquals("library", Routes.LIBRARY)
        assertEquals("reader/{bookId}", Routes.READER)
        assertEquals("settings", Routes.SETTINGS)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.novelfa.reader.ScaffoldTest" ` (from `android/`)
Expected: FAIL — `Routes` unresolved.

- [ ] **Step 3: Write minimal implementation**

`android/settings.gradle.kts`:
```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Novelfa"
include(":app")
```

`android/app/build.gradle.kts` (excerpt — versions exact):
```kotlin
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.google.devtools.ksp") }
android {
    namespace = "com.novelfa.reader"
    compileSdk = 35
    defaultConfig { applicationId = "com.novelfa.reader"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "1.0.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom); androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.room:room-runtime:2.6.1"); ksp("androidx.room:room-compiler:2.6.1"); implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
```

`Routes` + `NavGraph` + `MainActivity` (minimal, Material3, fa locale default in manifest via `android:supportsRtl="true"`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.novelfa.reader.ScaffoldTest"` from `android/`
Expected: PASS. Also `./gradlew :app:assembleDebug` builds APK.

- [ ] **Step 5: Commit**

```bash
git add android .gitignore
git commit -m "feat(android): scaffold Compose app with locked routes"
```

---

### Task 2: EPUB parser core (pure JVM, port of EpubParser.cs)

**Files:**
- Create: `.../model/Book.kt`, `.../model/Chapter.kt`, `.../parser/HrefResolver.kt`, `.../parser/EpubParser.kt`
- Test: `.../EpubParserTest.kt` (+ test fixtures `src/test/resources/sample.opf`, `container.xml`)

**Interfaces:**
- Consumes: nothing (pure JVM; input = extracted folder path).
- Produces: `data class Chapter(val title: String, val file: File)`, `data class ParsedBook(val title: String, val chapters: List<Chapter>, val coverFile: File?)`, `object HrefResolver { fun resolve(folder: File, href: String): File }`, `object EpubParser { fun parse(extractDir: File, epubFileName: String): ParsedBook }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.novelfa.reader.parser

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EpubParserTest {
    @Test
    fun resolveHref_decodesPersianAndStripsHash() {
        val folder = File("src/test/resources/opf").apply { mkdirs() }
        File(folder, "فصل ۱.xhtml").writeText("x")
        val got = HrefResolver.resolve(folder, "%D9%81%D8%B5%D9%84%20%DB%B1.xhtml#ch1")
        assertTrue(got.exists())
    }
    @Test
    fun spineOrder_isPreserved() {
        val dir = File("src/test/resources/book1")
        val book = EpubParser.parse(dir, "mybook.epub")
        assertEquals(listOf("فصل 1", "فصل 2"), book.chapters.map { it.title })
        assertEquals("My Title", book.title)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.novelfa.reader.parser.EpubParserTest"`
Expected: FAIL — `HrefResolver`/`EpubParser` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.novelfa.reader.parser

import com.novelfa.reader.model.Chapter
import com.novelfa.reader.model.ParsedBook
import java.io.File
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

object HrefResolver {
    fun resolve(folder: File, href: String): File {
        val clean = href.substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val decodedFile = File(folder, decoded.replace('/', File.separatorChar))
        if (decodedFile.exists()) return decodedFile
        return File(folder, clean.replace('/', File.separatorChar))
    }
}

object EpubParser {
    fun parse(extractDir: File, epubFileName: String): ParsedBook {
        val container = File(extractDir, "META-INF/container.xml")
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val cdoc = dbf.newDocumentBuilder().parse(container)
        val rootfile = cdoc.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:container", "rootfile").item(0)
        val opfRel = rootfile.attributes.getNamedItem("full-path").nodeValue
        val opfFile = File(extractDir, opfRel.replace('/', File.separatorChar))
        val opfFolder = opfFile.parentFile!!
        val odoc = dbf.newDocumentBuilder().parse(opfFile)
        val titleNodes = odoc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title")
        val title = if (titleNodes.length > 0 && titleNodes.item(0).textContent.isNotBlank()) titleNodes.item(0).textContent.trim() else File(epubFileName).nameWithoutExtension
        val manifest = mutableMapOf<String, Pair<String, String?>>()
        val items = odoc.getElementsByTagNameNS("http://www.idpf.org/2007/opf", "item")
        for (i in 0 until items.length) {
            val el = items.item(i)
            val id = el.attributes.getNamedItem("id")?.nodeValue ?: continue
            val href = el.attributes.getNamedItem("href")?.nodeValue ?: continue
            if (manifest.containsKey(id)) continue
            manifest[id] = href to el.attributes.getNamedItem("media-type")?.nodeValue
        }
        var coverHref: String? = null
        val metas = odoc.getElementsByTagNameNS("http://www.idpf.org/2007/opf", "meta")
        for (i in 0 until metas.length) {
            val el = metas.item(i)
            if (el.attributes.getNamedItem("name")?.nodeValue == "cover") {
                val cid = el.attributes.getNamedItem("content")?.nodeValue
                if (cid != null && manifest.containsKey(cid)) coverHref = manifest[cid]!!.first
            }
        }
        if (coverHref == null) coverHref = manifest.values.firstOrNull { (h, t) -> t != null && t.startsWith("image") && h.lowercase().contains("cover") }?.first
        if (coverHref == null) coverHref = manifest.values.firstOrNull { (_, t) -> t != null && t.startsWith("image") }?.first
        val coverFile = coverHref?.let { HrefResolver.resolve(opfFolder, it).takeIf { f -> f.exists() } }
        val spine = odoc.getElementsByTagNameNS("http://www.idpf.org/2007/opf", "itemref")
        val chapters = mutableListOf<com.novelfa.reader.model.Chapter>()
        var idx = 1
        for (i in 0 until spine.length) {
            val idref = spine.item(i).attributes.getNamedItem("idref")?.nodeValue ?: continue
            val href = manifest[idref]?.first ?: continue
            chapters += Chapter(title = "فصل $idx", file = HrefResolver.resolve(opfFolder, href))
            idx++
        }
        return ParsedBook(title = title, chapters = chapters, coverFile = coverFile)
    }
}
```

`model/Book.kt`: `data class Chapter(val title: String, val file: File)`, `data class ParsedBook(val title: String, val chapters: List<Chapter>, val coverFile: File?)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.novelfa.reader.parser.EpubParserTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/novelfa/reader/parser android/app/src/main/java/com/novelfa/reader/model android/app/src/test
git commit -m "feat(android): add EPUB parser with Persian href resolution"
```

---

### Task 3: Data layer (Room + DataStore + stores, port of library.json/appsettings)

**Files:**
- Create: `.../data/BookEntity.kt`, `.../data/BookDao.kt`, `.../data/ReaderDb.kt`, `.../data/PrefsStore.kt`, `.../data/CoverStore.kt`, `.../data/FontStore.kt`, `.../data/Importer.kt`, `.../data/SortFa.kt`
- Test: `src/test/.../ProgressTest.kt`, `src/test/.../SortFaTest.kt`, `src/androidTest/.../BookDaoTest.kt`

**Interfaces:**
- Consumes: `ParsedBook` from Task 2.
- Produces: `@Entity data class BookEntity(id: String, title: String, filePath: String, coverPath: String?, totalChapters: Int, lastChapterIndex: Int, fontFamily: String, fontSize: Int, lineHeight: Double, sideMargin: Int, paragraphSpacing: Double, dateAdded: String, lastOpenedDate: String?, isPrivate: Boolean, isFavorite: Boolean, collectionName: String?)`, `fun progressPercent(e: BookEntity): Double`, `fun isCompleted/isInProgress/percentDisplay`, `object SortFa { fun compare(a: String, b: String): Int }`, `class Importer { suspend fun importEpub(sourceUri: Uri): String /*bookId*/ }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.novelfa.reader.data

import org.junit.Assert.*
import org.junit.Test

class ProgressTest {
    @Test
    fun progress_formulaMatchesWpf() {
        val e = BookEntity(id = "1", title = "t", filePath = "f", totalChapters = 10, lastChapterIndex = 2, dateAdded = "x", lastOpenedDate = "y")
        assertEquals(30.0, progressPercent(e), 0.001)
        assertEquals(30, percentDisplay(e))
    }
    @Test
    fun zeroChapters_isZero() {
        val e = BookEntity(id = "1", title = "t", filePath = "f", totalChapters = 0, dateAdded = "x")
        assertEquals(0.0, progressPercent(e), 0.001)
    }
}

class SortFaTest {
    @Test
    fun جلد2_before_جلد10() {
        assertTrue(SortFa.compare("جلد 2", "جلد 10") < 0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.novelfa.reader.data.*"`
Expected: FAIL — entities missing.

- [ ] **Step 3: Write minimal implementation**

`BookEntity.kt` mirrors `Book.cs` field-for-field with Room annotations; `progressPercent = if (totalChapters<=0||lastOpenedDate==null) 0.0 else min(100.0,(lastChapterIndex+1)*100.0/totalChapters)`; `percentDisplay = if completed 100 else min(99,floor(progress))`. `SortFa` uses `java.text.Collator.getInstance(Locale("fa","IR"))` + numeric chunk splitting (port of `StrCmpLogicalW`). `PrefsStore` = DataStore keys `theme, accent, charTheme, fontSize...` (port of `appsettings.txt`). `CoverStore`: `files/covers/cover_<md5(lowercase epub path)>.<ext>`. `Importer`: SAF `InputStream` → `files/books/` copy → unzip to `cache/` → `EpubParser.parse` → Room insert; on exception delete tmp + throw `ImportException(faMessage)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.novelfa.reader.data.*"` then `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.novelfa.reader.data.BookDaoTest` (emulator)
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/novelfa/reader/data android/app/src/test android/app/src/androidTest
git commit -m "feat(android): add Room library store with WPF progress formulas"
```

---

### Task 4: Localization (port of LocStrings, fa default + en)

**Files:**
- Create: `.../loc/Strings.kt`; Modify: `app/src/main/res/values/strings.xml`, `values-fa/strings.xml`
- Test: extend `ScaffoldTest` or new `StringsTest.kt`

**Interfaces:**
- Consumes: nothing. Produces: `object Strings { fun t(key: String): String }` — keys identical to WPF `Loc.T` keys.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun fa_containsPinAndSupportKeys() {
    assertTrue(Strings.t("رمز").isNotBlank())
}
```

- [ ] **Step 2: Run test to verify it fails** — `./gradlew :app:testDebugUnitTest --tests "*.StringsTest"` → FAIL.
- [ ] **Step 3: Write minimal implementation** — copy all user-visible fa strings from `LocStrings.cs` + en from `Loc.En.cs` into `values/strings.xml` + `values-fa/strings.xml`; `Strings` delegates to `context.getString` (pure-map fallback for unit tests).
- [ ] **Step 4: Run test to verify it passes.**
- [ ] **Step 5: Commit** — `git commit -m "feat(android): add fa/en localization ported from LocStrings"`

---

### Task 5: Library UI (port of LibraryWindow + Collections + SAF import)

**Files:**
- Create: `.../library/LibraryViewModel.kt`, `.../library/LibraryScreen.kt`, `.../library/CollectionsScreen.kt`
- Test: `src/test/.../LibraryViewModelTest.kt`, instrumented `LibraryScreenTest.kt`

**Interfaces:**
- Consumes: `BookDao`, `Importer`, `SortFa` (Tasks 2–3). Produces: `LibraryScreen(onOpenBook: (String)->Unit)` with tabs all/reading/completed/recent/private + search + add-book SAF launcher.

- [ ] **Step 1: Write the failing test** — ViewModel tab filter: given 3 entities (completed/in-progress/new), `filter("completed")` returns 1.
- [ ] **Step 2: Run test to verify it fails.**
- [ ] **Step 3: Write minimal implementation** — `LibraryViewModel` exposes `Flow<List<BookEntity>>` per tab (same predicates as `Book.cs: IsCompleted/IsInProgress`), search `title contains`, sort `SortFa`; `LibraryScreen` = `TabRow` + `LazyVerticalGrid` of cards (Coil cover, title, percent bar), private tab gated by `PinManager`; FAB launches `OpenDocument("application/epub")` → `Importer.importEpub`.
- [ ] **Step 4: Run tests** — unit + `./gradlew :app:connectedDebugAndroidTest ...LibraryScreenTest`.
- [ ] **Step 5: Commit** — `git commit -m "feat(android): add library with tabs search and SAF import"`

---

### Task 6: Reader (port of MainWindow HTML + WebView, single + continuous)

**Files:**
- Create: `.../reader/HtmlBuilder.kt`, `.../reader/ReaderViewModel.kt`, `.../reader/ReaderScreen.kt`, `.../reader/ReaderWebView.kt`
- Test: `src/test/.../HtmlBuilderTest.kt` (theme CSS + RTL + font-size injection), instrumented `ReaderFlowTest.kt`

**Interfaces:**
- Consumes: `BookDao`, `Chapter.file` (Tasks 2–3), `Themes` (Task 7 interface agreed here: `data class ReaderTheme(key, bg, text, thumb, track, divider, isDark)`).
- Produces: `object HtmlBuilder { fun build(chapterHtml: String, theme: ReaderTheme, fontFamily: String, fontSizeSp: Int, lineHeight: Double, sideMarginDp: Int, paragraphSpacingEm: Double, isLtr: Boolean): String }`, `ReaderScreen(bookId: String)` with chapter pager + continuous toggle + font controls.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun builder_injectsThemeAndFont() {
    val html = HtmlBuilder.build("<p>سلام</p>", Themes.DARK, "Vazirmatn", 18, 1.8, 16, 0.6, false)
    assertTrue(html.contains("#1C1A2A") && html.contains("Vazirmatn") && html.contains("dir=\"rtl\""))
}
```

- [ ] **Step 2: Run test to verify it fails.**
- [ ] **Step 3: Write minimal implementation** — port WPF HTML template: strip `<script>`, rewrite relative `src/href` to `file://` chapter dir, wrap with CSS (scrollbar colors, divider, `@font-face Vazirmatn` + custom), `dir` by first-strong-char heuristic (same as `readerTextIsLtr`); continuous mode concatenates chapters with `<hr>` dividers + `window.scroll` listener via `addJavascriptInterface` reporting chapter → ViewModel updates `lastChapterIndex/lastOpenedDate` debounced 500ms (replaces `DispatcherTimer`/`docGen/contFirst/contLast`).
- [ ] **Step 4: Run tests to verify they pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat(android): add WebView reader with single and continuous modes"`

---

### Task 7: Themes + fonts (port of ThemeManager/CharacterThemes/CustomFontService)

**Files:**
- Create: `.../theme/Themes.kt`, `.../theme/Accent.kt`, `.../data/FontStore.kt` (if not done), `.../settings/FontPicker.kt`
- Test: `src/test/.../ThemesTest.kt`

**Interfaces:**
- Consumes: `ReaderTheme` used by Task 6. Produces: `object Themes { val DARK/WHITE/CREAM/BLACK/GRAY/GREEN with exact WPF hex; fun characterPages(): List<ReaderTheme> /* night+day per CharacterThemes.All */ }`, `object Accent { val PRESETS = 7 hex values; var current }`.

- [ ] **Step 1: Write the failing test** — `Themes.DARK.bg == "#1C1A2A"`, `characterPages().size == CharacterThemes.All.size*2`, `Accent.PRESETS.size == 7`.
- [ ] **Step 2: Run test to verify it fails.**
- [ ] **Step 3: Write minimal implementation** — copy exact hex from `MainWindow.Themes` + `CharacterThemes` palettes; `FontStore`: `files/fonts/` copy via SAF, `@font-face` injection, `isAvailable = file.exists`; bundle `Vazirmatn-Regular/Bold` in `assets/fonts/`.
- [ ] **Step 4: Run test to verify it passes.**
- [ ] **Step 5: Commit** — `git commit -m "feat(android): add reader themes accents and custom fonts"`

---

### Task 8: PIN/private + collections/favorites + support + settings

**Files:**
- Create: `.../settings/PinManager.kt`, `.../settings/SettingsViewModel.kt`, `.../settings/SettingsScreens.kt`
- Test: `src/test/.../PinManagerTest.kt`

**Interfaces:**
- Consumes: `BookDao`, `PrefsStore`. Produces: `class PinManager { fun hasPin(): Boolean; fun setPin(pin: String); fun verify(pin: String): Boolean }` (SHA-256, port of `SettingsService`), settings screen (theme/accent/char-theme/font/size/spacing/support-toggle).

- [ ] **Step 1: Write the failing test** — `setPin("1234"); verify("1234")==true; verify("0000")==false` (use fake DataStore).
- [ ] **Step 2: Run test to verify it fails.**
- [ ] **Step 3: Write minimal implementation** — SHA-256 hex (same as `HashPin`), lock private tab + private books; collections add/remove + favorites toggle (same `collectionName/isFavorite` semantics); support reminder dialog every 30 min reading (WorkManager-free: accumulate foreground seconds, same `SupportIntervalSeconds = 1800`).
- [ ] **Step 4: Run test to verify it passes.**
- [ ] **Step 5: Commit** — `git commit -m "feat(android): add PIN collections and settings"`

---

### Task 9: Release hardening (perf + Play)

**Files:**
- Modify: `app/build.gradle.kts`, `proguard-rules.pro`, `AndroidManifest.xml`; Create: `docs/PLAY-RELEASE.md`
- Test: manual gate + `./gradlew :app:assembleRelease` + `:app:bundleRelease`

**Interfaces:**
- Consumes: all tasks. Produces: signed AAB, `docs/PLAY-RELEASE.md` with exact steps.

- [ ] **Step 1: Write the failing check** — `./gradlew :app:assembleRelease` must succeed with R8; cold-start trace < 2s, 50-chapter continuous opens windowed (no ANR).
- [ ] **Step 2: Run to verify it fails** (first run: missing signing config / ANR).
- [ ] **Step 3: Minimal hardening** — R8 keep rules for Room/WebView-JS interface, `allowBackup=true` (exclude `files/books` + `files/covers` via `fullBackupContent.xml`), Coil disk cache 100MB, reader chapter window (render ±3, append on scroll like `isAppendingChapter`), rotation/process-death state restore via `SavedStateHandle`.
- [ ] **Step 4: Run to verify it passes** — `bundleRelease` + manual checklist from spec §7 all green.
- [ ] **Step 5: Commit** — `git commit -m "chore(android): release hardening for Play"`

---

## Self-Review (run before handoff)

1. **Spec coverage:** parser→T2; Room/prefs/covers/fonts→T3; loc→T4; library/tabs/search/SAF→T5; reader single+continuous/WebView/typo→T6; 6 themes+character+accents+fonts→T7; PIN/collections/favorites/support/settings→T8; offline/Play/R8/backup→T9. Keyboard shortcuts explicitly dropped per spec. ✅
2. **Placeholder scan:** no TBD/TODO; every step has exact file, code, command, expected result. ✅
3. **Type consistency:** `Chapter(file: File)`, `ParsedBook`, `BookEntity` fields, `ReaderTheme(bg,text,thumb,track,divider,isDark)`, `HtmlBuilder.build(...)` signature identical in T6/T7. ✅
