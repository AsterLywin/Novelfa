# Novelfa Android (Kotlin Native) — Design Spec

Date: 2026-10-13
Path: Bounded? No — Architectural (new platform, new project).
Decision: Approach A — Native single-module Kotlin (Compose + WebView). Standalone, offline, Play Store.

## 1. Context

Current: `EpubReader` WPF on .NET Framework 4.7.2 (Windows-only).
- Reader: `MainWindow.xaml(.cs)` ~2600 lines, HTML generation + WebView2 (`Microsoft.Web.WebView2.Wpf`, `WebView2Loader.dll`).
- Screens: `LibraryWindow`, `CollectionWindow`, `CollectionPickerWindow`, `WelcomeWindow`, `PinWindow`, `AccentWindow`, `CharacterThemeWindow`.
- Effects: `ThemeParticles.cs`, `CardMotion.cs` (Media/Media3D/Animation).
- Portable logic: `Services/EpubParser.cs` (Zip + LINQ-to-XML, `ResolveHref` incl. Persian URL decode), `Models/Book.cs`, `EpubBook.cs`, `ChapterItem.cs`, `SimpleJson.cs`, `Services/Loc.cs`, `LocStrings.cs`, `Loc.En.cs`, `AppSettings.cs` (key=value), `LibraryService.cs`, `SettingsService.cs` (SHA-256 PIN), `ProfileService.cs`, `RankService.cs`, `CharacterThemes.cs`.
- Fonts: `RecommendedFonts/Vazirmatn-Regular|Bold.ttf` embedded.
- Windows-only blockers: `shlwapi!StrCmpLogicalW` (`LibraryService.cs:14`), `gdi32!AddFontResourceEx` (`CustomFontService.cs:52-56`), `user32` + `PresentationSource.FromVisual` (`ThemeManager.cs:498+`), `AppData\EpubReaderApp` + `GetTempPath`, Edge WebView2 Runtime registry check.

Constraints agreed: full parity with Windows v1, standalone (manual SAF import, no sync), offline-first, Play Store, minSdk 29 (Android 10).

## 2. Architecture (APPROVED)

Single-module Kotlin app, 100% Jetpack Compose + Navigation 3.

```
com.novelfa.reader/
  parser/    EpubParser.kt, OpfModel.kt, HrefResolver.kt
  data/      BookEntity.kt, BookDao.kt, ReaderDb.kt, PrefsStore.kt, CoverStore.kt, FontStore.kt
  library/   LibraryViewModel.kt, Importer.kt (SAF), SortFa.kt
  reader/    ReaderViewModel.kt, HtmlBuilder.kt (port of WPF HTML/CSS), ReaderWebView.kt, ContinuousScroll.kt
  theme/     Themes.kt (6 + character day/night), Accent.kt
  settings/  PinManager.kt, SettingsViewModel.kt
  loc/       StringsFa.kt, StringsEn.kt (port of LocStrings)
```

- `minSdk 29`, `targetSdk 35`, Compose BOM, Material3, Room, DataStore-Preferences, Coil, Coroutines/Flow.
- No backend, no account, no sync (YAGNI).
- Keyboard shortcuts (WPF `ShortcutService`) NOT ported; replaced by touch gestures + reader toolbar buttons.

## 3. Components (WPF → Android mapping)

| Windows | Android |
|---|---|
| `LibraryWindow` (tabs all/reading/completed/recent/private + search) | `LibraryScreen` + `LibraryViewModel`, tabs + search bar, sort via `Collator(fa-IR)` + numeric |
| `CollectionWindow` / Picker | `CollectionsScreen` + bottom-sheet picker, `collectionName` column |
| `MainWindow` reader single/continuous | `ReaderScreen`: Android `WebView` + `HtmlBuilder`; single = one chapter HTML; continuous = concatenated HTML + `ScrollListener` (replaces `DispatcherTimer`); `docGen/contFirst/contLast` logic ported |
| `WelcomeWindow` | First-run onboarding screen (import hint) |
| `PinWindow` + `SettingsService` | `PinDialog` + `PinManager` (SHA-256, DataStore) |
| `AccentWindow` + `ThemeManager.Presets` (7 accents) | `AccentSheet` |
| `CharacterThemeWindow` + `CharacterThemes` + `CharacterPages` night/day | `CharacterSheet`, `Themes.kt` with same palettes |
| `DarkMessageBox` | Snackbar / AlertDialog |
| `ThemeParticles` / `CardMotion` (3D) | Simplified: subtle Compose animation only; no Media3D (perf + battery) |
| Support reminder (30-min timer) | Optional WorkManager/discrete dialog, same interval, off by default on mobile |

## 4. Data flow

1. User picks `.epub` via SAF (`OpenDocument`) → copy to `files/books/<id>.epub` (persist URI permission not needed after copy).
2. `EpubParser.load(epubUri)`:
   - `ZipFile` → `cache/epub_tmp/<uuid>/`, read `META-INF/container.xml` → OPF path (same as C#).
   - Parse manifest (`id→href/media-type`), cover (`meta[name=cover]` → image/* fallback → first image), spine `itemref` order → `chapters[i] = {title: "فصل i", file}`.
   - `HrefResolver`: strip `#`, `URLDecoder.decode`, `/`→separator, check decoded-exists-first (port of `ResolveHref`).
   - Cover → `files/covers/cover_<md5(epubPath)>.<ext>` via Coil; same naming as `CopyToPermanentCoverStorage`.
   - On exception: delete tmp dir, rethrow → UI fa message.
3. Insert `BookEntity` into Room (fields mirror `Book.cs`: `id, title, displayTitleLogic, filePath, coverPath, totalChapters, lastChapterIndex, fontFamily/Size, lineHeight, sideMargin, paragraphSpacing, dateAdded, lastOpenedDate, isPrivate, isFavorite, collectionName`; `progress = (last+1)/total`, `isCompleted/isInProgress/percentDisplay` same formulas; `DisplayTitle` dedup rule: same title → show filename).
4. Reader: load chapter file(s) → sanitize (strip scripts, resolve relative img/css to `file://`) → wrap with `HtmlBuilder` template: `bg/text/thumb/track/divider` per active `ReaderTheme` (6 static + character night/day), per-book `fontFamily/Size/lineHeight/margin/paragraphSpacing`, RTL auto (`dir` by first-strong-char, `readerTextIsLtr` equivalent), Vazirmatn `@font-face` + custom fonts.
5. On page change: update `lastChapterIndex/lastOpenedDate` (debounced), backup-safe write.
6. Settings/PIN/fonts in DataStore + `files/fonts/`; custom font registration = copy + `@font-face`, `isAvailable = file.exists` (no `gdi32`).

Storage mapping: `AppData\EpubReaderApp\library.json` → Room; `appsettings.txt`/`settings.txt`/`fonts.txt` → DataStore; `Covers/` → `files/covers/`; tmp extract → `cache/` (auto-cleared).

## 5. Error handling

- Corrupt EPUB / missing container.xml / missing OPF: delete tmp, dialog with ported `LocStrings` fa/en text; log to `files/logs/open.log` (port of `LogOpen`).
- Missing chapter file: skip + toast, continue spine.
- Missing cover: placeholder (accent initial letter).
- Room load fail: same guard as `lastLoadFailed` — never overwrite with empty list; keep backup `library.bak.json` on first successful load.
- WebView error (no network needed — all `file://`): error state with retry; no Edge-Runtime concept on Android.
- SAF denied / scoped-storage: explain + re-pick; Android 10+ `requestLegacyExternalStorage` NOT used.

## 6. Localization / RTL

- Port `Loc.T/F` + `LocStrings` dictionary to `strings.xml (fa default) + values-en`; keep same keys.
- Force RTL layout dir for fa; reader `dir=rtl/ltr` per chapter content (same `readerTextIsLtr` rule).
- Persian digits/sort: `Collator.getInstance(fa-IR)` + natural numeric segments (replaces `StrCmpLogicalW`).

## 7. Testing

- Unit (JUnit5): parser (container/OPF/manifest/spine/cover fallbacks, Persian `%20` hrefs), `HrefResolver`, natural-fa sort, `progress/isCompleted/percent`, PIN hash verify, `DisplayTitle` dedup.
- Instrumented: Room migration + backup guard, DataStore prefs, SAF import → parse → list → open → progress persist, WebView HTML builder theme snapshot (6 + sample character night/day).
- Manual gate: import 5 real Persian EPUBs, single/continuous, font size/family/line-height/margin, custom TTF add/remove, private+PIN lock/unlock, collections/favorites, support dialog, rotation + process-death restore, dark/light system.
- Perf: cold start < 2s on mid device, chapter open < 500ms, continuous 50-chapter concatenation paged (windowed append like `isAppendingChapter`).

## 8. Build / release

- Gradle version catalog, R8/minify, App Bundle, Play signing, `android:allowBackup=true` (Room + DataStore included, books/covers excluded → re-import note).
- Permissions: none (SAF only); no `READ_EXTERNAL_STORAGE` on 29+ for `OpenDocument`.
- Privacy: fully offline; no analytics in v1.

## 9. Non-goals (v1)

No cloud sync, no Windows↔Android sync, no store/download, no TTS, no annotations sync, no iOS.

## 10. Spec self-review

- Placeholders: none. All file mappings concrete.
- Consistency: single-module + Room/DataStore/WebView used uniformly; shortcuts explicitly dropped.
- Scope: single plan (Android v1 parity). KMP extraction deferred, noted as follow-up.
- Ambiguity resolved: standalone (no sync), offline, minSdk 29, full parity incl. character themes + custom fonts + continuous mode.

Next: `writing-plans` → implementation plan.
