<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/app_logo.png" width="140" alt="Novelfa Logo"/>
</p>

<h1 align="center">Novelfa | ناول‌فا</h1>

<p align="center">
  <b>A Persian EPUB reader with first-class RTL support — for Android</b><br/>
  <i>کتاب‌خوان EPUB فارسی با پشتیبانی کامل از راست‌به‌چپ — برای اندروید.</i><br/>
  🇮🇷 <a href="README.md">فارسی</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" alt="Platform"/>
  <img src="https://img.shields.io/badge/Android-Kotlin%202.0.20%20%7C%20Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Android stack"/>
  <img src="https://img.shields.io/badge/Android-Min%20SDK%2029%20%7C%20Target%2035-3DDC84?logo=android&logoColor=white" alt="Android SDK"/>
  <img src="https://img.shields.io/badge/Version-1.0.0-4C8DFF" alt="Version"/>
  <img src="https://img.shields.io/badge/Status-In%20Development-F48C06" alt="Status"/>
  <img src="https://img.shields.io/badge/UI-FA%20%7C%20EN-2EA043" alt="UI languages"/>
  <img src="https://img.shields.io/badge/License-GPL--3.0-4C8DFF" alt="License"/>
</p>

<p align="center">
  <a href="#features">✨ Features</a> ·
  <a href="#xp">⭐ XP & Ranks</a> ·
  <a href="#backup">💾 Backup</a> ·
  <a href="#structure">🗂️ Project Structure</a> ·
  <a href="#build">🚀 Build & Run</a> ·
  <a href="#credits">🙏 Credits</a>
</p>

---

<a id="about"></a>

## 📖 About

**Novelfa** is a Persian EPUB reader: a personal library for novels and e-books that goes beyond opening a file — day/night character themes, book collections, an XP rank system, a private lock for personal books, and a portable backup — all built around one goal: comfortable, enjoyable reading of Persian books, with full RTL text and layout support.

This repository holds the **Android version** of Novelfa, developed by **[AsterLywin](https://github.com/AsterLywin)**. The project is based on **[NovelFaReader](https://github.com/ali1234536/NovelFaReader)**, the original Windows WPF app by **[ali1234536](https://github.com/ali1234536)**; the Windows app itself is not in this repository — it lives upstream with Ali.

### 🔎 At a glance

| | |
|---|---|
| 📚 **Book format** | EPUB |
| 📱 **Platform** | Android (Kotlin + Jetpack Compose) |
| 🌐 **UI languages** | Persian (RTL) · English (LTR) |
| 💾 **Backup file** | ‎`.novelfabak` — portable between devices |
| ⭐ **Progression** | XP and 12 rank titles + eternity marks |
| 🚧 **Status** | In development — version 1.0.0 |

### Contents

- [✨ Features](#features)
- [⭐ XP & Rank System](#xp)
- [💾 Backup & Restore](#backup)
- [🗂️ Project Structure](#structure)
- [🛠️ Tech Stack](#tech)
- [🚀 Build & Run](#build)
- [🙏 Credits](#credits)

---

<a id="features"></a>

## ✨ Features

### 📚 Reading

- Full EPUB rendering with automatic direction and layout from the book's own language (RTL / LTR)
- Two reading modes: **vertical** (continuous scroll or single-section) and **horizontal paged** (page-by-page)
- Images sized to the page in paged mode — no blank pages, no cropped images
- Per-book settings: font, text size (A+/A− buttons and numeric box, with a notice at the 10–72 limits), line spacing and margins
- 6 light/dark reading themes + a reading theme that follows the active character theme
- Resume exactly at the last position, with a per-chapter progress bar
- Chapter list with a number chip per chapter and the current chapter highlighted
- Page-turn by finger drag and by tap (outer quarters turn, middle half is inert, links never turn) with automatic pagination recovery on failure

### 🗂️ Library & Collections

- Search, sorting, and tabs for all books / reading / finished / favorites / recently opened
- Single and batch EPUB import, with an overwrite-confirmation dialog for duplicates
- Open an EPUB directly from Android's file manager with automatic import
- Multi-select and batch delete
- Personal collections: create, rename, pin, per-collection stats, and "continue reading" inside each collection
- A "continue reading" card and live library stats on the home screen
- A 5-page first-run guide (welcome, adding books, reading modes, XP ranks, backup & themes), re-openable from the About dialog

### 🎨 Themes & Personalization

- 7 day/night character themes, each with its own 16-color palette
- Emblem watermark, background aura glow, and themed animated particles — butterflies, petals, snow, and more (the app warns you if you enable particles while no theme is active)
- The "Tsugi" theme: decorative stitching on cards and covers, and a tear effect beside the book being read
- A bite effect on the covers of finished books
- Secret themes unlockable with codes — `BEAKO` · `EMT` · `TEA` — plus themes unlocked by rank
- Every locked theme has two gates — legacy rank or XP title — and tapping it shows a progress dialog for both paths
- Accent color with ready presets and a free HSV picker
- Built-in Persian fonts (Vazirmatn, Koodak, …) + adding your own font with preview and search + tap-to-select from a list
- UI language and direction independent of the book: Persian (RTL) or English (LTR)

### 🔐 Privacy

- PIN lock for entering the app and for private books — secure PBKDF2 hashing and temporary lockout after repeated failed attempts

---

<a id="xp"></a>

## ⭐ XP & Rank System

Reading progress is measured in **XP**, not just finished books.

**How do you earn XP?**

- The XP unit is **one book section** (spine item): `max(1, (words + 1000) / 2000)` — in CJK text, each character counts as one word.
- A section's XP is recorded only when you actually visited it, stayed at least 5 seconds, and left it; each section pays out once.
- The book-completion bonus — `max(10, bookXp / 10)` — is paid only when the last section is credited and at least 90% of sections are credited; each book pays it once.
- Private-shelf books earn no XP.

**12 titles, 5 stars each**

Stars are drawn fractionally and fill continuously with progress inside a title. Tapping the rank badge on the home screen opens the "View all ranks" dialog with every title, its progress, and its locks; the Settings progress card shows the current level, total XP, and the nearest locked theme.

| # | Title | عنوان | Min XP |
|:---:|---|---|---:|
| 1 | Newcomer | تازه‌وارد | 0 |
| 2 | Library Passerby | رهگذر کتابخانه | 25 |
| 3 | Book Lover | کتاب‌باز | 100 |
| 4 | Bookworm | کتاب‌خور | 300 |
| 5 | Story Hunter | شکارچی داستان | 750 |
| 6 | Librarian | کتابدار | 1500 |
| 7 | Lost in Worlds | غرق‌شده در دنیاها | 3000 |
| 8 | Keeper of the Forbidden Library | نگهبان کتابخانه‌ی ممنوعه | 5000 |
| 9 | Library Legend | افسانه‌ی کتابخانه | 8000 |
| 10 | Sage of Pleiades | دانای پلایادس | 12000 |
| 11 | Monster of Pleiades | هیولای پلایادس | 18000 |
| 12 | Devourer of Worlds | بلعنده‌ی جهان‌ها | 27000 |

**Eternity marks:** past the 27,000 XP cap, every 10,000 XP earns one eternity mark:

| # | Mark | نشانه |
|:---:|---|---|
| 1 | Ember of Eternity | اخگر ابدیت |
| 2 | Stardew | شبنم ستاره‌ای |
| 3 | Golden Leaf | برگ زرین |
| 4 | Silver Crescent | هلال نقره‌ای |
| 5 | Paper Sun | خورشید کاغذی |
| 6 | Galactic Crown | تاج کهکشانی |
| 7 | Keeper’s Wing | بالِ نگهبان کتاب |
| 8 | Heart of Pleiades | قلب پلایادس |
| 9 | Throne of Eternity | تخت ابدیت |
| 10 | Eternal Sun of Worlds | خورشید ابدیتِ جهان‌ها |

**Celebrations** appear only on the home screen (never mid-reading) and survive app restarts.

**Migration from the legacy rank:** if you ranked under the old finished-books system, your XP is built from the max of three values — the library estimate, the legacy-rank floor, and stored XP — and your rank never goes down. Floors: 0→0, 1→100, 2→300, 3→1500, 4→8000, 5→12000, 6→18000.

---

<a id="backup"></a>

## 💾 Backup & Restore — ‎`.novelfabak`

Your profile is packed into one portable file, so a fresh device — or a reinstall — restores everything to its place.

**What's inside a backup?**

User fonts, avatar, profile name, total XP, secret themes, finished-book count, and **per-book reading-progress records**.

**📍 Continue reading, even after a reinstall** — each book's current position is stored with the exact identity of its EPUB file. After restoring, just re-import the books: each one opens exactly where you left it — finished state, favorites, and collection membership come back too. Even deleting a book from the library and re-importing it keeps its place. Private-shelf books are excluded from the records, because the backup file is not encrypted.

**Security & validation**

- Full validation **before** any change is applied: SHA-256 hash per file + a whole-package integrity checksum
- An incomplete or tampered file is rejected and leaves no trace on the app

**Merge rules on restore**

| Data | Merge rule |
|---|---|
| 📕 Finished books | **Summed** with the current count |
| ⭐ Total XP | Only **up** (max of both sides) |
| 🔓 Secret themes | **Union** |
| 🔤 Fonts | Duplicates skipped by hash |
| 📍 Per-book reading records | **Higher** progress wins; progress never goes back, and finished state is sticky |

After a restore, a clear report is shown: rank change, new book total, and fonts added.

---

<a id="structure"></a>

## 🗂️ Project Structure

```
Novelfa/
├── android/                     # Android version (Kotlin + Compose)
│   ├── app/src/main/java/com/novelfa/reader/
│   │   ├── data/                # Database (Room), settings (DataStore), backup
│   │   ├── library/             # Library and collections
│   │   ├── model/               # Data models
│   │   ├── parser/              # EPUB parsing
│   │   ├── reader/              # Reading engine (WebView)
│   │   ├── settings/            # Settings and profile
│   │   └── theme/               # Themes, ranks, particles, decorative effects
│   └── task.cmd                 # Windows build tool (Debug / Release + output check)
├── docs/                        # Release guide and development plan documents
├── LICENSE                      # GPL-3.0 license
├── README.md                    # Persian version
└── README.en.md                 # This file (English)
```

---

<a id="tech"></a>

## 🛠️ Tech Stack

| | 📱 Android |
|---|---|
| **Language** | Kotlin 2.0.20 |
| **UI** | Jetpack Compose · Material 3 |
| **Framework** | Android SDK 35 (min 29) |
| **Book rendering** | Android WebView |
| **Storage** | Room + DataStore |
| **Build** | Gradle (AGP 8.5.2) |

---

<a id="build"></a>

## 🚀 Build & Run

Inside the [`android/`](android/) folder, run `task.cmd` — it builds Debug or Release, checks the outputs (APK/AAB), and reports the result in color. Opening the folder in Android Studio and running `assembleDebug` works too:

```bash
cd android
gradlew.bat assembleDebug
```

> 📦 Package: `com.novelfa.reader` · ‎`minSdk 29` · ‎`targetSdk 35` · version 1.0.0

> ⚠️ Release outputs are **unsigned** unless a keystore is configured; never upload an unsigned APK to Play (guide: [`docs/PLAY-RELEASE.md`](docs/PLAY-RELEASE.md)).

---

<a id="credits"></a>

## 🙏 Credits

This project is built on **[NovelFaReader](https://github.com/ali1234536/NovelFaReader)** by **[ali1234536](https://github.com/ali1234536)** — the Windows reader Novelfa was born from. My sincere thanks to Ali for creating and publishing the original project; without it, this work would not exist. 🌹

**The Android version** — a complete native rebuild for mobile — and the maintenance of this repository are by **[AsterLywin](https://github.com/AsterLywin)**.
